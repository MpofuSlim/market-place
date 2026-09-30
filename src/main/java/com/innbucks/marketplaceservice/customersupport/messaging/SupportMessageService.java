package com.innbucks.marketplaceservice.customersupport.messaging;

import com.innbucks.marketplaceservice.api.ApiException;
import com.innbucks.marketplaceservice.customersupport.SubjectKind;
import com.innbucks.marketplaceservice.customersupport.SupportAgent;
import com.innbucks.marketplaceservice.customersupport.SupportSubjects;
import com.innbucks.marketplaceservice.customersupport.dto.SupportMessagePreviewResponse;
import com.innbucks.marketplaceservice.customersupport.dto.SupportMessageRequest;
import com.innbucks.marketplaceservice.customersupport.dto.SupportMessageResponse;
import com.innbucks.marketplaceservice.customersupport.messaging.SupportMessageComposer.Composed;
import com.innbucks.marketplaceservice.customersupport.messaging.SupportRecipients.Recipient;
import com.innbucks.marketplaceservice.delivery.DeliveryMethod;
import com.innbucks.marketplaceservice.fulfilment.BuyerParcelRules;
import com.innbucks.marketplaceservice.fulfilment.DeliveryConfirmer;
import com.innbucks.marketplaceservice.fulfilment.FulfilmentService;
import com.innbucks.marketplaceservice.fulfilment.FulfilmentStatus;
import com.innbucks.marketplaceservice.fulfilment.OrderFulfilment;
import com.innbucks.marketplaceservice.fulfilment.OrderFulfilmentRepository;
import com.innbucks.marketplaceservice.fulfilment.notice.BuyerNoticeKind;
import com.innbucks.marketplaceservice.fulfilment.notice.BuyerNoticeOutcome;
import com.innbucks.marketplaceservice.fulfilment.notice.BuyerNoticeRecorder;
import com.innbucks.marketplaceservice.notify.MsisdnMasking;
import com.innbucks.marketplaceservice.notify.OrderNotificationComposer;
import com.innbucks.marketplaceservice.order.MarketOrder;
import com.innbucks.marketplaceservice.order.OrderPaid;
import com.innbucks.marketplaceservice.order.OrderStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * What support can send a customer: a message the agent types, or a platform
 * message sent again — the order confirmation, the seller's last parcel update,
 * or a FRESH collection code for a buyer who lost theirs.
 *
 * <p>Every send goes to a number on the record ({@link SupportRecipients}),
 * through the same claim → send → record pipeline ({@link SupportMessageSender})
 * and under the same limits, whatever its kind. None of it is
 * {@code @Transactional}: the gateway call must hold no connection.
 */
@Service
public class SupportMessageService {

    private final SupportRecipients recipients;
    private final SupportMessageComposer composer;
    private final SupportMessageSender sender;
    private final SupportSubjects subjects;
    private final OrderFulfilmentRepository fulfilmentRepository;
    private final FulfilmentService fulfilmentService;
    private final BuyerParcelRules buyerRules;
    private final BuyerNoticeRecorder noticeRecorder;
    private final long disputeWindowDays;

    public SupportMessageService(SupportRecipients recipients, SupportMessageComposer composer,
                                 SupportMessageSender sender, SupportSubjects subjects,
                                 OrderFulfilmentRepository fulfilmentRepository,
                                 FulfilmentService fulfilmentService, BuyerParcelRules buyerRules,
                                 BuyerNoticeRecorder noticeRecorder,
                                 @Value("${marketplace.settlement.dispute-window-days}") long disputeWindowDays) {
        this.recipients = recipients;
        this.composer = composer;
        this.sender = sender;
        this.subjects = subjects;
        this.fulfilmentRepository = fulfilmentRepository;
        this.fulfilmentService = fulfilmentService;
        this.buyerRules = buyerRules;
        this.noticeRecorder = noticeRecorder;
        this.disputeWindowDays = disputeWindowDays;
    }

    /**
     * What the customer would receive. Sends nothing, records nothing, spends
     * no limit. Refuses what the send would refuse EXCEPT length, which it
     * reports ({@code characters} against {@code maxCharacters}) so the
     * console can show a live count; the send refuses an over-long message.
     */
    public SupportMessagePreviewResponse preview(SupportMessageRequest request) {
        MessageChannel channel = channelOf(request.channel());
        Recipient recipient = recipients.resolve(request.subjectKind(), request.subjectId(), request.recipient(),
                request.phone());
        Composed composed = composer.composeUnchecked(request.text());
        sender.requireChannel(channel);
        boolean whatsappOnly = channel == MessageChannel.WHATSAPP;
        String text = whatsappOnly ? composed.whatsappText() : composed.smsText();
        return new SupportMessagePreviewResponse(channel.name(), recipient.role().name(),
                MsisdnMasking.mask(recipient.msisdn()), text, text.length(), composer.maxCharacters(channel),
                whatsappOnly ? null : SupportMessageComposer.smsSegments(composed.smsText()),
                !whatsappOnly && composed.transliterated(),
                channel == MessageChannel.SMS_THEN_WHATSAPP ? composed.whatsappText() : null);
    }

    /** A message the agent typed, to a customer on record. */
    public SupportMessageResponse sendCustom(SupportAgent agent, SupportMessageRequest request) {
        MessageChannel channel = channelOf(request.channel());
        Recipient recipient = recipients.resolve(request.subjectKind(), request.subjectId(), request.recipient(),
                request.phone());
        Composed composed = composer.composeCustom(request.text(), channel);
        sender.requireChannel(channel);
        return sender.send(agent, recipient, SupportMessageKind.CUSTOM, channel, composed);
    }

