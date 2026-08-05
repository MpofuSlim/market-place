package com.innbucks.marketplaceservice.catalog;

import com.innbucks.marketplaceservice.api.ApiException;
import com.innbucks.marketplaceservice.api.ApiResult;
import com.innbucks.marketplaceservice.catalog.dto.ListingCreateRequest;
import com.innbucks.marketplaceservice.catalog.dto.ListingPageResponse;
import com.innbucks.marketplaceservice.catalog.dto.ListingResponse;
import com.innbucks.marketplaceservice.catalog.dto.ListingStatusRequest;
import com.innbucks.marketplaceservice.catalog.dto.ListingUpdateRequest;
import com.innbucks.marketplaceservice.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Seller-side listing management. Ownership and merchant scope are resolved
 * from the verified JWT via {@link CurrentUser} — never from the request.
 *
 * <p>UUID path variables and numeric query params are parsed leniently in this
 * controller (400 {@code invalid_listing_id} / fall back to defaults) because
 * {@code GlobalExceptionHandler} has no {@code MethodArgumentTypeMismatchException}
 * mapping — a typed {@code @PathVariable UUID} would 500 on garbage input.
 */
@Tag(name = "Merchant Listings",
        description = "Seller-side listing management. Requires a MERCHANT_ADMIN fleet token carrying "
                + "a merchantId claim (owner's call: marketplace administration is MERCHANT_ADMIN-only; "
                + "shop staff roles are deliberately excluded); merchant scope is never taken from the "
                + "request body.")
@RestController
@RequestMapping("/marketplace/listings")
@RequiredArgsConstructor
@PreAuthorize("hasRole('MERCHANT_ADMIN')")
public class ListingController {

    private final ListingService listingService;

    // -- Shared example bodies (cross-endpoint consistency: run the requests in
    //    order and the GETs show exactly what the writes produced) -------------

    private static final String EXAMPLE_CREATED_201 = """
            {
              "code": "CREATED",
              "message": "Created",
              "data": {
                "id": "b4c2f0a8-3d1e-4e5a-9c7b-2f8d6a1e4b93",
                "merchantId": "7e2a9c41-5b8f-4d36-a1c9-8f3b6d2e7a54",
                "title": "Wireless Bluetooth Speaker",
                "description": "Portable speaker with 12h battery life.",
                "category": "electronics",
                "priceCents": 2599,
                "currency": "USD",
                "stockQty": 120,
                "status": "DRAFT",
                "createdAt": "2026-08-05T09:15:00Z",
                "updatedAt": "2026-08-05T09:15:00Z"
              }
            }""";

    private static final String EXAMPLE_UPDATED_200 = """
            {
              "code": "OK",
              "message": "Success",
              "data": {
                "id": "b4c2f0a8-3d1e-4e5a-9c7b-2f8d6a1e4b93",
                "merchantId": "7e2a9c41-5b8f-4d36-a1c9-8f3b6d2e7a54",
                "title": "Wireless Bluetooth Speaker",
                "description": "Portable speaker with 12h battery life.",
                "category": "electronics",
                "priceCents": 2399,
                "currency": "USD",
                "stockQty": 150,
                "status": "DRAFT",
                "createdAt": "2026-08-05T09:15:00Z",
                "updatedAt": "2026-08-05T09:20:00Z"
              }
            }""";

    private static final String EXAMPLE_STATUS_200 = """
            {
              "code": "OK",
              "message": "Success",
              "data": {
                "id": "b4c2f0a8-3d1e-4e5a-9c7b-2f8d6a1e4b93",
                "merchantId": "7e2a9c41-5b8f-4d36-a1c9-8f3b6d2e7a54",
                "title": "Wireless Bluetooth Speaker",
                "description": "Portable speaker with 12h battery life.",
                "category": "electronics",
                "priceCents": 2399,
                "currency": "USD",
                "stockQty": 150,
                "status": "ACTIVE",
                "createdAt": "2026-08-05T09:15:00Z",
                "updatedAt": "2026-08-05T09:25:00Z"
              }
            }""";

    private static final String EXAMPLE_MINE_200 = """
            {
              "code": "OK",
              "message": "Success",
              "data": {
                "items": [
                  {
                    "id": "b4c2f0a8-3d1e-4e5a-9c7b-2f8d6a1e4b93",
                    "merchantId": "7e2a9c41-5b8f-4d36-a1c9-8f3b6d2e7a54",
                    "title": "Wireless Bluetooth Speaker",
                    "description": "Portable speaker with 12h battery life.",
                    "category": "electronics",
                    "priceCents": 2399,
                    "currency": "USD",
                    "stockQty": 150,
                    "status": "ACTIVE",
                    "createdAt": "2026-08-05T09:15:00Z",
                    "updatedAt": "2026-08-05T09:25:00Z"
                  }
                ],
                "page": 0,
                "size": 20,
                "totalItems": 1,
                "totalPages": 1
              }
            }""";

