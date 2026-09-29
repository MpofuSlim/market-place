package com.innbucks.marketplaceservice.seller;

import com.innbucks.marketplaceservice.api.ApiException;
import com.innbucks.marketplaceservice.api.ApiResult;
import com.innbucks.marketplaceservice.security.AuthenticatedUser;
import com.innbucks.marketplaceservice.security.CurrentUser;
import com.innbucks.marketplaceservice.seller.dto.CollectionSettingRequest;
import com.innbucks.marketplaceservice.seller.dto.CollectionSettingResponse;
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
 * A seller's own collection setting (V20): whether buyers may collect from
 * them, or they only deliver.
 *
 * <p><b>Scoped by SHAPE</b>, like the payout destination and collection
 * points: no path or query parameter names a merchant, so the subject is always
 * the caller's own organization. An operator acting for a seller uses
 * {@code /marketplace/admin/sellers/{merchantId}/collection}.
 *
 * <p>Rides the plain {@code /marketplace/**} gateway route — an ordinary
 * authenticated surface, not an internal one.
 */
@Tag(name = "Seller collection setting",
        description = "Whether buyers may collect your goods, or you only deliver. Every seller "
                + "collects until they turn it off. Turning it off makes you DELIVERY-ONLY: "
                + "every item you sell must then be delivered, so each item on sale needs at "
                + "least one delivery town, and checkout stops offering collection for your items. "
                + "Your collection points are kept (just hidden from buyers), so turning "
                + "collection back on restores them. Orders already placed for collection are "
                + "not affected.")
@RestController
@RequestMapping("/marketplace/sellers/me/collection")
@RequiredArgsConstructor
@PreAuthorize("hasRole('MERCHANT_ADMIN')")
public class SellerCollectionController {

    static final String EXAMPLE_OFF_200 = """
            {
              "code": "OK",
              "message": "Collection setting saved",
              "data": {
                "collectionEnabled": false,
                "updatedAt": "2026-09-29T08:10:00Z"
              }
            }""";

    static final String EXAMPLE_ON_200 = """
            {
              "code": "OK",
              "message": "Collection setting saved",
              "data": {
                "collectionEnabled": true,
                "updatedAt": "2026-09-30T07:45:00Z"
              }
            }""";

    static final String EXAMPLE_READ_OFF_200 = """
            {
              "code": "OK",
              "message": "Collection setting",
              "data": {
                "collectionEnabled": false,
                "updatedAt": "2026-09-29T08:10:00Z"
              }
            }""";

    static final String EXAMPLE_NEVER_CHANGED_200 = """
            {
              "code": "OK",
              "message": "Collection setting",
              "data": {
                "collectionEnabled": true,
                "updatedAt": null
              }
            }""";

    static final String EXAMPLE_VALIDATION_400 = """
            {
              "code": "VALIDATION_ERROR",
              "message": "Request validation failed",
              "data": {
                "collectionEnabled": "must not be null"
              }
            }""";

    static final String EXAMPLE_COLLECTION_REQUIRED_409 = """
            {
              "code": "collection_required",
              "message": "Some of your items on sale can only be collected - add delivery towns to them or take them off sale first",
              "data": {
                "listings": [
                  { "id": "9c2e8a4d-6b1f-4e3a-8d5c-2f7b9a1e4c63", "title": "Wireless Earbuds" },
                  { "id": "b4c2f0a8-3d1e-4e5a-9c7b-2f8d6a1e4b93", "title": "Wireless Bluetooth Speaker" }
                ],
                "truncated": false
              }
            }""";

    static final String EXAMPLE_DISABLED_422 = """
            {
              "code": "delivery_only_disabled",
              "message": "Turning collection off is not available yet"
            }""";

    static final String EXAMPLE_NOT_OFFERED_422 = """
            {
              "code": "delivery_not_offered",
              "message": "Delivery is not offered in this market, so collection cannot be turned off"
            }""";

    static final String EXAMPLE_REQUEST_OFF = """
            { "collectionEnabled": false }""";

    static final String EXAMPLE_REQUEST_ON = """
            { "collectionEnabled": true }""";

    private static final String EXAMPLE_ROLE_403 = """
            {
              "code": "FORBIDDEN",
              "message": "Forbidden - insufficient role",
              "data": null
            }""";

    private static final String EXAMPLE_401 = """
            {
              "code": "UNAUTHORIZED",
              "message": "Invalid or missing token",
              "data": null
            }""";

    private final SellerService sellerService;

    @GetMapping
    @Operation(summary = "Your collection setting",
            description = "`collectionEnabled: true` means buyers may collect from you — every "
                    + "seller's starting point, so a seller who has never changed it reads `true` "
                    + "with `updatedAt: null`. `false` means you only deliver.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Your setting",
                    content = @Content(mediaType = "application/json", examples = {
                            @ExampleObject(name = "delivery-only", value = EXAMPLE_READ_OFF_200),
                            @ExampleObject(name = "never changed", value = EXAMPLE_NEVER_CHANGED_200)})),
            @ApiResponse(responseCode = "401", description = "Missing/invalid token",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(name = "unauthorized", value = EXAMPLE_401))),
            @ApiResponse(responseCode = "403", description = "Not an OWNER/ADMIN of a selling "
                    + "business",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(name = "insufficient-role", value = EXAMPLE_ROLE_403)))
    })
    public ResponseEntity<ApiResult<CollectionSettingResponse>> get() {
        return ResponseEntity.ok(ApiResult.ok("Collection setting",
                sellerService.collectionSetting(requireMerchantId(CurrentUser.get()))));
    }

    @PutMapping
    @Operation(summary = "Turn collection on or off",
            description = "`{\"collectionEnabled\": false}` makes you DELIVERY-ONLY. It is refused "
                    + "until every item you have ON SALE delivers to at least one town — the 409 "
                    + "names up to 20 of the items in the way (`truncated: true` when there are "
                    + "more), so you can add towns to them or take them off sale. Nothing is "
                    + "changed for you: your items stay exactly as they are.\n\n"
                    + "`{\"collectionEnabled\": true}` turns collection back on, and is never "
                    + "refused.\n\n"
                    + "Sending the value you already have changes nothing and answers 200.\n\n"
                    + "While you are delivery-only, an item cannot be put on sale without a "
                    + "delivery town, and an item on sale cannot have all its towns removed "
                    + "(422 `delivery_towns_required` on those listing calls).")
    @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(
            mediaType = "application/json", examples = {
                    @ExampleObject(name = "delivery only", value = EXAMPLE_REQUEST_OFF),
                    @ExampleObject(name = "collection back on", value = EXAMPLE_REQUEST_ON)}))
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Saved (or already so)",
                    content = @Content(mediaType = "application/json", examples = {
                            @ExampleObject(name = "now delivery-only", value = EXAMPLE_OFF_200),
                            @ExampleObject(name = "collection back on", value = EXAMPLE_ON_200)})),
            @ApiResponse(responseCode = "400", description = "`collectionEnabled` missing",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(name = "missing-field",
                                    value = EXAMPLE_VALIDATION_400))),
            @ApiResponse(responseCode = "401", description = "Missing/invalid token",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(name = "unauthorized", value = EXAMPLE_401))),
            @ApiResponse(responseCode = "403", description = "Not an OWNER/ADMIN of a selling "
                    + "business",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(name = "insufficient-role", value = EXAMPLE_ROLE_403))),
            @ApiResponse(responseCode = "409", description = "Items on sale that could only be "
                    + "collected",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(name = "collection-required",
                                    value = EXAMPLE_COLLECTION_REQUIRED_409))),
            @ApiResponse(responseCode = "422", description = "Delivery-only sellers are not "
                    + "switched on in this market yet, or this market offers no delivery",
                    content = @Content(mediaType = "application/json", examples = {
                            @ExampleObject(name = "not-available-yet", value = EXAMPLE_DISABLED_422),
                            @ExampleObject(name = "no-delivery-here", value = EXAMPLE_NOT_OFFERED_422)}))
    })
    public ResponseEntity<ApiResult<CollectionSettingResponse>> set(
            @Valid @RequestBody CollectionSettingRequest request) {
        AuthenticatedUser caller = CurrentUser.get();
        return ResponseEntity.ok(ApiResult.ok("Collection setting saved",
                sellerService.setCollectionEnabled(caller, requireMerchantId(caller),
                        request.collectionEnabled(), true)));
    }

    /** Merchant scope comes from the JWT, never from a request — the payout
     *  destination's rule. Unreachable through the real filter (no scope means
     *  no MERCHANT_ADMIN), kept as defence in depth. */
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
