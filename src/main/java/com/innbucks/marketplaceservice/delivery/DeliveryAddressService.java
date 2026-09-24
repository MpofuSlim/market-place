package com.innbucks.marketplaceservice.delivery;

import com.innbucks.marketplaceservice.api.ApiException;
import com.innbucks.marketplaceservice.api.Msisdns;
import com.innbucks.marketplaceservice.catalog.util.TextSanitizer;
import com.innbucks.marketplaceservice.checkout.CheckoutProperties;
import com.innbucks.marketplaceservice.delivery.dto.AddressRequest;
import com.innbucks.marketplaceservice.delivery.dto.AddressResponse;
import com.innbucks.marketplaceservice.security.AuthenticatedUser;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The buyer's delivery address book.
 *
 * <p>Every operation is scoped to the caller BY SHAPE: there is no path or
 * query parameter naming a user, the reads carry {@code buyerUuid} in the
 * query, and a write resolves the row by (id, buyer) — so someone else's
 * address is a 404 indistinguishable from a missing one, and there is nothing
 * to point at another shopper.
 *
 * <p>Exactly one default per buyer, kept true three ways: the first address
 * saved becomes the default whatever the request says (a one-entry book with
 * no default helps nobody), a promotion demotes the incumbent BEFORE marking
 * the new one, and {@code uq_address_default_per_buyer} is the database
 * backstop under both.
 */
@Service
@RequiredArgsConstructor
public class DeliveryAddressService {

    private final DeliveryAddressRepository addressRepository;
    private final Msisdns msisdns;
    private final CheckoutProperties properties;
    private final DeliveryTownCatalog towns;

