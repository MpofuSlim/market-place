package com.innbucks.marketplaceservice.customersupport;

import com.innbucks.marketplaceservice.api.ApiResult;
import com.innbucks.marketplaceservice.customersupport.dto.SupportMessagePageResponse;
import com.innbucks.marketplaceservice.customersupport.dto.SupportMessagePreviewResponse;
import com.innbucks.marketplaceservice.customersupport.dto.SupportMessageRequest;
import com.innbucks.marketplaceservice.customersupport.dto.SupportMessageResponse;
import com.innbucks.marketplaceservice.customersupport.dto.SupportResendRequest;
import com.innbucks.marketplaceservice.customersupport.messaging.MessageChannel;
import com.innbucks.marketplaceservice.customersupport.messaging.SupportMessageHistory;
import com.innbucks.marketplaceservice.customersupport.messaging.SupportMessageService;
import com.innbucks.marketplaceservice.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.UUID;

/**
 * Messages from the call center to a customer ON RECORD (V23): typed by the
 * agent, or a platform message sent again. There is no destination number in
 * any request — the buyer or order the agent opened supplies it.
 */
@Tag(name = "Customer support - messages",
        description = "Send a customer an SMS or WhatsApp you type (`customer-messages:send`), or resend the "
                + "order confirmation, the last parcel update, or a fresh collection code "
                + "(`marketplace-support:manage`). Always to a number already on the buyer's or the order's "
                + "record. Limits: 60 per agent per hour, 5 per customer number per 24 hours, every kind counted.")
@RestController
@RequestMapping("/marketplace/support")
@RequiredArgsConstructor
public class SupportMessageController {

    static final String MESSAGE_ID = "8c2e4f61-9a3b-4d7e-b5c1-2f6a8d0e3b94";

    static final String EXAMPLE_SEND_REQUEST = """
            {
              "subjectKind": "ORDER",
              "subjectId": "3f9c2a71-5b4e-4d8a-9c6f-1e2d3b4a5c6d",
              "recipient": "BUYER",
              "channel": "SMS_THEN_WHATSAPP",
              "text": "Hello, the seller has confirmed your parcel leaves Harare tomorrow morning."
            }""";

    static final String EXAMPLE_PREVIEW_200 = """
            {
              "code": "OK",
              "message": "Preview",
              "data": {
                "channel": "SMS_THEN_WHATSAPP",
                "recipientRole": "BUYER",
                "recipient": "****3456",
                "text": "Hello, the seller has confirmed your parcel leaves Harare tomorrow morning.\\n- InnBucks Marketplace Support",
                "characters": 108,
                "maxCharacters": 459,
                "smsSegments": 1,
                "transliterated": false,
                "whatsappText": "Hello, the seller has confirmed your parcel leaves Harare tomorrow morning.\\n- InnBucks Marketplace Support"
              }
            }""";

    static final String EXAMPLE_SENT_201 = """
            {
              "code": "CREATED",
              "message": "Message sent",
              "data": {
                "id": "8c2e4f61-9a3b-4d7e-b5c1-2f6a8d0e3b94",
                "kind": "CUSTOM",
                "subjectKind": "ORDER",
                "subjectId": "3f9c2a71-5b4e-4d8a-9c6f-1e2d3b4a5c6d",
                "channelRequested": "SMS_THEN_WHATSAPP",
                "deliveredVia": "SMS",
                "outcome": "SENT",
                "recipientRole": "BUYER",
                "recipient": "****3456",
                "text": "Hello, the seller has confirmed your parcel leaves Harare tomorrow morning.\\n- InnBucks Marketplace Support",
                "sentBy": { "uuid": "9d3f6a2e-1c4b-4e8f-a7d5-3b2c1e0f9a86", "login": "tariro.moyo@innbucks.co.zw" },
                "createdAt": "2026-09-30T08:41:12Z",
                "completedAt": "2026-09-30T08:41:13Z",
                "failureCode": null
              }
            }""";

    static final String EXAMPLE_CODE_SENT_201 = """
            {
              "code": "CREATED",
              "message": "Collection code sent",
              "data": {
                "id": "2b7d9e13-6c4a-4f8b-a1d5-9e3c7b2f0a46",
                "kind": "COLLECT_CODE",
                "subjectKind": "ORDER",
                "subjectId": "3f9c2a71-5b4e-4d8a-9c6f-1e2d3b4a5c6d",
                "channelRequested": "SMS_THEN_WHATSAPP",
                "deliveredVia": "SMS",
                "outcome": "SENT",
                "recipientRole": "GIFT_RECIPIENT",
                "recipient": "****7788",
                "text": null,
                "sentBy": { "uuid": "9d3f6a2e-1c4b-4e8f-a7d5-3b2c1e0f9a86", "login": "tariro.moyo@innbucks.co.zw" },
                "createdAt": "2026-09-30T09:02:40Z",
                "completedAt": "2026-09-30T09:02:41Z",
                "failureCode": null
              }
            }""";

