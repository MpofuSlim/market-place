package com.innbucks.marketplaceservice.seller;

import com.innbucks.marketplaceservice.api.ApiException;
import com.innbucks.marketplaceservice.api.ApiResult;
import com.innbucks.marketplaceservice.security.AuthenticatedUser;
import com.innbucks.marketplaceservice.security.CurrentUser;
import com.innbucks.marketplaceservice.seller.dto.PayoutDestinationRequest;
import com.innbucks.marketplaceservice.seller.dto.PayoutDestinationResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * A seller's own payout destination — where the money this platform releases
 * to them is actually sent.
 *
 * <p><b>Scoped by SHAPE, not by a check.</b> There is no path or query
 * parameter naming a merchant: the subject is always the caller's own
 * {@code merchantId} claim, so there is nothing to point at another seller's
 * bank details. A SUPER_ADMIN acting on a seller's behalf uses the separate
 * {@code /marketplace/admin/sellers/{merchantId}/payout-destination}, which is
 * where naming a merchant belongs.
 *
 * <p>Rides the plain {@code /marketplace/**} gateway route — this is a normal
 * authenticated surface, not an internal one, so no deny route is involved.
 */
@Tag(name = "Seller payout", description = "Where a seller's released money is sent. "
        + "Set by the seller themselves; read back in full, because 'is this the right "
        + "account?' is the one question the screen exists to answer.")
@RestController
@RequestMapping("/marketplace/sellers/me/payout-destination")
@RequiredArgsConstructor
@PreAuthorize("hasRole('MERCHANT_ADMIN')")
public class SellerPayoutController {

    private static final String EXAMPLE_200 = """
            {
              "code": "OK",
              "message": "Payout destination",
              "data": {
                "merchantId": "7e2a9c41-5b8f-4d36-a1c9-8f3b6d2e7a54",
                "configured": true,
                "method": "MOBILE_MONEY",
                "accountName": "Rudo Chikwanha",
                "msisdn": "+263771234567",
                "updatedAt": "2026-09-18T09:15:00Z",
                "updatedBy": "3f1c9d24-a77e-4e21-9c60-11ab22cd33ef"
              }
            }""";

    private static final String EXAMPLE_NONE_200 = """
            {
              "code": "OK",
              "message": "Payout destination",
              "data": {
                "merchantId": "7e2a9c41-5b8f-4d36-a1c9-8f3b6d2e7a54",
                "configured": false
              }
            }""";

    private final SellerService sellerService;

    @GetMapping
    @Operation(summary = "Your payout destination",
            description = "Returns your own destination in full — nothing is masked, because "
                    + "you typed it and checking it is the point.\n\n"
                    + "`configured: false` with no other fields means none is on file. That is a "
                    + "normal 200, not a 404: you exist, you simply have nowhere to be paid yet. "
                    + "You can still sell and still accrue released money — it just cannot be "
                    + "sent anywhere.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Your destination, or none on file",
                    content = @Content(examples = {
                            @ExampleObject(name = "configured", value = EXAMPLE_200),
                            @ExampleObject(name = "none on file", value = EXAMPLE_NONE_200)})),
            @ApiResponse(responseCode = "403", description = "Not a merchant, or a token with "
                    + "no merchant scope",
                    content = @Content(examples = @ExampleObject(value = """
                            {"code":"merchant_scope_missing","message":"Caller token carries no merchant scope"}""")))
    })
    public ResponseEntity<ApiResult<PayoutDestinationResponse>> get() {
        return ResponseEntity.ok(ApiResult.ok("Payout destination",
                sellerService.payoutDestination(requireMerchantId(CurrentUser.get()))));
    }

    @PutMapping
    @Operation(summary = "Set where you want to be paid",
            description = "REPLACES your destination entirely — send a complete one for the "
                    + "method you choose. There is no partial update: a half-changed "
                    + "destination is exactly the shape that reads as configured everywhere and "
                    + "fails when a transfer is attempted.\n\n"
                    + "`MOBILE_MONEY` needs `msisdn`; `BANK` needs `bankName` + `accountNumber`. "
                    + "`accountName` is required either way and is the name the ACCOUNT is held "
                    + "in — not your trading name. A transfer is rejected when those do not "
                    + "match.\n\n"
                    + "Changing this notifies your account, because re-pointing a payout is what "
                    + "a compromised seller account is used for.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Saved",
                    content = @Content(examples = @ExampleObject(value = EXAMPLE_200))),
            @ApiResponse(responseCode = "400", description = "A field the chosen method needs is "
                    + "missing, or the msisdn is not a valid number",
                    content = @Content(examples = {
                            @ExampleObject(name = "missing field", value = """
                                    {"code":"payout_field_required","message":"bankName is required for this payout method"}"""),
                            @ExampleObject(name = "bad number", value = """
                                    {"code":"invalid_msisdn","message":"msisdn is not a valid phone number"}""")})),
            @ApiResponse(responseCode = "403", description = "Not a merchant, or a token with "
                    + "no merchant scope",
                    content = @Content(examples = @ExampleObject(value = """
                            {"code":"merchant_scope_missing","message":"Caller token carries no merchant scope"}""")))
    })
    public ResponseEntity<ApiResult<PayoutDestinationResponse>> set(
            @Valid @RequestBody PayoutDestinationRequest request) {
        AuthenticatedUser caller = CurrentUser.get();
        return ResponseEntity.ok(ApiResult.ok("Payout destination saved",
                sellerService.setPayoutDestination(
                        caller, requireMerchantId(caller), request, true)));
    }

    /** Merchant scope comes from the JWT, never from a request — the same rule
     *  the fulfilment queue and settlement views apply. */
    private static UUID requireMerchantId(AuthenticatedUser caller) {
        String claim = caller == null ? null : caller.merchantId();
        if (claim == null || claim.isBlank()) {
            throw ApiException.forbidden("merchant_scope_missing",
                    "Caller token carries no merchant scope");
        }
        try {
            return UUID.fromString(claim.trim());
        } catch (IllegalArgumentException ex) {
            throw ApiException.forbidden("merchant_scope_missing",
                    "Caller token carries no merchant scope");
        }
    }
}