    @Transactional(readOnly = true)
    public List<AddressResponse> listMine(AuthenticatedUser buyer) {
        return addressRepository
                .findByBuyerUuidOrderByDefaultAddressDescCreatedAtDesc(buyerId(buyer))
                .stream().map(AddressResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public AddressResponse getMine(AuthenticatedUser buyer, UUID addressId) {
        return AddressResponse.from(require(buyer, addressId));
    }

    @Transactional
    public AddressResponse create(AuthenticatedUser buyer, AddressRequest request) {
        UUID buyerUuid = buyerId(buyer);
        long existing = addressRepository.countByBuyerUuid(buyerUuid);
        int cap = properties.getDelivery().getMaxAddressesPerBuyer();
        if (existing >= cap) {
            throw ApiException.conflict("address_limit_reached",
                    "You can save up to " + cap + " addresses. Delete one you no longer use.");
        }
        Instant now = Instant.now();
        DeliveryAddress address = DeliveryAddress.builder()
                .id(UUID.randomUUID())
                .buyerUuid(buyerUuid)
                // The first address is ALWAYS the default: a book whose only
                // entry is not the default makes every checkout ask a question
                // with one possible answer.
                .defaultAddress(existing == 0 || Boolean.TRUE.equals(request.makeDefault()))
                .createdAt(now)
                .updatedAt(now)
                .build();
        apply(address, request, now);
        if (address.isDefaultAddress()) {
            addressRepository.demoteOtherDefaults(buyerUuid, address.getId(), now);
        }
        return AddressResponse.from(addressRepository.save(address));
    }

    @Transactional
    public AddressResponse update(AuthenticatedUser buyer, UUID addressId, AddressRequest request) {
        DeliveryAddress address = require(buyer, addressId);
        Instant now = Instant.now();
        apply(address, request, now);
        // Demotion is NOT offered here: clearing the default by editing some
        // other field would leave the buyer with none, so only promotion is
        // reachable. Moving the default is done by promoting the other one.
        if (Boolean.TRUE.equals(request.makeDefault()) && !address.isDefaultAddress()) {
            addressRepository.demoteOtherDefaults(address.getBuyerUuid(), address.getId(), now);
            address.setDefaultAddress(true);
        }
        return AddressResponse.from(addressRepository.save(address));
    }

    /** Promote one entry to default (demote-then-mark, in one transaction). */
    @Transactional
    public AddressResponse makeDefault(AuthenticatedUser buyer, UUID addressId) {
        DeliveryAddress address = require(buyer, addressId);
        if (address.isDefaultAddress()) {
            return AddressResponse.from(address); // idempotent: already there
        }
        Instant now = Instant.now();
        addressRepository.demoteOtherDefaults(address.getBuyerUuid(), address.getId(), now);
        address.setDefaultAddress(true);
        address.setUpdatedAt(now);
        return AddressResponse.from(addressRepository.save(address));
    }

    /**
     * Deletes outright — no soft-delete column, because no order reads through
     * this row (orders snapshot the destination), so a delete can never orphan
     * or rewrite an order in flight.
     *
     * <p>Deleting the default promotes the most recent survivor, the same way
     * deleting a listing's primary image promotes one: leaving a book with
     * entries but no default is a state every checkout then has to handle.
     */
    @Transactional
    public void delete(AuthenticatedUser buyer, UUID addressId) {
        DeliveryAddress address = require(buyer, addressId);
        boolean wasDefault = address.isDefaultAddress();
        UUID buyerUuid = address.getBuyerUuid();
        addressRepository.delete(address);
        if (!wasDefault) {
            return;
        }
        addressRepository.flush(); // the unique index must see the delete first
        addressRepository.findByBuyerUuidOrderByDefaultAddressDescCreatedAtDesc(buyerUuid).stream()
                .findFirst()
                .ifPresent(survivor -> {
                    survivor.setDefaultAddress(true);
                    survivor.setUpdatedAt(Instant.now());
                    addressRepository.save(survivor);
                });
    }

    /** The checkout's pre-selection: the buyer's default, or empty when the
     *  book is. Never guesses at one of several. */
    @Transactional(readOnly = true)
    public DeliveryAddress requireForCheckout(AuthenticatedUser buyer, UUID addressId) {
        if (addressId != null) {
            return require(buyer, addressId);
        }
        return addressRepository.findByBuyerUuidAndDefaultAddressTrue(buyerId(buyer))
                .orElseThrow(() -> ApiException.badRequest("delivery_address_required",
                        "Choose a delivery address, or add one first"));
    }

    private DeliveryAddress require(AuthenticatedUser buyer, UUID addressId) {
        return addressRepository.findByIdAndBuyerUuid(addressId, buyerId(buyer))
                .orElseThrow(() -> ApiException.notFound("address_not_found", "Address not found"));
    }

    /** Sanitizes and normalises in ONE place so create and update can never
     *  diverge on what they accept. */
    private void apply(DeliveryAddress address, AddressRequest request, Instant now) {
        address.setLabel(blankToNull(TextSanitizer.sanitize(request.label())));
        address.setRecipientName(requireText(TextSanitizer.sanitize(request.recipientName()),
                "recipientName"));
        address.setRecipientMsisdn(msisdns.normalize(request.recipientMsisdn(), "recipientMsisdn"));
        address.setLine1(requireText(TextSanitizer.sanitize(request.line1()), "line1"));
        address.setLine2(blankToNull(TextSanitizer.sanitize(request.line2())));
        // The town is chosen from the list, never typed: coverage is a match.
        DeliveryTown town = towns.resolveForAddress(request.townCode(),
                TextSanitizer.sanitize(request.city()));
        address.setTownCode(town.getCode());
        address.setCity(town.getName());
        address.setArea(blankToNull(TextSanitizer.sanitize(request.area())));
        address.setLandmark(blankToNull(TextSanitizer.sanitize(request.landmark())));
        address.setUpdatedAt(now);
    }

    /**
     * Bean Validation's {@code @NotBlank} runs on the RAW value; sanitizing can
     * empty a field that was nothing but markup ({@code "<b></b>"}), and an
     * address line that survives as an empty string is a parcel with nowhere to
     * go. Re-checked after sanitizing, so the column constraint is never what
     * reports it.
     */
    private static String requireText(String value, String field) {
        String trimmed = blankToNull(value);
        if (trimmed == null) {
            throw ApiException.badRequest("invalid_address", field + " is required");
        }
        return trimmed;
    }

    private static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static UUID buyerId(AuthenticatedUser buyer) {
        return UUID.fromString(buyer.uuid());
    }
}
