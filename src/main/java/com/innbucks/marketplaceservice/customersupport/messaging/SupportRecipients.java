package com.innbucks.marketplaceservice.customersupport.messaging;

import com.innbucks.marketplaceservice.api.ApiException;
import com.innbucks.marketplaceservice.api.Msisdns;
import com.innbucks.marketplaceservice.customersupport.SubjectKind;
import com.innbucks.marketplaceservice.customersupport.SupportSubjects;
import com.innbucks.marketplaceservice.order.MarketOrder;
import com.innbucks.marketplaceservice.order.MarketOrderRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * Decides which number a support message goes to — ALWAYS a number already on
 * the record the agent looked up. There is deliberately no way to name a
 * destination: a console that can text any number is a console that can text
 * anyone as "InnBucks Marketplace Support", and the first stolen agent login
 * would prove it.
 *
 * <p>On an ORDER the agent picks a role (the payer, the gift recipient, the
 * delivery recipient) and the order supplies the number. On a BUYER the number
 * is one the buyer has paid from: the most recent by default, or another of
 * theirs the agent SELECTS — the selection is matched against the buyer's own
 * numbers and refused otherwise, so it chooses, it never supplies.
 */
@Component
@RequiredArgsConstructor
public class SupportRecipients {

    private final SupportSubjects subjects;
    private final MarketOrderRepository orderRepository;
    private final Msisdns msisdns;

    public record Recipient(SubjectKind subjectKind, UUID subjectId, RecipientRole role, String msisdn) {
    }

    /**
     * @param role          defaults to {@link RecipientRole#BUYER}
     * @param phoneSelector on a BUYER only: which of the buyer's own numbers
     * @throws ApiException 404 for an unknown subject; 400 {@code invalid_recipient}
     *                      or {@code recipient_not_on_record}; 422 {@code no_phone_on_record}
     */
    public Recipient resolve(SubjectKind kind, UUID subjectId, RecipientRole role, String phoneSelector) {
        RecipientRole wanted = role == null ? RecipientRole.BUYER : role;
        return switch (kind) {
            case ORDER -> forOrder(subjects.requireOrder(subjectId), wanted, phoneSelector);
            case BUYER -> forBuyer(subjectId, wanted, phoneSelector);
            case SELLER -> throw ApiException.badRequest("invalid_recipient",
                    "Support messages go to customers - open the buyer or the order");
        };
    }

    /** The collector of a collection parcel: the gift recipient when the
     *  order names one with a number, else the buyer — the rule the buyer's
     *  own code request follows. */
    public Recipient collectorOf(MarketOrder order) {
        if (order.getRecipientMsisdn() != null && !order.getRecipientMsisdn().isBlank()) {
            return new Recipient(SubjectKind.ORDER, order.getId(), RecipientRole.GIFT_RECIPIENT,
                    order.getRecipientMsisdn());
        }
        return new Recipient(SubjectKind.ORDER, order.getId(), RecipientRole.BUYER, order.getBuyerMsisdn());
    }

    public Recipient buyerOf(MarketOrder order) {
        return new Recipient(SubjectKind.ORDER, order.getId(), RecipientRole.BUYER, order.getBuyerMsisdn());
    }

    private Recipient forOrder(MarketOrder order, RecipientRole role, String phoneSelector) {
        if (phoneSelector != null && !phoneSelector.isBlank()) {
            throw ApiException.badRequest("invalid_recipient",
                    "On an order, choose the recipient by role - the order supplies the number");
        }
        String msisdn = switch (role) {
            case BUYER -> order.getBuyerMsisdn();
            case GIFT_RECIPIENT -> order.getRecipientMsisdn();
            case DELIVERY_RECIPIENT -> order.getDeliveryRecipientMsisdn();
        };
        if (msisdn == null || msisdn.isBlank()) {
            throw ApiException.unprocessable("no_phone_on_record", switch (role) {
                case BUYER -> "This order has no payer number on record";
                case GIFT_RECIPIENT -> "This order names no gift recipient's number";
                case DELIVERY_RECIPIENT -> "This order names no delivery recipient's number";
            });
        }
        return new Recipient(SubjectKind.ORDER, order.getId(), role, msisdn);
    }

    private Recipient forBuyer(UUID buyerUuid, RecipientRole role, String phoneSelector) {
        if (role != RecipientRole.BUYER) {
            throw ApiException.badRequest("invalid_recipient",
                    "A gift or delivery recipient belongs to one order - open the order to message them");
        }
        subjects.requireBuyer(buyerUuid);
        List<String> numbers = orderRepository.phonesOf(buyerUuid).stream()
                .map(MarketOrderRepository.BuyerPhone::getMsisdn)
                .toList();
        if (numbers.isEmpty()) {
            throw ApiException.unprocessable("no_phone_on_record",
                    "This buyer has not paid from any number yet, so there is none on record to message");
        }
        if (phoneSelector == null || phoneSelector.isBlank()) {
            return new Recipient(SubjectKind.BUYER, buyerUuid, RecipientRole.BUYER, numbers.getFirst());
        }
        String chosen = msisdns.normalize(phoneSelector, "phone");
        if (!numbers.contains(chosen)) {
            throw ApiException.badRequest("recipient_not_on_record",
                    "That is not a number this buyer has paid from - pick one from their record");
        }
        return new Recipient(SubjectKind.BUYER, buyerUuid, RecipientRole.BUYER, chosen);
    }
}
