package com.innbucks.marketplaceservice.catalog;

import com.innbucks.marketplaceservice.api.ApiException;
import com.innbucks.marketplaceservice.catalog.dto.CategoryNode;
import com.innbucks.marketplaceservice.catalog.dto.ListingPageResponse;
import com.innbucks.marketplaceservice.catalog.dto.ListingResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Public (unauthenticated) catalog reads. ACTIVE listings only — every other
 * status is invisible here, including on direct-id lookup, so a DRAFT or
 * ARCHIVED listing is indistinguishable from a nonexistent one (404).
 *
 * <p><b>Browse queries build predicates CONDITIONALLY — never bind a null.</b>
 * With four optional filters (q, category, condition, city) the old
 * one-query-per-combination discipline would need 16 repository methods, so
 * browse moved to a JPA {@link Specification}. The rule it must preserve is
 * the same one those explicit queries existed for: an absent filter
 * contributes NO predicate and therefore NO bind — the single
 * "(:q is null or lower(...) ...)" nullable-param query this all replaced
 * died on real Postgres with "function lower(bytea) does not exist" (an
 * untyped null bind is inferred as bytea; found by SecuritySurfaceIT in CI,
 * invisible to mocked-repo tests). CatalogServiceTest pins the
 * predicate-per-present-filter structure; SecuritySurfaceIT's anonymous
 * no-filter browse still proves it against real SQL.
 */
@Service
@RequiredArgsConstructor
public class CatalogService {

    /** Hard pagination cap (fleet input-hygiene rule). Oversized requests are
     *  clamped, never errored. */
    static final int MAX_PAGE_SIZE = 50;

    private static final Sort NEWEST_FIRST = Sort.by(Sort.Direction.DESC, "createdAt");

    private final ListingRepository listingRepository;
    private final ListingImageRepository listingImageRepository;
    private final CategoryRepository categoryRepository;
    private final ListingViewAssembler assembler;

    /**
     * Browse ACTIVE listings with optional filters:
     * <ul>
     *   <li>{@code q} — case-insensitive title 'contains' (LIKE wildcards in
     *       the client text are escaped, never act as wildcards);</li>
     *   <li>{@code category} — taxonomy code; a PARENT code expands to itself
     *       plus all its children (an unknown code simply matches nothing —
     *       lenient like the rest of this public surface);</li>
     *   <li>{@code condition} — one of {@link ItemCondition} (case-insensitive;
     *       a value outside the enum is a clean 400 {@code invalid_condition},
     *       never a silently unfiltered result);</li>
     *   <li>{@code city} — exact, case-insensitive.</li>
     * </ul>
     */
    @Transactional(readOnly = true)
    public ListingPageResponse browse(String q, String category, String condition, String city,
                                      int page, int size) {
        String titleFilter = blankToNull(q);
        if (titleFilter != null) {
            titleFilter = escapeLike(titleFilter);
        }
        String categoryFilter = blankToNull(category);
        ItemCondition conditionFilter = parseCondition(condition);
        String cityFilter = blankToNull(city);
        PageRequest pageable = PageRequest.of(Math.max(page, 0),
                Math.clamp(size, 1, MAX_PAGE_SIZE), NEWEST_FIRST);

        // Conditional predicate construction — the no-null-bind rule (see the
        // class comment). Each branch closes over a guaranteed-non-null value.
        Specification<Listing> spec = (root, query, cb) ->
                cb.equal(root.get("status"), ListingStatus.ACTIVE);
        if (titleFilter != null) {
            String pattern = "%" + titleFilter.toLowerCase(Locale.ROOT) + "%";
            spec = spec.and((root, query, cb) ->
                    cb.like(cb.lower(root.get("title")), pattern, '!'));
        }
        if (categoryFilter != null) {
            List<String> codes = expandCategory(categoryFilter);
            spec = spec.and((root, query, cb) -> root.get("categoryCode").in(codes));
        }
        if (conditionFilter != null) {
            ItemCondition value = conditionFilter;
            spec = spec.and((root, query, cb) -> cb.equal(root.get("condition"), value));
        }
        if (cityFilter != null) {
            String cityLower = cityFilter.toLowerCase(Locale.ROOT);
            spec = spec.and((root, query, cb) -> cb.equal(cb.lower(root.get("city")), cityLower));
        }
        Page<Listing> result = listingRepository.findAll(spec, pageable);
        return ListingPageResponse.from(assembler.toResponsePage(result));
    }