    private static final String EXAMPLE_VALIDATION_400 = """
            {
              "code": "VALIDATION_ERROR",
              "message": "Request validation failed",
              "data": {
                "priceCents": "must be less than or equal to 100000000"
              }
            }""";

    private static final String EXAMPLE_TITLE_400 = """
            {
              "code": "title_invalid",
              "message": "Title must not be empty after sanitization"
            }""";

    private static final String EXAMPLE_MALFORMED_400 = """
            {
              "code": "MALFORMED_REQUEST",
              "message": "Request body is malformed"
            }""";

    private static final String EXAMPLE_INVALID_ID_400 = """
            {
              "code": "invalid_listing_id",
              "message": "Listing id must be a UUID"
            }""";

    private static final String EXAMPLE_401 = """
            {
              "code": "UNAUTHORIZED",
              "message": "Invalid or missing token",
              "data": null
            }""";

    private static final String EXAMPLE_ROLE_403 = """
            {
              "code": "FORBIDDEN",
              "message": "Forbidden - insufficient role",
              "data": null
            }""";

    private static final String EXAMPLE_SCOPE_403 = """
            {
              "code": "merchant_scope_missing",
              "message": "Caller token carries no merchant scope"
            }""";

    private static final String EXAMPLE_NOT_OWNED_403 = """
            {
              "code": "listing_not_owned",
              "message": "Listing does not belong to the caller's merchant"
            }""";

    private static final String EXAMPLE_NOT_FOUND_404 = """
            {
              "code": "listing_not_found",
              "message": "Listing not found"
            }""";

    private static final String EXAMPLE_LIMIT_409 = """
            {
              "code": "listing_limit_reached",
              "message": "Merchant listing limit reached"
            }""";

