package com.innbucks.marketplaceservice.publictest;

import com.innbucks.marketplaceservice.api.ApiException;
import com.innbucks.marketplaceservice.security.AuthenticatedUser;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The safety argument for the whole public test surface lives in this class, so
 * it is pinned here rather than left to the controller tests.
 */
class PublicTestIdentityTest {

    @Test
    void aDerivedIdIsVersionFiveSoItCanNeverBeARealCustomersUuid() {
        // user-service mints userUuid with UUID.randomUUID() -> version 4.
        // Pinning version 5 here is what makes "a caller cannot address a real
        // customer" structural rather than improbable. If this ever fails, the
        // whole surface has to come down, not just this test.
        assertThat(PublicTestIdentity.derivedUuid("alice").version()).isEqualTo(5);
        assertThat(UUID.randomUUID().version()).isEqualTo(4);
    }

    @Test
    void everyDerivedIdIsVersionFiveWhateverTheHandle() {
        IntStream.range(0, 500).forEach(i ->
                assertThat(PublicTestIdentity.derivedUuid("handle-" + i).version()).isEqualTo(5));
    }

    @Test
    void theVariantIsIetfSoTheValueIsAWellFormedUuid() {
        assertThat(PublicTestIdentity.derivedUuid("alice").variant()).isEqualTo(2);
    }

    @Test
    void theSameHandleAlwaysDerivesTheSameBuyer() {
        // The app keeps its basket across restarts only because this holds.
        assertThat(PublicTestIdentity.derivedUuid("alice"))
                .isEqualTo(PublicTestIdentity.derivedUuid("alice"));
    }

    @Test
    void handlesAreTrimmedSoAStrayNewlineIsNotADifferentShopper() {
        assertThat(PublicTestIdentity.derivedUuid("  alice\n"))
                .isEqualTo(PublicTestIdentity.derivedUuid("alice"));
    }

    @Test
    void differentHandlesGetDifferentBaskets() {
        assertThat(PublicTestIdentity.derivedUuid("alice"))
                .isNotEqualTo(PublicTestIdentity.derivedUuid("bob"));
    }

    @Test
    void handlesAreCaseSensitive() {
        // Not a design goal either way, but pinned so a later "helpful"
        // lowercasing is a deliberate change rather than a silent basket loss.
        assertThat(PublicTestIdentity.derivedUuid("Alice"))
                .isNotEqualTo(PublicTestIdentity.derivedUuid("alice"));
    }

    @Test
    void theSeparatorStopsAdjacentHandlesColliding() {
        // Without the 0x1F between namespace and name, a namespace ending in
        // "x" plus handle "y" would hash identically to namespace plus "xy".
        // Cheap to get wrong, invisible when you do.
        String withSeparator = "a" + ((char) 0x1F) + "b";
        assertThat(PublicTestIdentity.derivedUuid(withSeparator))
                .isNotEqualTo(PublicTestIdentity.derivedUuid("ab"));
    }

    @Test
    void aBlankHandleIsRefused() {
        assertThatThrownBy(() -> PublicTestIdentity.derivedUuid("   "))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("must not be blank");
        assertThatThrownBy(() -> PublicTestIdentity.derivedUuid(null))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void anOverLongHandleIsRefusedRatherThanHashed() {
        String tooLong = "x".repeat(PublicTestIdentity.MAX_HANDLE_LENGTH + 1);
        assertThatThrownBy(() -> PublicTestIdentity.derivedUuid(tooLong))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("at most");
    }

    @Test
    void aHandleAtTheLimitIsAccepted() {
        String atLimit = "x".repeat(PublicTestIdentity.MAX_HANDLE_LENGTH);
        assertThat(PublicTestIdentity.derivedUuid(atLimit)).isNotNull();
    }

    @Test
    void theCallerIsOnlyEverACustomer() {
        AuthenticatedUser buyer = PublicTestIdentity.buyerFor("alice");
        assertThat(buyer.roles()).containsExactly("CUSTOMER");
        assertThat(buyer.isSuperAdmin()).isFalse();
        assertThat(buyer.merchantId()).isNull();
        assertThat(buyer.shopId()).isNull();
    }

    @Test
    void anOpaqueHandleCarriesNoPhoneSoItCannotNameAPayerByItself() {
        // OrderService.resolveBuyerMsisdn reads this claim, and on the EcoCash
        // rail it is the handset a PIN prompt is delivered to. A demo buyer
        // stays phone-less — it can only name a payer explicitly in the order
        // body, where the value is validated like any other number.
        assertThat(PublicTestIdentity.buyerFor("alice").phone()).isNull();
    }

    @Test
    void aPhoneKeyedBuyerCarriesItsOwnNumberAsThePayer() {
        // The deliberate opposite: the phone IS the identity (the operator's
        // loyalty-parity direction, 2026-09-22), so orders under it are
        // payable by the basket's owner with no body field — the same rule a
        // real customer token gets, and the body is ignored the same way.
        AuthenticatedUser buyer = PublicTestIdentity.buyerForPhone("+263771234567");
        assertThat(buyer.phone()).isEqualTo("+263771234567");
        assertThat(buyer.roles()).containsExactly("CUSTOMER");
        assertThat(buyer.uuid())
                .isEqualTo(PublicTestIdentity.derivedUuid("+263771234567").toString());
    }

    @Test
    void theBuyerUuidMatchesTheDerivation() {
        assertThat(PublicTestIdentity.buyerFor("alice").uuid())
                .isEqualTo(PublicTestIdentity.derivedUuid("alice").toString());
    }
}
