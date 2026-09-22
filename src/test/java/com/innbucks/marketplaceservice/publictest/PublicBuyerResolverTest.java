package com.innbucks.marketplaceservice.publictest;

import com.innbucks.marketplaceservice.api.ApiException;
import com.innbucks.marketplaceservice.api.Msisdns;
import com.innbucks.marketplaceservice.security.AuthenticatedUser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The rule that makes the public rail behave like loyalty: a phone is ONE
 * buyer however it is spelled, a demo handle is untouched, and the space in
 * between fails closed instead of silently forking baskets.
 */
class PublicBuyerResolverTest {

    /** Same default region the ZW cell runs with. */
    private final PublicBuyerResolver resolver = new PublicBuyerResolver(new Msisdns("ZW"));

    @Test
    @DisplayName("every spelling of a number is the same buyer — the whole point")
    void everySpellingOfANumberIsOneBuyer() {
        AuthenticatedUser local = resolver.resolve("0771234567");
        AuthenticatedUser e164 = resolver.resolve("+263771234567");
        AuthenticatedUser spaced = resolver.resolve("0771 234 567");
        AuthenticatedUser punctuated = resolver.resolve("(077) 123-4567");

        assertThat(e164.uuid())
                .isEqualTo(local.uuid())
                .isEqualTo(spaced.uuid())
                .isEqualTo(punctuated.uuid());
    }

    @Test
    @DisplayName("a phone-keyed buyer carries the E.164 phone — it IS the payer")
    void phoneBuyerCarriesTheNormalisedPhone() {
        AuthenticatedUser buyer = resolver.resolve("0771234567");

        // OrderService.resolveBuyerMsisdn prefers this over the body, so an
        // order under this identity is payable by the number whose basket it
        // is — the same invariant a real customer token has.
        assertThat(buyer.phone()).isEqualTo("+263771234567");
        assertThat(buyer.roles()).containsExactly("CUSTOMER");
    }

    @Test
    @DisplayName("a canonical E.164 handle derives the id it always did — no basket orphaned")
    void canonicalE164DerivesTheHistoricalId() {
        // Before this resolver existed, "+263771234567" was hashed verbatim as
        // an opaque handle. Normalising it is a no-op, so the derived id is
        // unchanged — anyone who was already using the canonical form keeps
        // their data.
        assertThat(resolver.resolve("+263771234567").uuid())
                .isEqualTo(PublicTestIdentity.derivedUuid("+263771234567").toString());
    }

    @Test
    @DisplayName("an opaque demo handle resolves exactly as it always has, phone-less")
    void opaqueHandleIsUnchanged() {
        AuthenticatedUser alice = resolver.resolve("alice");

        assertThat(alice.uuid()).isEqualTo(PublicTestIdentity.derivedUuid("alice").toString());
        assertThat(alice.phone()).isNull();
    }

    @Test
    @DisplayName("digits that are not a dialable number are refused, never forked into an empty basket")
    void phoneShapedButUndialableIsRefused() {
        // Silently hashing "07712345" as an opaque handle would give a typo
        // its own empty basket — "my cart disappeared", undebuggable. Refusal
        // is the only answer the caller can act on.
        assertThatThrownBy(() -> resolver.resolve("07712345"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("handle");
        assertThatThrownBy(() -> resolver.resolve("123"))
                .isInstanceOf(ApiException.class);
    }

    @Test
    @DisplayName("one letter makes it a handle, not a failed phone")
    void aLetterMakesItAHandle() {
        // "alice2" and "test-77" are unambiguous demo labels; holding them to
        // phone validation would break every existing build script for nothing.
        assertThat(resolver.resolve("alice2").phone()).isNull();
        assertThat(resolver.resolve("test-77").phone()).isNull();
    }

    @Test
    @DisplayName("both flavours stay version-5, disjoint from every real userUuid")
    void bothFlavoursStayVersionFive() {
        assertThat(java.util.UUID.fromString(resolver.resolve("0771234567").uuid()).version())
                .isEqualTo(5);
        assertThat(java.util.UUID.fromString(resolver.resolve("alice").uuid()).version())
                .isEqualTo(5);
    }

    @Test
    @DisplayName("a phone is masked in log form; a demo handle stays readable")
    void loggableMasksPhonesOnly() {
        assertThat(resolver.loggable("+263771234567")).isEqualTo("****4567");
        assertThat(resolver.loggable("0771 234 567")).endsWith("567").startsWith("****");
        assertThat(resolver.loggable("alice")).isEqualTo("alice");
    }

    @Test
    @DisplayName("blank still refuses through the identity's own guard")
    void blankStillRefuses() {
        assertThatThrownBy(() -> resolver.resolve("   "))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("blank");
    }
}