    @Operation(summary = "Create a listing",
            description = "Creates a DRAFT listing owned by the caller's merchant (merchantId JWT claim). "
                    + "Currency is always the cell currency; HTML in title/description/category is "
                    + "stripped server-side. Publish it via PATCH /marketplace/listings/{id}/status.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Listing created as DRAFT",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(name = "created", value = EXAMPLE_CREATED_201))),
            @ApiResponse(responseCode = "400", description = "Validation failed",
                    content = @Content(mediaType = "application/json", examples = {
                            @ExampleObject(name = "bean-validation", value = EXAMPLE_VALIDATION_400),
                            @ExampleObject(name = "title-empty-after-sanitization", value = EXAMPLE_TITLE_400)})),
            @ApiResponse(responseCode = "401", description = "Missing/invalid token",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(name = "unauthorized", value = EXAMPLE_401))),
            @ApiResponse(responseCode = "403", description = "Wrong role or no merchant scope",
                    content = @Content(mediaType = "application/json", examples = {
                            @ExampleObject(name = "insufficient-role", value = EXAMPLE_ROLE_403),
                            @ExampleObject(name = "merchant-scope-missing", value = EXAMPLE_SCOPE_403)})),
            @ApiResponse(responseCode = "409", description = "Per-merchant listing cap reached",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(name = "limit-reached", value = EXAMPLE_LIMIT_409)))
    })
    @PostMapping
    public ResponseEntity<ApiResult<ListingResponse>> create(
            @Valid @RequestBody ListingCreateRequest request) {
        ListingResponse created = listingService.create(CurrentUser.get(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResult.created(created));
    }

    @Operation(summary = "Update a listing",
            description = "Full replace of the listing's content (title/description/category/price/stock). "
                    + "Status and currency are not updatable here. Caller must own the listing.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Listing updated",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(name = "updated", value = EXAMPLE_UPDATED_200))),
            @ApiResponse(responseCode = "400", description = "Validation failed or malformed id",
                    content = @Content(mediaType = "application/json", examples = {
                            @ExampleObject(name = "bean-validation", value = EXAMPLE_VALIDATION_400),
                            @ExampleObject(name = "title-empty-after-sanitization", value = EXAMPLE_TITLE_400),
                            @ExampleObject(name = "invalid-id", value = EXAMPLE_INVALID_ID_400)})),
            @ApiResponse(responseCode = "401", description = "Missing/invalid token",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(name = "unauthorized", value = EXAMPLE_401))),
            @ApiResponse(responseCode = "403", description = "Wrong role, no merchant scope, or not the owner",
                    content = @Content(mediaType = "application/json", examples = {
                            @ExampleObject(name = "insufficient-role", value = EXAMPLE_ROLE_403),
                            @ExampleObject(name = "merchant-scope-missing", value = EXAMPLE_SCOPE_403),
                            @ExampleObject(name = "not-owned", value = EXAMPLE_NOT_OWNED_403)})),
            @ApiResponse(responseCode = "404", description = "No listing with that id",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(name = "not-found", value = EXAMPLE_NOT_FOUND_404)))
    })
    @PutMapping("/{id}")
    public ApiResult<ListingResponse> update(
            @Parameter(description = "Listing id", example = "b4c2f0a8-3d1e-4e5a-9c7b-2f8d6a1e4b93",
                    schema = @Schema(type = "string", format = "uuid"))
            @PathVariable("id") String id,
            @Valid @RequestBody ListingUpdateRequest request) {
        return ApiResult.ok(listingService.update(CurrentUser.get(), parseListingId(id), request));
    }

    @Operation(summary = "Change a listing's status",
            description = "Moves the listing between DRAFT/ACTIVE/INACTIVE/ARCHIVED. Only ACTIVE "
                    + "listings appear in the public catalog and can have stock reserved. "
                    + "Caller must own the listing.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Status changed",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(name = "activated", value = EXAMPLE_STATUS_200))),
            @ApiResponse(responseCode = "400", description = "Malformed body (e.g. unknown status) or malformed id",
                    content = @Content(mediaType = "application/json", examples = {
                            @ExampleObject(name = "malformed-body", value = EXAMPLE_MALFORMED_400),
                            @ExampleObject(name = "invalid-id", value = EXAMPLE_INVALID_ID_400)})),
            @ApiResponse(responseCode = "401", description = "Missing/invalid token",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(name = "unauthorized", value = EXAMPLE_401))),
            @ApiResponse(responseCode = "403", description = "Wrong role, no merchant scope, or not the owner",
                    content = @Content(mediaType = "application/json", examples = {
                            @ExampleObject(name = "insufficient-role", value = EXAMPLE_ROLE_403),
                            @ExampleObject(name = "merchant-scope-missing", value = EXAMPLE_SCOPE_403),
                            @ExampleObject(name = "not-owned", value = EXAMPLE_NOT_OWNED_403)})),
            @ApiResponse(responseCode = "404", description = "No listing with that id",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(name = "not-found", value = EXAMPLE_NOT_FOUND_404)))
    })
    @PatchMapping("/{id}/status")
    public ApiResult<ListingResponse> changeStatus(
            @Parameter(description = "Listing id", example = "b4c2f0a8-3d1e-4e5a-9c7b-2f8d6a1e4b93",
                    schema = @Schema(type = "string", format = "uuid"))
            @PathVariable("id") String id,
            @Valid @RequestBody ListingStatusRequest request) {
        return ApiResult.ok(listingService.changeStatus(CurrentUser.get(), parseListingId(id), request));
    }

    @Operation(summary = "List my listings",
            description = "All of the caller's merchant's listings (every status), newest first. "
                    + "Page size is clamped to 50.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "One page of the merchant's listings",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(name = "mine", value = EXAMPLE_MINE_200))),
            @ApiResponse(responseCode = "401", description = "Missing/invalid token",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(name = "unauthorized", value = EXAMPLE_401))),
            @ApiResponse(responseCode = "403", description = "Wrong role or no merchant scope",
                    content = @Content(mediaType = "application/json", examples = {
                            @ExampleObject(name = "insufficient-role", value = EXAMPLE_ROLE_403),
                            @ExampleObject(name = "merchant-scope-missing", value = EXAMPLE_SCOPE_403)}))
    })
    @GetMapping("/mine")
    public ApiResult<ListingPageResponse> listMine(
            @Parameter(description = "Zero-based page index",
                    schema = @Schema(type = "integer", defaultValue = "0"))
            @RequestParam(value = "page", defaultValue = "0") String page,
            @Parameter(description = "Page size (clamped to 50)",
                    schema = @Schema(type = "integer", defaultValue = "20"))
            @RequestParam(value = "size", defaultValue = "20") String size) {
        return ApiResult.ok(listingService.listMine(CurrentUser.get(),
                intParam(page, 0), intParam(size, 20)));
    }

    private static UUID parseListingId(String raw) {
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException ex) {
            throw ApiException.badRequest("invalid_listing_id", "Listing id must be a UUID");
        }
    }

    /** Lenient like the size clamp: a non-numeric page/size falls back to the
     *  default instead of erroring. */
    private static int intParam(String raw, int fallback) {
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }
}
