package com.innbucks.marketplaceservice.seller;

import com.innbucks.marketplaceservice.api.Msisdns;
import com.innbucks.marketplaceservice.audit.AuditService;
import com.innbucks.marketplaceservice.cart.CartService;
import com.innbucks.marketplaceservice.catalog.CatalogService;
import com.innbucks.marketplaceservice.catalog.ListingRepository;
import com.innbucks.marketplaceservice.catalog.ListingService;
import com.innbucks.marketplaceservice.catalog.ListingViewAssembler;
import com.innbucks.marketplaceservice.checkout.BasketViewAssembler;
import com.innbucks.marketplaceservice.checkout.CheckoutService;
import com.innbucks.marketplaceservice.checkout.dto.CheckoutQuoteRequest;
import com.innbucks.marketplaceservice.favorite.FavoriteService;
import com.innbucks.marketplaceservice.order.OrderService;
import com.innbucks.marketplaceservice.order.OrderViewAssembler;
import com.innbucks.marketplaceservice.security.AuthenticatedUser;
import com.innbucks.marketplaceservice.settlement.SettlementQueryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Pageable;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
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
                mock(ApplicationEventPublisher.class), registry,
                SellerCollectionTest.policy(false, true),
                new com.innbucks.marketplaceservice.metrics.MarketplaceMetrics(
                        new io.micrometer.core.instrument.simple.SimpleMeterRegistry()));
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

    @Test
    @DisplayName("Every read that renders registry names runs OUTSIDE a transaction")
    void nameRenderingReadsAreNotTransactional() throws Exception {
        // Inside a transaction the resolver serves its cache and never calls
        // out (a call would hold a pooled connection for user-service's whole
        // latency). So a read that should show FRESH names must not open one;
        // re-adding @Transactional here would not fail anything visibly — the
        // names would just stop refreshing. NameLookupHoldsNoConnectionIT
        // measures the connection; this pins the list.
        List<Method> reads = List.of(
                CatalogService.class.getMethod("browse", CatalogService.BrowseQuery.class),
                CatalogService.class.getMethod("getById", UUID.class),
                CatalogService.class.getMethod("merchantProfile", UUID.class),
                FavoriteService.class.getMethod("listMine", AuthenticatedUser.class, int.class, int.class),
                ListingService.class.getMethod("listMine", AuthenticatedUser.class, int.class, int.class,
                        UUID.class),
                CartService.class.getMethod("getCart", AuthenticatedUser.class),
                // The cart mutations answer with the whole cart: the write commits
                // in its own transaction, then the cart is rendered outside it.
                CartService.class.getMethod("add", AuthenticatedUser.class, UUID.class, int.class),
                CartService.class.getMethod("add", AuthenticatedUser.class, UUID.class, UUID.class,
                        int.class),
                CartService.class.getMethod("setQuantity", AuthenticatedUser.class, UUID.class,
                        int.class),
                CartService.class.getMethod("setQuantity", AuthenticatedUser.class, UUID.class,
                        UUID.class, int.class),
                CartService.class.getMethod("remove", AuthenticatedUser.class, UUID.class),
                CartService.class.getMethod("remove", AuthenticatedUser.class, UUID.class,
                        UUID.class),
                CheckoutService.class.getMethod("quote", AuthenticatedUser.class,
                        CheckoutQuoteRequest.class),
                OrderService.class.getMethod("getMine", AuthenticatedUser.class, Pageable.class),
                OrderService.class.getMethod("getAll", UUID.class, Pageable.class),
                OrderService.class.getMethod("getOrder", AuthenticatedUser.class, UUID.class),
                SettlementQueryService.class.getMethod("payoutReportCsv", AuthenticatedUser.class),
                SellerService.class.getMethod("list", SellerStatus.class, int.class, int.class),
                SellerService.class.getMethod("displayNames", List.class));

        for (Method read : reads) {
            assertThat(read.isAnnotationPresent(NameResolvingRead.class))
                    .as("%s.%s is marked", read.getDeclaringClass().getSimpleName(), read.getName())
                    .isTrue();
        }
        for (Class<?> owner : reads.stream().map(Method::getDeclaringClass).distinct().toList()) {
            assertThat(owner.isAnnotationPresent(Transactional.class))
                    .as("%s must not be @Transactional at class level", owner.getSimpleName())
                    .isFalse();
            for (Method method : owner.getDeclaredMethods()) {
                if (method.isAnnotationPresent(NameResolvingRead.class)) {
                    assertThat(method.isAnnotationPresent(Transactional.class))
                            .as("%s.%s renders registry names and must not open a transaction",
                                    owner.getSimpleName(), method.getName())
                            .isFalse();
                }
            }
        }
    }

    @Test
    @DisplayName("The collaborators every name lookup passes through never open a transaction")
    void nameLookupCollaboratorsAreNotTransactional() throws Exception {
        // The lookup itself happens BELOW the marked reads — in the view
        // assemblers and in SellerService.displayNames(List, Map). A
        // @Transactional added to any of them would wrap the call again, and
        // orders, cart and quote would silently fall back to cached names with
        // nothing failing: NameLookupHoldsNoConnectionIT only drives the
        // catalogue family. So pin every class on the path, whole.
        List<Class<?>> collaborators = List.of(
                ListingViewAssembler.class,
                OrderViewAssembler.class,
                BasketViewAssembler.class,
                UserServiceOrganizationNameResolver.class);
        for (Class<?> owner : collaborators) {
            assertThat(owner.isAnnotationPresent(Transactional.class))
                    .as("%s must not be @Transactional at class level", owner.getSimpleName())
                    .isFalse();
            for (Method method : owner.getDeclaredMethods()) {
                assertThat(method.isAnnotationPresent(Transactional.class))
                        .as("%s.%s sits on the name-lookup path and must not open a transaction",
                                owner.getSimpleName(), method.getName())
                        .isFalse();
            }
        }
        Method gapFiller = SellerService.class.getMethod("displayNames", List.class, Map.class);
        assertThat(gapFiller.isAnnotationPresent(Transactional.class))
                .as("SellerService.displayNames(List, Map) calls the registry and must not "
                        + "open a transaction")
                .isFalse();
    }
}