    @Transactional(readOnly = true)
    public ListingResponse getById(UUID id) {
        return listingRepository.findByIdAndStatus(id, ListingStatus.ACTIVE)
                .map(assembler::toResponse)
                .orElseThrow(() -> ApiException.notFound("listing_not_found", "Listing not found"));
    }

    /**
     * Raw PRIMARY-image bytes + stored content type for the public image
     * endpoint — the unchanged V2 contract on top of the V3 gallery.
     *
     * <p>DELIBERATELY served regardless of listing status (unlike
     * {@link #getById}, which hides non-ACTIVE listings): listing UUIDs are
     * unguessable, a product image leaks nothing sensitive, and the owning
     * merchant needs the image URL to preview a DRAFT listing before
     * publishing it. Missing listing, missing gallery, and missing primary
     * are the same 404 — the endpoint never confirms which.
     */
    @Transactional(readOnly = true)
    public ListingImageView getImage(UUID listingId) {
        return listingImageRepository.findByListingIdAndPrimaryImageTrue(listingId)
                .map(img -> new ListingImageView(img.getImageBytes(), img.getContentType()))
                .orElseThrow(() -> ApiException.notFound("image_not_found",
                        "No image has been uploaded for this listing"));
    }

    /**
     * Raw bytes of ONE gallery image, addressed by (listingId, imageId) —
     * the pair must match, so an imageId can never be probed across listings.
     * Same status-independent, indistinguishable-404 discipline as
     * {@link #getImage}.
     */
    @Transactional(readOnly = true)
    public ListingImageView getImageById(UUID listingId, UUID imageId) {
        return listingImageRepository.findByIdAndListingId(imageId, listingId)
                .map(img -> new ListingImageView(img.getImageBytes(), img.getContentType()))
                .orElseThrow(() -> ApiException.notFound("image_not_found",
                        "No image has been uploaded for this listing"));
    }

    /**
     * The full two-level category tree for the public endpoint: top-level
     * nodes with their children, both levels display-name ordered. One query;
     * the table is a few dozen migration-seeded rows, so in-memory grouping
     * is fine and the response is safely cacheable for an hour.
     */
    @Transactional(readOnly = true)
    public List<CategoryNode> categoryTree() {
        List<Category> all = categoryRepository.findAllByOrderByNameAsc();
        Map<String, List<Category>> childrenByParent = all.stream()
                .filter(c -> c.getParentCode() != null)
                .collect(Collectors.groupingBy(Category::getParentCode));
        return all.stream()
                .filter(c -> c.getParentCode() == null)
                .map(parent -> new CategoryNode(parent.getCode(), parent.getName(),
                        childrenByParent.getOrDefault(parent.getCode(), List.of()).stream()
                                .map(child -> new CategoryNode(child.getCode(), child.getName(), List.of()))
                                .toList()))
                .toList();
    }

    /** Mirror of event-service's {@code BannerImage} pair. */
    public record ListingImageView(byte[] bytes, String contentType) {}

    /** Parent code -> itself + its children; child/unknown code -> itself. */
    private List<String> expandCategory(String rawCode) {
        String code = rawCode.trim().toLowerCase(Locale.ROOT);
        List<String> codes = new ArrayList<>();
        codes.add(code);
        for (Category child : categoryRepository.findByParentCode(code)) {
            codes.add(child.getCode());
        }
        return codes;
    }

    /** Case-insensitive condition parse; garbage is a clean 400 rather than a
     *  silently unfiltered (or silently empty) result. */
    private static ItemCondition parseCondition(String raw) {
        String value = blankToNull(raw);
        if (value == null) {
            return null;
        }
        try {
            return ItemCondition.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw ApiException.badRequest("invalid_condition",
                    "condition must be one of NEW, USED_LIKE_NEW, USED_GOOD, USED_FAIR");
        }
    }

    private static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * Neutralises LIKE metacharacters in the user's search term using {@code !}
     * as the escape character (matching the escape char passed to
     * {@code cb.like} in {@link #browse}) — a query of {@code 100%} must
     * match titles containing "100%", not act as a wildcard.
     */
    private static String escapeLike(String value) {
        return value.replace("!", "!!")
                .replace("%", "!%")
                .replace("_", "!_");
    }
}
