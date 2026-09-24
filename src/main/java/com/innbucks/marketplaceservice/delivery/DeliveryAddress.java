package com.innbucks.marketplaceservice.delivery;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * A saved delivery destination in one buyer's address book (V9
 * {@code delivery_address} table) — the buyer-side counterpart of "my existing
 * payment methods": saved once, picked at checkout, never retyped on a phone
 * keyboard.
 *
 * <p>{@code recipientName}/{@code recipientMsisdn} are separate from the
 * buyer's own identity on purpose. Sending something to a relative is the
 * common case, and the number a courier must ring is whoever is at the door,
 * not whoever paid. The msisdn is normalised to E.164 before storage, exactly
 * like {@code market_order.buyer_msisdn}.
 *
 * <p>An order never reads through this row — it SNAPSHOTS the values (see
 * {@code MarketOrder}'s delivery columns). Editing "Home" must not redirect a
 * parcel already in flight, and deleting it must not erase where a delivered
 * order went.
 */
@Entity
@Table(name = "delivery_address")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DeliveryAddress {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "buyer_uuid", nullable = false)
    private UUID buyerUuid;

    /** The shopper's own name for the entry ("Home", "Work"). Optional — the
     *  address itself is what identifies it. */
    @Column(name = "label", length = 40)
    private String label;

    @Column(name = "recipient_name", nullable = false, length = 120)
    private String recipientName;

    /** E.164. Never logged in full. */
    @Column(name = "recipient_msisdn", nullable = false, length = 20)
    private String recipientMsisdn;

    @Column(name = "line1", nullable = false, length = 160)
    private String line1;

    @Column(name = "line2", length = 160)
    private String line2;

    @Column(name = "city", nullable = false, length = 80)
    private String city;

    @Column(name = "area", length = 80)
    private String area;

    /** The {@code delivery_town} this address is in (V14) — what delivery
     *  coverage is checked against. {@code city} is kept, set to the town's
     *  name. Null only on a pre-V14 address whose free-text city matched no
     *  town: it must be edited before it can take a DELIVERY order. */
    @Column(name = "town_code", length = 40)
    private String townCode;

    /** "Opposite the clinic, blue gate." In the markets this cell serves, the
     *  landmark is what actually gets a courier to the door. */
    @Column(name = "landmark", length = 160)
    private String landmark;

    /** At most one per buyer — the partial unique index
     *  {@code uq_address_default_per_buyer} is the backstop. */
    @Column(name = "is_default", nullable = false)
    private boolean defaultAddress;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** Wrapper {@code Long} for the same reason {@code MarketOrder}'s is: with
     *  a manually assigned id, Spring Data decides new-vs-existing by this
     *  field being null. */
    @Version
    @Column(name = "version", nullable = false)
    private Long version;
}