    static final String EXAMPLE_NOT_DELIVERED_502 = """
            {
              "code": "message_not_delivered",
              "message": "The message could not be delivered - neither channel accepted it",
              "data": {
                "id": "8c2e4f61-9a3b-4d7e-b5c1-2f6a8d0e3b94",
                "kind": "CUSTOM",
                "subjectKind": "ORDER",
                "subjectId": "3f9c2a71-5b4e-4d8a-9c6f-1e2d3b4a5c6d",
                "channelRequested": "SMS_THEN_WHATSAPP",
                "deliveredVia": null,
                "outcome": "FAILED",
                "recipientRole": "BUYER",
                "recipient": "****3456",
                "text": "Hello, the seller has confirmed your parcel leaves Harare tomorrow morning.\\n- InnBucks Marketplace Support",
                "sentBy": { "uuid": "9d3f6a2e-1c4b-4e8f-a7d5-3b2c1e0f9a86", "login": "tariro.moyo@innbucks.co.zw" },
                "createdAt": "2026-09-30T08:41:12Z",
                "completedAt": "2026-09-30T08:41:14Z",
                "failureCode": "sms_and_whatsapp_failed"
              }
            }""";

    static final String EXAMPLE_HISTORY_200 = """
            {
              "code": "OK",
              "message": "Messages",
              "data": {
                "items": [
                  {
                    "id": "8c2e4f61-9a3b-4d7e-b5c1-2f6a8d0e3b94",
                    "kind": "CUSTOM",
                    "subjectKind": "ORDER",
                    "subjectId": "3f9c2a71-5b4e-4d8a-9c6f-1e2d3b4a5c6d",
                    "channelRequested": "SMS_THEN_WHATSAPP",
                    "deliveredVia": "SMS",
                    "outcome": "SENT",
                    "recipientRole": "BUYER",
                    "recipient": "****3456",
                    "text": "Hello, the seller has confirmed your parcel leaves Harare tomorrow morning.\\n- InnBucks Marketplace Support",
                    "sentBy": { "uuid": "9d3f6a2e-1c4b-4e8f-a7d5-3b2c1e0f9a86", "login": "tariro.moyo@innbucks.co.zw" },
                    "createdAt": "2026-09-30T08:41:12Z",
                    "completedAt": "2026-09-30T08:41:13Z",
                    "failureCode": null
                  }
                ],
                "page": 0, "size": 20, "totalItems": 1, "totalPages": 1
              }
            }""";

    static final String EXAMPLE_LINK_400 = """
            {
              "code": "link_not_allowed",
              "message": "Links in a support message may only point at innbucks.co.zw - 'bit.ly' is not one of them. If it is not meant as a link, add a space after the full stop.",
              "data": { "host": "bit.ly" }
            }""";

    static final String EXAMPLE_TOO_LONG_400 = """
            {
              "code": "message_too_long",
              "message": "An SMS from support is at most 459 characters including the signature - this one is 512"
            }""";

    static final String EXAMPLE_NOT_ON_RECORD_400 = """
            {
              "code": "recipient_not_on_record",
              "message": "That is not a number this buyer has paid from - pick one from their record"
            }""";

    static final String EXAMPLE_NO_PHONE_422 = """
            {
              "code": "no_phone_on_record",
              "message": "This order names no gift recipient's number"
            }""";

    static final String EXAMPLE_RATE_LIMITED_429 = """
            {
              "code": "support_message_rate_limited",
              "message": "This customer has already had 5 messages from support in the last 24 hours",
              "data": { "scope": "RECIPIENT", "limit": 5, "windowMinutes": 1440 }
            }""";

    static final String EXAMPLE_CHANNEL_503 = """
            {
              "code": "channel_unavailable",
              "message": "WhatsApp is not set up on this deployment - try SMS"
            }""";

    static final String EXAMPLE_NOT_PAID_409 = """
            {
              "code": "order_not_paid",
              "message": "Only a paid order has a confirmation to resend - this one is PENDING_PAYMENT"
            }""";

