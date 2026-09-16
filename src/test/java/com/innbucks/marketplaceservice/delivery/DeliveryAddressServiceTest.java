package com.innbucks.marketplaceservice.delivery;

import com.innbucks.marketplaceservice.api.ApiException;
import com.innbucks.marketplaceservice.api.Msisdns;
import com.innbucks.marketplaceservice.checkout.CheckoutProperties;
import com.innbucks.marketplaceservice.delivery.dto.AddressRequest;
import com.innbucks.marketplaceservice.delivery.dto.AddressResponse;
import com.innbucks.marketplaceservice.security.AuthenticatedUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pins the address book's two load-bearing rules: the caller can only ever
 * reach their OWN addresses, and exactly one of them is the default.
 */
class DeliveryAddressServiceTest {

    private static final UUID BUYER_UUID = UUID.randomUUID();
    private static final AuthenticatedUser BUYER = new AuthenticatedUser(
            BUYER_UUID.toString(), Set.of("CUSTOMER"), null, null, "+263771234567", "ZW");

    private DeliveryAddressRepository repository;
    private CheckoutProperties properties;
    private DeliveryAddressService service;

    @BeforeEach
    void setUp() {
        repository = mock(DeliveryAddressRepository.class);
        properties = new CheckoutProperties();
        service = new DeliveryAddressService(repository, new Msisdns("ZW"), properties);
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private static AddressRequest request(Boolean makeDefault) {
        return new AddressRequest("Home", "Tariro Moyo", "0771234567",
                "14 Samora Machel Ave", "Flat 3B", "Harare", "Avondale",
                "Opposite the clinic", makeDefault);
    }

    private static DeliveryAddress stored(UUID id, boolean isDefault) {
        Instant now = Instant.now();
        return DeliveryAddress.builder()
                .id(id).buyerUuid(BUYER_UUID).recipientName("Tariro Moyo")
                .recipientMsisdn("+263771234567").line1("14 Samora Machel Ave").city("Harare")
                .defaultAddress(isDefault).createdAt(now).updatedAt(now).version(0L).build();
    }

    // ------------------------------------------------------------------
    // Normalisation + sanitizing
    // ------------------------------------------------------------------

    @Test
    @DisplayName("The recipient number is normalised to E.164 before storage")
    void normalisesTheRecipientNumber() {
        when(repository.countByBuyerUuid(BUYER_UUID)).thenReturn(0L);

        AddressResponse saved = service.create(BUYER, request(null));

        // Stored dialable, in the same shape market_order.buyer_msisdn uses —
        // the courier rings this number.
        assertThat(saved.recipientMsisdn()).isEqualTo("+263771234567");
    }

    @Test
    @DisplayName("A number that cannot be dialled is refused, never stripped into one that parses")
    void refusesAnUndialableNumber() {
        when(repository.countByBuyerUuid(BUYER_UUID)).thenReturn(0L);

        assertThatThrownBy(() -> service.create(BUYER,
                new AddressRequest(null, "Tariro", "not-a-number", "14 Samora Machel Ave",
                        null, "Harare", null, null, null)))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).code())
                .isEqualTo("invalid_msisdn");
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("A field that is nothing but markup is refused, not stored as an empty line")
    void refusesAFieldThatSanitizesAway() {
        when(repository.countByBuyerUuid(BUYER_UUID)).thenReturn(0L);

        // @NotBlank passes on the RAW value; sanitizing empties it, and an
        // address line that survives as "" is a parcel with nowhere to go.
        assertThatThrownBy(() -> service.create(BUYER,
                new AddressRequest(null, "Tariro", "0771234567", "<b></b>",
                        null, "Harare", null, null, null)))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).code())
                .isEqualTo("invalid_address");
    }

    @Test
    @DisplayName("Blank optional fields are stored as null, not as empty strings")
    void blankOptionalsBecomeNull() {
        when(repository.countByBuyerUuid(BUYER_UUID)).thenReturn(0L);

        AddressResponse saved = service.create(BUYER,
                new AddressRequest("  ", "Tariro", "0771234567", "14 Samora Machel Ave",
                        "   ", "Harare", null, null, null));

        assertThat(saved.label()).isNull();
        assertThat(saved.line2()).isNull();
        assertThat(saved.area()).isNull();
    }

    // ------------------------------------------------------------------
    // Exactly one default
    // ------------------------------------------------------------------

    @Test
    @DisplayName("The FIRST address saved is the default whatever the request says")
    void firstAddressIsAlwaysTheDefault() {
        when(repository.countByBuyerUuid(BUYER_UUID)).thenReturn(0L);

        // A book whose only entry is not the default makes every checkout ask a
        // question with one possible answer.
        assertThat(service.create(BUYER, request(false)).defaultAddress()).isTrue();
    }

    @Test
    @DisplayName("A later address is only the default when asked for")
    void laterAddressesAreNotDefaultByAccident() {
        when(repository.countByBuyerUuid(BUYER_UUID)).thenReturn(1L);

        assertThat(service.create(BUYER, request(null)).defaultAddress()).isFalse();
        verify(repository, never()).demoteOtherDefaults(any(), any(), any());
    }

    @Test
    @DisplayName("A promotion DEMOTES the incumbent first, so the unique index never sees two")
    void promotionDemotesBeforeMarking() {
        UUID id = UUID.randomUUID();
        when(repository.findByIdAndBuyerUuid(id, BUYER_UUID))
                .thenReturn(Optional.of(stored(id, false)));

        service.makeDefault(BUYER, id);

        InOrder order = inOrder(repository);
        order.verify(repository).demoteOtherDefaults(eq(BUYER_UUID), eq(id), any());
        order.verify(repository).save(any());
    }

    @Test
    @DisplayName("Promoting the entry that is already default is an idempotent no-op")
    void promotingTheCurrentDefaultDoesNothing() {
        UUID id = UUID.randomUUID();
        when(repository.findByIdAndBuyerUuid(id, BUYER_UUID))
                .thenReturn(Optional.of(stored(id, true)));

        assertThat(service.makeDefault(BUYER, id).defaultAddress()).isTrue();
        verify(repository, never()).demoteOtherDefaults(any(), any(), any());
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("Editing another field can never CLEAR the default, only set it")
    void updateCannotClearTheDefault() {
        UUID id = UUID.randomUUID();
        when(repository.findByIdAndBuyerUuid(id, BUYER_UUID))
                .thenReturn(Optional.of(stored(id, true)));

        // makeDefault:false on the current default would otherwise leave the
        // buyer with none, and every checkout back to asking.
        assertThat(service.update(BUYER, id, request(false)).defaultAddress()).isTrue();
    }

    @Test
    @DisplayName("Deleting the default promotes the most recent survivor")
    void deletingTheDefaultPromotesASurvivor() {
        UUID id = UUID.randomUUID();
        UUID survivorId = UUID.randomUUID();
        DeliveryAddress survivor = stored(survivorId, false);
        when(repository.findByIdAndBuyerUuid(id, BUYER_UUID))
                .thenReturn(Optional.of(stored(id, true)));
        when(repository.findByBuyerUuidOrderByDefaultAddressDescCreatedAtDesc(BUYER_UUID))
                .thenReturn(List.of(survivor));

        service.delete(BUYER, id);

        InOrder order = inOrder(repository);
        order.verify(repository).delete(any());
        // Flushed first: the partial unique index must see the delete before
        // another row claims the default.
        order.verify(repository).flush();
        order.verify(repository).save(survivor);
        assertThat(survivor.isDefaultAddress()).isTrue();
    }

    @Test
    @DisplayName("Deleting a NON-default promotes nobody")
    void deletingANonDefaultChangesNothingElse() {
        UUID id = UUID.randomUUID();
        when(repository.findByIdAndBuyerUuid(id, BUYER_UUID))
                .thenReturn(Optional.of(stored(id, false)));

        service.delete(BUYER, id);

        verify(repository).delete(any());
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("Deleting the last address leaves an empty book, not an error")
    void deletingTheLastAddressIsFine() {
        UUID id = UUID.randomUUID();
        when(repository.findByIdAndBuyerUuid(id, BUYER_UUID))
                .thenReturn(Optional.of(stored(id, true)));
        when(repository.findByBuyerUuidOrderByDefaultAddressDescCreatedAtDesc(BUYER_UUID))
                .thenReturn(List.of());

        service.delete(BUYER, id);

        verify(repository, never()).save(any());
    }

    // ------------------------------------------------------------------
    // Scoping + caps
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Another buyer's address is the same 404 as a nonexistent one")
    void otherBuyersAddressesAre404() {
        UUID id = UUID.randomUUID();
        // The repository query carries the buyer, so a foreign row simply is
        // not found — there is no existence oracle here.
        when(repository.findByIdAndBuyerUuid(id, BUYER_UUID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getMine(BUYER, id))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).code())
                .isEqualTo("address_not_found");
    }

    @Test
    @DisplayName("The per-buyer cap is refused with a message that says what to do")
    void capIsEnforced() {
        properties.getDelivery().setMaxAddressesPerBuyer(2);
        when(repository.countByBuyerUuid(BUYER_UUID)).thenReturn(2L);

        assertThatThrownBy(() -> service.create(BUYER, request(null)))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    assertThat(((ApiException) ex).code()).isEqualTo("address_limit_reached");
                    assertThat(ex.getMessage()).contains("up to 2 addresses");
                });
    }

    // ------------------------------------------------------------------
    // Checkout resolution
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Checkout with no address named falls back to the buyer's default")
    void checkoutFallsBackToTheDefault() {
        DeliveryAddress fallback = stored(UUID.randomUUID(), true);
        when(repository.findByBuyerUuidAndDefaultAddressTrue(BUYER_UUID))
                .thenReturn(Optional.of(fallback));

        assertThat(service.requireForCheckout(BUYER, null)).isSameAs(fallback);
    }

    @Test
    @DisplayName("A buyer with an EMPTY book is asked for an address, never guessed at")
    void checkoutWithNoAddressesIsRefused() {
        when(repository.findByBuyerUuidAndDefaultAddressTrue(BUYER_UUID))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.requireForCheckout(BUYER, null))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).code())
                .isEqualTo("delivery_address_required");
    }

    @Test
    @DisplayName("A named address is used as-is, and must still belong to the caller")
    void namedAddressIsOwnerScoped() {
        UUID id = UUID.randomUUID();
        when(repository.findByIdAndBuyerUuid(id, BUYER_UUID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.requireForCheckout(BUYER, id))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).code())
                .isEqualTo("address_not_found");
    }
}