    /**
     * The buyer's order-paid confirmation, word for word, to the payer.
     *
     * @throws ApiException 409 {@code order_not_paid}
     */
    public SupportMessageResponse resendConfirmation(SupportAgent agent, UUID orderId, MessageChannel requested) {
        MessageChannel channel = channelOf(requested);
        MarketOrder order = subjects.requireOrder(orderId);
        if (order.getStatus() != OrderStatus.PAID) {
            throw ApiException.conflict("order_not_paid",
                    "Only a paid order has a confirmation to resend - this one is " + order.getStatus());
        }
        Composed texts = composer.template(OrderNotificationComposer.buyerOrderPaidMessage(OrderPaid.of(order)));
        sender.requireChannel(channel);
        return sender.send(agent, recipients.buyerOf(order), SupportMessageKind.ORDER_CONFIRMATION, channel, texts);
    }

    /**
     * The seller's last move on a parcel, told to the buyer again, word for
     * word: on its way / ready to collect, or marked delivered by the seller
     * (with the dispute window — the one message that protects the buyer's
     * money). When it goes out, the seller's card records that the buyer was
     * reached, exactly as the original notice would have.
     *
     * @throws ApiException 409 {@code nothing_to_resend}
     */
    public SupportMessageResponse resendParcelUpdate(SupportAgent agent, UUID orderId, UUID fulfilmentId,
                                                     MessageChannel requested) {
        MessageChannel channel = channelOf(requested);
        MarketOrder order = subjects.requireOrder(orderId);
        OrderFulfilment parcel = parcelOn(order, fulfilmentId);
        boolean partOfOrder = fulfilmentRepository.findByOrderIdOrderByCreatedAtAsc(order.getId()).size() > 1;
        String text;
        BuyerNoticeKind noticeKind;
        if (parcel.getStatus() == FulfilmentStatus.DISPATCHED) {
            text = OrderNotificationComposer.parcelDispatchedMessage(order.getOrderRef(),
                    parcel.getDeliveryMethod(), partOfOrder);
            noticeKind = parcel.getDeliveryMethod() == DeliveryMethod.COLLECTION
                    ? BuyerNoticeKind.READY_TO_COLLECT : BuyerNoticeKind.DISPATCHED;
        } else if (parcel.getStatus() == FulfilmentStatus.DELIVERED
                && parcel.getDeliveredBy() == DeliveryConfirmer.MERCHANT) {
            text = OrderNotificationComposer.parcelDeliveredBySellerMessage(order.getOrderRef(), partOfOrder,
                    disputeWindowDays);
            noticeKind = BuyerNoticeKind.DELIVERED_BY_SELLER;
        } else {
            throw ApiException.conflict("nothing_to_resend", "There is no seller update to resend for a parcel "
                    + "that is " + parcel.getStatus() + " - write the buyer a message instead");
        }
        sender.requireChannel(channel);
        SupportMessageResponse sent = sender.send(agent, recipients.buyerOf(order), SupportMessageKind.PARCEL_UPDATE,
                channel, composer.template(text));
        // Only a delivered resend updates the card: a failed one leaves the
        // original notice's outcome standing (and alerts no seller again).
        noticeRecorder.record(parcel.getId(), noticeKind,
                DeliveredVia.WHATSAPP.name().equals(sent.deliveredVia())
                        ? BuyerNoticeOutcome.WHATSAPP : BuyerNoticeOutcome.SMS);
        return sent;
    }

    /**
     * A FRESH collection code, texted straight to whoever collects (the gift
     * recipient when the order names one with a number, else the buyer). The
     * live code stops working, as when the buyer mints one in the app.
     *
     * <p>The limits are checked BEFORE the code is minted — a refused send
     * must not have already replaced the code the collector holds — and the
     * code never reaches the agent: not in the response, not in the stored
     * message. If the send fails, the buyer can still mint one in the app.
     */
    public SupportMessageResponse sendCollectCode(SupportAgent agent, UUID orderId, UUID fulfilmentId,
                                                  MessageChannel requested) {
        MessageChannel channel = channelOf(requested);
        MarketOrder order = subjects.requireOrder(orderId);
        OrderFulfilment parcel = parcelOn(order, fulfilmentId);
        BuyerParcelRules.Refusal refusal = buyerRules.collectCodeRefusal(parcel);
        if (refusal != null) {
            throw refusal.toException();
        }
        Recipient collector = recipients.collectorOf(order);
        sender.requireChannel(channel);
        SupportMessage claimed = sender.claim(agent, collector, SupportMessageKind.COLLECT_CODE, channel, null);
        String grouped;
        try {
            grouped = fulfilmentService.mintCollectCodeForSupport(order.getId(), parcel.getId());
        } catch (RuntimeException ex) {
            // The parcel moved between the check and the mint: close the
            // claimed row honestly and answer with the mint's own refusal.
            sender.abandon(agent, claimed, "not_minted");
            throw ex;
        }
        return sender.dispatch(agent, claimed, channel,
                composer.template(OrderNotificationComposer.collectCodeMessage(order.getOrderRef(), grouped)));
    }

    private OrderFulfilment parcelOn(MarketOrder order, UUID fulfilmentId) {
        return fulfilmentRepository.findById(fulfilmentId)
                .filter(p -> p.getOrderId().equals(order.getId()))
                .orElseThrow(() -> ApiException.notFound("fulfilment_not_found", "Fulfilment not found"));
    }

    static MessageChannel channelOf(MessageChannel requested) {
        return requested == null ? MessageChannel.SMS_THEN_WHATSAPP : requested;
    }
}