    static final String EXAMPLE_NOTHING_TO_RESEND_409 = """
            {
              "code": "nothing_to_resend",
              "message": "There is no seller update to resend for a parcel that is PREPARING - write the buyer a message instead"
            }""";

    static final String EXAMPLE_CODE_REFUSED_409 = """
            {
              "code": "collect_code_not_applicable",
              "message": "This parcel is being delivered - there is nothing to collect in person"
            }""";

    static final String EXAMPLE_ORDER_404 = """
            {
              "code": "order_not_found",
              "message": "Order not found"
            }""";

    static final String EXAMPLE_401 = """
            {
              "code": "UNAUTHORIZED",
              "message": "Invalid or missing token",
              "data": null
            }""";

    static final String EXAMPLE_403 = """
            {
              "code": "FORBIDDEN",
              "message": "Forbidden - insufficient role",
              "data": null
            }""";

    private final SupportMessageService messageService;
    private final SupportMessageHistory history;

    @PostMapping("/messages/preview")
    @PreAuthorize(SupportPermissions.CAN_MESSAGE)
    @Operation(summary = "Preview a typed message exactly as the customer will receive it",
            description = "Sends nothing, records nothing and uses none of the limits. Refuses what sending "
                    + "would refuse EXCEPT length: `characters` against `maxCharacters` is the live count to show "
                    + "while typing (sending refuses an over-long message with `message_too_long`). `text` is the "
                    + "first channel's text: on SMS it has been made "
                    + "SMS-safe (the gateway rejects `: / ? ! \" * ;`, dashes and emoji), and `transliterated` "
                    + "says when that changed anything - read it before sending.")
    @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(mediaType = "application/json",
            examples = @ExampleObject(name = "to the payer of an order", value = EXAMPLE_SEND_REQUEST)))
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "What would be sent", content = @Content(
                    mediaType = "application/json", examples = @ExampleObject(name = "preview", value = EXAMPLE_PREVIEW_200))),
            @ApiResponse(responseCode = "400", description = "Empty, a link elsewhere, or a number not the buyer's",
                    content = @Content(mediaType = "application/json", examples = {
                            @ExampleObject(name = "link", value = EXAMPLE_LINK_400),
                            @ExampleObject(name = "not the buyer's number", value = EXAMPLE_NOT_ON_RECORD_400)})),
            @ApiResponse(responseCode = "401", description = "Missing/invalid token", content = @Content(
                    mediaType = "application/json", examples = @ExampleObject(name = "unauthorized", value = EXAMPLE_401))),
            @ApiResponse(responseCode = "403", description = "No `customer-messages:send`", content = @Content(
                    mediaType = "application/json", examples = @ExampleObject(name = "forbidden", value = EXAMPLE_403))),
            @ApiResponse(responseCode = "404", description = "No such buyer or order", content = @Content(
                    mediaType = "application/json", examples = @ExampleObject(name = "unknown order", value = EXAMPLE_ORDER_404))),
            @ApiResponse(responseCode = "422", description = "The record has no number for that recipient", content = @Content(
                    mediaType = "application/json", examples = @ExampleObject(name = "no number", value = EXAMPLE_NO_PHONE_422))),
            @ApiResponse(responseCode = "503", description = "The channel is not set up on this deployment", content = @Content(
                    mediaType = "application/json", examples = @ExampleObject(name = "no channel", value = EXAMPLE_CHANNEL_503)))
    })
    public ResponseEntity<ApiResult<SupportMessagePreviewResponse>> preview(
            @Valid @RequestBody SupportMessageRequest request) {
        return ResponseEntity.ok(ApiResult.ok("Preview", messageService.preview(request)));
    }

    @PostMapping("/messages")
    @PreAuthorize(SupportPermissions.CAN_MESSAGE)
    @Operation(summary = "Send a customer a message you typed",
            description = "To a number ON RECORD only: on an ORDER choose `recipient` (BUYER = the payer, "
                    + "GIFT_RECIPIENT, DELIVERY_RECIPIENT); on a BUYER it is the number they last paid from, or "
                    + "another of THEIR numbers named in `phone`. The support signature is added on its own line. "
                    + "Sent synchronously: `201` when a channel took it, `502 message_not_delivered` (with the "
                    + "FAILED record) when none did. Every attempt is recorded, counts against the limits, and "
                    + "is audited without its text or number.")
    @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(mediaType = "application/json",
            examples = @ExampleObject(name = "to the payer of an order", value = EXAMPLE_SEND_REQUEST)))
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Sent", content = @Content(
                    mediaType = "application/json", examples = @ExampleObject(name = "sent", value = EXAMPLE_SENT_201))),
            @ApiResponse(responseCode = "400", description = "Empty, too long, a link elsewhere, or a number not the buyer's",
                    content = @Content(mediaType = "application/json", examples = {
                            @ExampleObject(name = "link", value = EXAMPLE_LINK_400),
                            @ExampleObject(name = "too long", value = EXAMPLE_TOO_LONG_400),
                            @ExampleObject(name = "not the buyer's number", value = EXAMPLE_NOT_ON_RECORD_400)})),
            @ApiResponse(responseCode = "401", description = "Missing/invalid token", content = @Content(
                    mediaType = "application/json", examples = @ExampleObject(name = "unauthorized", value = EXAMPLE_401))),
            @ApiResponse(responseCode = "403", description = "No `customer-messages:send`", content = @Content(
                    mediaType = "application/json", examples = @ExampleObject(name = "forbidden", value = EXAMPLE_403))),
            @ApiResponse(responseCode = "404", description = "No such buyer or order", content = @Content(
                    mediaType = "application/json", examples = @ExampleObject(name = "unknown order", value = EXAMPLE_ORDER_404))),
            @ApiResponse(responseCode = "422", description = "The record has no number for that recipient", content = @Content(
                    mediaType = "application/json", examples = @ExampleObject(name = "no number", value = EXAMPLE_NO_PHONE_422))),
            @ApiResponse(responseCode = "429", description = "Over the agent's or the customer's limit", content = @Content(
                    mediaType = "application/json", examples = @ExampleObject(name = "limit", value = EXAMPLE_RATE_LIMITED_429))),
            @ApiResponse(responseCode = "502", description = "No channel accepted it", content = @Content(
                    mediaType = "application/json", examples = @ExampleObject(name = "not delivered", value = EXAMPLE_NOT_DELIVERED_502))),
            @ApiResponse(responseCode = "503", description = "The channel is not set up on this deployment", content = @Content(
                    mediaType = "application/json", examples = @ExampleObject(name = "no channel", value = EXAMPLE_CHANNEL_503)))
    })
    public ResponseEntity<ApiResult<SupportMessageResponse>> send(@Valid @RequestBody SupportMessageRequest request) {
        return created("Message sent", messageService.sendCustom(agent(), request));
    }

    @PostMapping("/orders/{orderId}/messages/order-confirmation")
    @PreAuthorize(SupportPermissions.CAN_MANAGE)
    @Operation(summary = "Resend the order confirmation to the payer",
            description = "The platform's own order-paid message, word for word, for a PAID order. Same "
                    + "limits and record as a typed message. The body is optional.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Sent", content = @Content(
                    mediaType = "application/json", examples = @ExampleObject(name = "sent", value = EXAMPLE_SENT_201))),
            @ApiResponse(responseCode = "401", description = "Missing/invalid token", content = @Content(
                    mediaType = "application/json", examples = @ExampleObject(name = "unauthorized", value = EXAMPLE_401))),
            @ApiResponse(responseCode = "403", description = "No `marketplace-support:manage`", content = @Content(
                    mediaType = "application/json", examples = @ExampleObject(name = "forbidden", value = EXAMPLE_403))),
            @ApiResponse(responseCode = "404", description = "No such order", content = @Content(
                    mediaType = "application/json", examples = @ExampleObject(name = "unknown order", value = EXAMPLE_ORDER_404))),
            @ApiResponse(responseCode = "409", description = "The order is not paid", content = @Content(
                    mediaType = "application/json", examples = @ExampleObject(name = "not paid", value = EXAMPLE_NOT_PAID_409))),
            @ApiResponse(responseCode = "429", description = "Over a limit", content = @Content(
                    mediaType = "application/json", examples = @ExampleObject(name = "limit", value = EXAMPLE_RATE_LIMITED_429))),
            @ApiResponse(responseCode = "502", description = "No channel accepted it", content = @Content(
                    mediaType = "application/json", examples = @ExampleObject(name = "not delivered", value = EXAMPLE_NOT_DELIVERED_502))),
            @ApiResponse(responseCode = "503", description = "The channel is not set up", content = @Content(
                    mediaType = "application/json", examples = @ExampleObject(name = "no channel", value = EXAMPLE_CHANNEL_503)))
    })
    public ResponseEntity<ApiResult<SupportMessageResponse>> resendConfirmation(
            @PathVariable UUID orderId, @RequestBody(required = false) SupportResendRequest request) {
        return created("Order confirmation sent",
                messageService.resendConfirmation(agent(), orderId, channelOf(request)));
    }

    @PostMapping("/orders/{orderId}/fulfilments/{fulfilmentId}/messages/parcel-update")
    @PreAuthorize(SupportPermissions.CAN_MANAGE)
    @Operation(summary = "Resend the seller's last update on a parcel to the payer",
            description = "Word for word: \"on its way\" / \"ready to collect\" for a DISPATCHED parcel, or "
                    + "\"marked delivered\" (with the dispute window) for one the SELLER closed. When it goes out, "
                    + "the seller's card records that the buyer was reached. Anything else is 409 "
                    + "`nothing_to_resend` - write the buyer a message instead.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Sent", content = @Content(
                    mediaType = "application/json", examples = @ExampleObject(name = "sent", value = EXAMPLE_SENT_201))),
            @ApiResponse(responseCode = "401", description = "Missing/invalid token", content = @Content(
                    mediaType = "application/json", examples = @ExampleObject(name = "unauthorized", value = EXAMPLE_401))),
            @ApiResponse(responseCode = "403", description = "No `marketplace-support:manage`", content = @Content(
                    mediaType = "application/json", examples = @ExampleObject(name = "forbidden", value = EXAMPLE_403))),
            @ApiResponse(responseCode = "404", description = "No such order, or the parcel is not on it", content = @Content(
                    mediaType = "application/json", examples = @ExampleObject(name = "unknown order", value = EXAMPLE_ORDER_404))),
            @ApiResponse(responseCode = "409", description = "No seller update to resend", content = @Content(
                    mediaType = "application/json", examples = @ExampleObject(name = "nothing", value = EXAMPLE_NOTHING_TO_RESEND_409))),
            @ApiResponse(responseCode = "429", description = "Over a limit", content = @Content(
                    mediaType = "application/json", examples = @ExampleObject(name = "limit", value = EXAMPLE_RATE_LIMITED_429))),
            @ApiResponse(responseCode = "502", description = "No channel accepted it", content = @Content(
                    mediaType = "application/json", examples = @ExampleObject(name = "not delivered", value = EXAMPLE_NOT_DELIVERED_502))),
            @ApiResponse(responseCode = "503", description = "The channel is not set up", content = @Content(
                    mediaType = "application/json", examples = @ExampleObject(name = "no channel", value = EXAMPLE_CHANNEL_503)))
    })
    public ResponseEntity<ApiResult<SupportMessageResponse>> resendParcelUpdate(
            @PathVariable UUID orderId, @PathVariable UUID fulfilmentId,
            @RequestBody(required = false) SupportResendRequest request) {
        return created("Parcel update sent",
                messageService.resendParcelUpdate(agent(), orderId, fulfilmentId, channelOf(request)));
    }

    @PostMapping("/orders/{orderId}/fulfilments/{fulfilmentId}/collect-code")
    @PreAuthorize(SupportPermissions.CAN_MANAGE)
    @Operation(summary = "Send the collector a FRESH collection code",
            description = "For a buyer who lost theirs. Mints a new code - the old one stops working, as when "
                    + "the buyer mints one in the app - and texts it to whoever collects: the gift recipient "
                    + "when the order names one with a number, else the payer. **The code is never shown to you** "
                    + "(`text` is null here and in the history): whoever holds a code can have the goods handed "
                    + "over. The buyer's own rule applies (a collection parcel that is still open). The limits "
                    + "are checked BEFORE the code is replaced.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Sent", content = @Content(
                    mediaType = "application/json", examples = @ExampleObject(name = "sent", value = EXAMPLE_CODE_SENT_201))),
            @ApiResponse(responseCode = "401", description = "Missing/invalid token", content = @Content(
                    mediaType = "application/json", examples = @ExampleObject(name = "unauthorized", value = EXAMPLE_401))),
            @ApiResponse(responseCode = "403", description = "No `marketplace-support:manage`", content = @Content(
                    mediaType = "application/json", examples = @ExampleObject(name = "forbidden", value = EXAMPLE_403))),
            @ApiResponse(responseCode = "404", description = "No such order, or the parcel is not on it", content = @Content(
                    mediaType = "application/json", examples = @ExampleObject(name = "unknown order", value = EXAMPLE_ORDER_404))),
            @ApiResponse(responseCode = "409", description = "Not a collection, or already closed", content = @Content(
                    mediaType = "application/json", examples = @ExampleObject(name = "refused", value = EXAMPLE_CODE_REFUSED_409))),
            @ApiResponse(responseCode = "429", description = "Over a limit", content = @Content(
                    mediaType = "application/json", examples = @ExampleObject(name = "limit", value = EXAMPLE_RATE_LIMITED_429))),
            @ApiResponse(responseCode = "502", description = "No channel accepted it (the new code IS live - the buyer can mint another in the app)",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(name = "not delivered", value = EXAMPLE_NOT_DELIVERED_502))),
            @ApiResponse(responseCode = "503", description = "The channel is not set up", content = @Content(
                    mediaType = "application/json", examples = @ExampleObject(name = "no channel", value = EXAMPLE_CHANNEL_503)))
    })
    public ResponseEntity<ApiResult<SupportMessageResponse>> sendCollectCode(
            @PathVariable UUID orderId, @PathVariable UUID fulfilmentId,
            @RequestBody(required = false) SupportResendRequest request) {
        return created("Collection code sent",
                messageService.sendCollectCode(agent(), orderId, fulfilmentId, channelOf(request)));
    }

    @GetMapping("/messages")
    @PreAuthorize(SupportPermissions.CAN_READ)
    @Operation(summary = "What support has sent a buyer or about an order, newest first",
            description = "A BUYER's history includes every message about any of their orders. The number is "
                    + "masked; a collection-code message has no text. Page size at most 100.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "One page", content = @Content(
                    mediaType = "application/json", examples = @ExampleObject(name = "history", value = EXAMPLE_HISTORY_200))),
            @ApiResponse(responseCode = "401", description = "Missing/invalid token", content = @Content(
                    mediaType = "application/json", examples = @ExampleObject(name = "unauthorized", value = EXAMPLE_401))),
            @ApiResponse(responseCode = "403", description = "No `marketplace-support:read`", content = @Content(
                    mediaType = "application/json", examples = @ExampleObject(name = "forbidden", value = EXAMPLE_403)))
    })
    public ResponseEntity<ApiResult<SupportMessagePageResponse>> messages(@RequestParam SubjectKind subjectKind,
                                                                          @RequestParam UUID subjectId,
                                                                          @RequestParam(defaultValue = "0") int page,
                                                                          @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(ApiResult.ok("Messages", history.forSubject(subjectKind, subjectId, page, size)));
    }

    @GetMapping("/messages/feed")
    @PreAuthorize(SupportPermissions.CAN_SUPERVISE)
    @Operation(summary = "Every agent's messages (supervisors)",
            description = "Newest first. Filters: `agentUuid`, `kind` (CUSTOM, ORDER_CONFIRMATION, PARCEL_UPDATE, "
                    + "COLLECT_CODE), `outcome` (PENDING, SENT, FAILED) and a `from`/`to` window (ISO-8601, `to` "
                    + "exclusive). Page size at most 100. Needs `marketplace-support:supervise`.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "One page", content = @Content(
                    mediaType = "application/json", examples = @ExampleObject(name = "feed", value = EXAMPLE_HISTORY_200))),
            @ApiResponse(responseCode = "401", description = "Missing/invalid token", content = @Content(
                    mediaType = "application/json", examples = @ExampleObject(name = "unauthorized", value = EXAMPLE_401))),
            @ApiResponse(responseCode = "403", description = "No `marketplace-support:supervise`", content = @Content(
                    mediaType = "application/json", examples = @ExampleObject(name = "forbidden", value = EXAMPLE_403)))
    })
    public ResponseEntity<ApiResult<SupportMessagePageResponse>> feed(
            @RequestParam(required = false) String agentUuid,
            @RequestParam(required = false) String kind,
            @RequestParam(required = false) String outcome,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return ResponseEntity.ok(ApiResult.ok("Support messages", history.feed(
                new SupportMessageHistory.FeedQuery(agentUuid, kind, outcome, from, to, page, size))));
    }

    private static ResponseEntity<ApiResult<SupportMessageResponse>> created(String message,
                                                                           SupportMessageResponse body) {
        return ResponseEntity.status(HttpStatus.CREATED).body(new ApiResult<>("CREATED", message, body));
    }

    private static MessageChannel channelOf(SupportResendRequest request) {
        return request == null ? null : request.channel();
    }

    private static SupportAgent agent() {
        return SupportAgent.of(CurrentUser.get());
    }
}
