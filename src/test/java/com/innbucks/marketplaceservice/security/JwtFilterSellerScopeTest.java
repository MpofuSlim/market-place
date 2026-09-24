package com.innbucks.marketplaceservice.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the ONE rule that makes a caller a seller here since user-service
 * stopped minting a {@code merchantId} claim: the session's {@code orgId}, when
 * the caller is that organization's OWNER or ADMIN and it holds the
 * {@code marketplace} product. {@link JwtFilter} derives both the seller scope
 * ({@link AuthenticatedUser#merchantId()}) and the {@code MERCHANT_ADMIN} role
 * from exactly this; the role in the token's roles claim is not honoured alone.
 *
 * <p>Each refusal below is a real account shape: STAFF (a shop assistant added
 * to the business), a loyalty-only business, a session that has not chosen
 * among several organizations, and a token minted before organizations existed.
 */
class JwtFilterSellerScopeTest {

    private static final String ORG = "7b1e2c4d-9f3a-4e5b-8c6d-0a1b2c3d4e5f";

    @Test
    @DisplayName("the OWNER of an organization with the marketplace product sells for it")
    void owner_withMarketplace_sellsForTheOrganization() {
        assertThat(JwtFilter.sellingOrganizationOf(ORG, "OWNER", Set.of("loyalty", "marketplace"))).isEqualTo(ORG);
    }

    @Test
    @DisplayName("an ADMIN colleague sells for it too")
    void admin_withMarketplace_sellsForTheOrganization() {
        assertThat(JwtFilter.sellingOrganizationOf(ORG, "ADMIN", Set.of("marketplace"))).isEqualTo(ORG);
    }

    @Test
    @DisplayName("STAFF does not run the business, so sells for nobody")
    void staff_sellsForNobody() {
        assertThat(JwtFilter.sellingOrganizationOf(ORG, "STAFF", Set.of("marketplace"))).isNull();
    }

    @Test
    @DisplayName("a business without the marketplace product sells nothing, however senior the caller")
    void organizationWithoutMarketplace_sellsNothing() {
        assertThat(JwtFilter.sellingOrganizationOf(ORG, "OWNER", Set.of("loyalty", "ticketing"))).isNull();
        assertThat(JwtFilter.sellingOrganizationOf(ORG, "OWNER", Set.of())).isNull();
    }

    @Test
    @DisplayName("no organization chosen, or a pre-organizations token, sells nothing")
    void noOrganizationClaims_sellsNothing() {
        assertThat(JwtFilter.sellingOrganizationOf(null, "OWNER", Set.of("marketplace"))).isNull();
        assertThat(JwtFilter.sellingOrganizationOf(ORG, null, Set.of("marketplace"))).isNull();
        assertThat(JwtFilter.sellingOrganizationOf(ORG, "OWNER", null)).isNull();
    }

    @Test
    @DisplayName("names match exactly, as user-service mints them")
    void matchIsExact() {
        assertThat(JwtFilter.sellingOrganizationOf(ORG, "OWNER", Set.of("MARKETPLACE"))).isNull();
        assertThat(JwtFilter.sellingOrganizationOf(ORG, "owner", Set.of("marketplace"))).isNull();
    }

    @Test
    @DisplayName("a bare MERCHANT_ADMIN role is dropped when there is no selling organization")
    void bareRole_isDropped() {
        assertThat(JwtFilter.sellerRoles(Set.of("MERCHANT_ADMIN", "CUSTOMER"), null))
                .containsExactly("CUSTOMER");
    }

    @Test
    @DisplayName("a selling organization grants MERCHANT_ADMIN even to a token without the role")
    void sellingOrganization_grantsTheRole() {
        assertThat(JwtFilter.sellerRoles(Set.of("CUSTOMER"), ORG))
                .containsExactlyInAnyOrder("CUSTOMER", "MERCHANT_ADMIN");
    }

    @Test
    @DisplayName("every other role passes through untouched")
    void otherRoles_passThrough() {
        assertThat(JwtFilter.sellerRoles(Set.of("SUPER_ADMIN"), null)).containsExactly("SUPER_ADMIN");
        assertThat(JwtFilter.sellerRoles(null, null)).isEmpty();
    }

    // ------------------------------------------------------------------
    // COURIER (V14): any member of a selling business may carry its parcels
    // ------------------------------------------------------------------

    @Test
    @DisplayName("every member of a marketplace business - STAFF included - delivers for it")
    void anyMember_deliversForTheOrganization() {
        assertThat(JwtFilter.deliveringOrganizationOf(ORG, "OWNER", Set.of("marketplace"))).isEqualTo(ORG);
        assertThat(JwtFilter.deliveringOrganizationOf(ORG, "ADMIN", Set.of("marketplace"))).isEqualTo(ORG);
        // The point of the wider rule: drivers are usually staff, and STAFF sells nothing.
        assertThat(JwtFilter.deliveringOrganizationOf(ORG, "STAFF", Set.of("marketplace"))).isEqualTo(ORG);
        assertThat(JwtFilter.sellingOrganizationOf(ORG, "STAFF", Set.of("marketplace"))).isNull();
    }

    @Test
    @DisplayName("a business without the marketplace product, or no organization at all, delivers nothing")
    void noMarketplaceOrNoOrganization_deliversNothing() {
        assertThat(JwtFilter.deliveringOrganizationOf(ORG, "STAFF", Set.of("loyalty"))).isNull();
        assertThat(JwtFilter.deliveringOrganizationOf(null, "OWNER", Set.of("marketplace"))).isNull();
        assertThat(JwtFilter.deliveringOrganizationOf(ORG, "GUEST", Set.of("marketplace"))).isNull();
        assertThat(JwtFilter.deliveringOrganizationOf(ORG, "STAFF", null)).isNull();
    }

    @Test
    @DisplayName("COURIER is derived, never believed: a token claiming it without membership loses it")
    void courierRole_isDerivedNeverTrusted() {
        assertThat(JwtFilter.courierRoles(Set.of("CUSTOMER", "COURIER"), null))
                .containsExactly("CUSTOMER");
        assertThat(JwtFilter.courierRoles(Set.of("CUSTOMER"), ORG))
                .containsExactlyInAnyOrder("CUSTOMER", "COURIER");
    }
}
