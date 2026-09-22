package com.innbucks.marketplaceservice.seller;

import com.innbucks.marketplaceservice.api.Msisdns;
import com.innbucks.marketplaceservice.audit.AuditService;
import com.innbucks.marketplaceservice.catalog.ListingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Who a seller is SHOWN as, once the name can come from two places.
 *
 * <p>marketplace-service stores merchant ids and no merchant names, so every
 * surface that should say who is selling rendered "Unnamed merchant" until an
 * operator approved the seller WITH a name — which meant the admin queue, the
 * finance payout report and the public catalogue all showed a bare UUID. The
 * loyalty registry that issued the id has the name; these cases pin how the
 * two sources are layered and what happens when the registry cannot answer.
 */
class MerchantNameResolutionTest {

    private static final UUID NAMED_LOCALLY = UUID.randomUUID();
    private static final UUID UNNAMED = UUID.randomUUID();
    private static final UUID NOWHERE = UUID.randomUUID();

    private MarketplaceSellerRepository sellers;
    private RecordingResolver registry;
    private SellerService service;

    /** Captures what was ASKED of the registry, which is half the contract. */
    private static final class RecordingResolver implements MerchantNameResolver {
        private final Map<UUID, String> known;
        private final List<Collection<UUID>> calls = new ArrayList<>();
        private RuntimeException boom;

        RecordingResolver(Map<UUID, String> known) {
            this.known = known;
        }

        @Override
        public Map<UUID, String> namesFor(Collection<UUID> merchantIds) {
            calls.add(List.copyOf(merchantIds));
            if (boom != null) {
                throw boom;
            }
            Map<UUID, String> out = new java.util.LinkedHashMap<>();
            merchantIds.forEach(id -> {
                if (known.containsKey(id)) {
                    out.put(id, known.get(id));
                }
            });
            return out;
        }
    }

    @BeforeEach
    void setUp() {
        sellers = mock(MarketplaceSellerRepository.class);
        registry = new RecordingResolver(Map.of(
                UNNAMED, "Chipo Electronics",
                NAMED_LOCALLY, "Registry Name Nobody Should See"));
        service = new SellerService(sellers, mock(ListingRepository.class),
                mock(AuditService.class), new Msisdns("ZW"),
                mock(ApplicationEventPublisher.class), registry);
    }

    private static MarketplaceSeller seller(UUID merchantId, String displayName) {
        return MarketplaceSeller.builder()
                .merchantId(merchantId)
                .status(SellerStatus.PENDING)
                .displayName(displayName)
                .createdAt(Instant.now())
                .build();
    }

    private void seed(MarketplaceSeller... rows) {
        when(sellers.findAllById(org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of(rows));
    }

    @Test
    @DisplayName("An operator-set name WINS over the registry, and is never asked about")
    void localNameWinsAndCostsNoLookup() {
        seed(seller(NAMED_LOCALLY, "Rudo Traders"));

        Map<UUID, String> names = service.displayNames(List.of(NAMED_LOCALLY));

        // A name somebody typed while vouching for this seller is a deliberate
        // choice; the registry must not overrule it on screen.
        assertThat(names).containsEntry(NAMED_LOCALLY, "Rudo Traders");
        // And it costs nothing: an approved, named seller never leaves the box.
        assertThat(registry.calls).isEmpty();
    }

    @Test
    @DisplayName("A seller with no local name is filled from the registry")
    void registryFillsTheGap() {
        seed(seller(UNNAMED, null));

        assertThat(service.displayNames(List.of(UNNAMED)))
                .containsEntry(UNNAMED, "Chipo Electronics");
    }

    @Test
    @DisplayName("A blank local name is treated as no name, not as an empty one")
    void blankLocalNameIsNotAName() {
        seed(seller(UNNAMED, "   "));

        assertThat(service.displayNames(List.of(UNNAMED)))
                .containsEntry(UNNAMED, "Chipo Electronics");
    }

    @Test
    @DisplayName("Only the UNNAMED ids are asked for — the lookup is scoped to the gaps")
    void onlyGapsAreLookedUp() {
        seed(seller(NAMED_LOCALLY, "Rudo Traders"), seller(UNNAMED, null));

        service.displayNames(List.of(NAMED_LOCALLY, UNNAMED));

        // This is what keeps the resolver off the catalogue's critical path as
        // sellers get approved: the ask shrinks, it does not stay page-sized.
        assertThat(registry.calls).hasSize(1);
        assertThat(registry.calls.getFirst()).containsExactly(UNNAMED);
    }

    @Test
    @DisplayName("One batched ask for a page, never one per row")
    void oneCallForTheWholePage() {
        List<UUID> page = List.of(UNNAMED, NOWHERE, UUID.randomUUID(), UUID.randomUUID());
        seed();

        service.displayNames(page);

        assertThat(registry.calls).hasSize(1);
        assertThat(registry.calls.getFirst()).hasSize(4);
    }

    @Test
    @DisplayName("A merchant the registry does not know is simply absent — never a placeholder")
    void unknownMerchantHasNoName() {
        seed();

        Map<UUID, String> names = service.displayNames(List.of(NOWHERE));

        // Absent, not "Unknown" or an empty string: every caller already
        // renders a missing name, and inventing one is a claim the platform
        // has no basis to make.
        assertThat(names).doesNotContainKey(NOWHERE);
    }

    @Test
    @DisplayName("A duplicated merchant across a page is asked about once")
    void duplicatesCollapse() {
        seed();

        service.displayNames(List.of(UNNAMED, UNNAMED, UNNAMED));

        assertThat(registry.calls.getFirst()).containsExactly(UNNAMED);
    }

    @Test
    @DisplayName("An empty page asks the registry nothing at all")
    void emptyPageIsNotACall() {
        assertThat(service.displayNames(List.of())).isEmpty();
        assertThat(registry.calls).isEmpty();
    }
}
