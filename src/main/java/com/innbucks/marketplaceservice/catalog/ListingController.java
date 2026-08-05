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
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.UUID;

/**
 * Seller-side listing management. Ownership and merchant scope are resolved
 * from the verified JWT via {@link CurrentUser} — never from the request.
 * SUPER_ADMIN passes the class gate too; the service layer grants it the
 * fleet-oversight bypasses (any-merchant management, on-behalf creation,
 * all-listings reads) — see {@link ListingService}.
 *
 * <p>UUID path variables and numeric query params are parsed leniently in this
 * controller (400 {@code invalid_listing_id} / fall back to defaults) because
 * {@code GlobalExceptionHandler} has no {@code MethodArgumentTypeMismatchException}
 * mapping — a typed {@code @PathVariable UUID} would 500 on garbage input.
 */
@Tag(name = "Merchant Listings",
        description = "Seller-side listing management. Requires a MERCHANT_ADMIN fleet token carrying "
                + "a merchantId claim (owner's call: merchant-side administration is MERCHANT_ADMIN-only; "
                + "shop staff roles are deliberately excluded) or a SUPER_ADMIN token (fleet oversight: "
                + "any merchant's listings, on-behalf creation via the request merchantId field); "
                + "merchant scope is never taken from the request body except that one admin case.")
@RestController
@RequestMapping("/marketplace/listings")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('MERCHANT_ADMIN','SUPER_ADMIN')")
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
                "updatedAt": "2026-08-05T09:15:00Z",
                "imageUrl": null
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
                "updatedAt": "2026-08-05T09:20:00Z",
                "imageUrl": null
              }
            }""";

    /** Same record after PUT /{id}/image landed the product photo (the GETs
     *  and the status change below then show the populated imageUrl). */
    private static final String EXAMPLE_IMAGE_200 = """
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
                "updatedAt": "2026-08-05T09:22:00Z",
                "imageUrl": "/marketplace/catalog/b4c2f0a8-3d1e-4e5a-9c7b-2f8d6a1e4b93/image"
              }
            }""";

    private static final String EXAMPLE_IMAGE_DELETED_200 = """
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
                "updatedAt": "2026-08-05T09:23:00Z",
                "imageUrl": null
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
                "updatedAt": "2026-08-05T09:25:00Z",
                "imageUrl": "/marketplace/catalog/b4c2f0a8-3d1e-4e5a-9c7b-2f8d6a1e4b93/image"
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
                    "updatedAt": "2026-08-05T09:25:00Z",
                    "imageUrl": "/marketplace/catalog/b4c2f0a8-3d1e-4e5a-9c7b-2f8d6a1e4b93/image"
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

    private static final String EXAMPLE_MERCHANT_ID_REQUIRED_400 = """
            {
              "code": "merchant_id_required",
              "message": "merchantId is required when a SUPER_ADMIN creates a listing on behalf of a merchant"
            }""";

    private static final String EXAMPLE_SCOPE_MISMATCH_422 = """
            {
              "code": "merchant_scope_mismatch",
              "message": "merchantId in the request does not match the caller's merchant scope"
            }""";

    private static final String EXAMPLE_INVALID_MERCHANT_FILTER_400 = """
            {
              "code": "invalid_merchant_id",
              "message": "merchantId filter must be a UUID"
            }""";

    private static final String EXAMPLE_IMAGE_REQUIRED_400 = """
            {
              "code": "image_required",
              "message": "An image file part named 'image' is required"
            }""";

    private static final String EXAMPLE_UNSUPPORTED_IMAGE_400 = """
            {
              "code": "unsupported_image_type",
              "message": "Please upload a valid image file (JPG, PNG, or WEBP)."
            }""";

    private static final String EXAMPLE_IMAGE_TOO_LARGE_400 = """
            {
              "code": "image_too_large",
              "message": "That image is too large. Please use one under 10 MB."
            }""";

    @Operation(summary = "Create a listing",
            description = "Creates a DRAFT listing owned by the caller's merchant (merchantId JWT claim). "
                    + "Currency is always the cell currency; HTML in title/description/category is "
                    + "stripped server-side. Publish it via PATCH /marketplace/listings/{id}/status. "
                    + "SUPER_ADMIN creates ON BEHALF of a merchant and MUST send the request merchantId "
                    + "field (admins carry no merchant claim); a MERCHANT_ADMIN sending a merchantId "
                    + "different from their own claim is refused with 422.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Listing created as DRAFT",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(name = "created", value = EXAMPLE_CREATED_201))),
            @ApiResponse(responseCode = "400", description = "Validation failed, or SUPER_ADMIN omitted "
                    + "the target merchantId",
                    content = @Content(mediaType = "application/json", examples = {
                            @ExampleObject(name = "bean-validation", value = EXAMPLE_VALIDATION_400),
                            @ExampleObject(name = "title-empty-after-sanitization", value = EXAMPLE_TITLE_400),
                            @ExampleObject(name = "super-admin-without-merchant-id",
                                    value = EXAMPLE_MERCHANT_ID_REQUIRED_400)})),
            @ApiResponse(responseCode = "401", description = "Missing/invalid token",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(name = "unauthorized", value = EXAMPLE_401))),
            @ApiResponse(responseCode = "403", description = "Wrong role or no merchant scope",
                    content = @Content(mediaType = "application/json", examples = {
                            @ExampleObject(name = "insufficient-role", value = EXAMPLE_ROLE_403),
                            @ExampleObject(name = "merchant-scope-missing", value = EXAMPLE_SCOPE_403)})),
            @ApiResponse(responseCode = "409", description = "Per-merchant listing cap reached",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(name = "limit-reached", value = EXAMPLE_LIMIT_409))),
            @ApiResponse(responseCode = "422", description = "MERCHANT_ADMIN sent a merchantId that is "
                    + "not their own",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(name = "merchant-scope-mismatch",
                                    value = EXAMPLE_SCOPE_MISMATCH_422)))
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

    @Operation(summary = "Upload/replace the listing image",
            description = "Multipart single file part named `image` (JPEG/PNG/WEBP — GIF is not "
                    + "accepted; max 10 MB). The declared Content-Type AND the file's magic-byte "
                    + "signature are both validated (event-service banner discipline). The bytes are "
                    + "then served publicly at GET /marketplace/catalog/{id}/image — the imageUrl on "
                    + "the response. Caller must own the listing (SUPER_ADMIN may manage any). "
                    + "This is deliberately a separate endpoint: the JSON create contract is "
                    + "published to the FE and stays non-multipart.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Image stored; listing returned with imageUrl",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(name = "image-uploaded", value = EXAMPLE_IMAGE_200))),
            @ApiResponse(responseCode = "400", description = "Missing/empty part, unsupported type or "
                    + "signature, too large, or malformed id",
                    content = @Content(mediaType = "application/json", examples = {
                            @ExampleObject(name = "image-required", value = EXAMPLE_IMAGE_REQUIRED_400),
                            @ExampleObject(name = "unsupported-image-type", value = EXAMPLE_UNSUPPORTED_IMAGE_400),
                            @ExampleObject(name = "image-too-large", value = EXAMPLE_IMAGE_TOO_LARGE_400),
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
    @PutMapping(value = "/{id}/image", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResult<ListingResponse> uploadImage(
            @Parameter(description = "Listing id", example = "b4c2f0a8-3d1e-4e5a-9c7b-2f8d6a1e4b93",
                    schema = @Schema(type = "string", format = "uuid"))
            @PathVariable("id") String id,
            // required=false so an absent part renders OUR 400 image_required
            // instead of Spring's MissingServletRequestPartException falling
            // into the catch-all as a 500.
            @Parameter(description = "Image file (JPEG/PNG/WEBP, max 10 MB)")
            @RequestPart(value = "image", required = false) MultipartFile image) {
        return ApiResult.ok(listingService.uploadImage(CurrentUser.get(), parseListingId(id), image));
    }

    @Operation(summary = "Remove the listing image",
            description = "Clears the stored image bytes and content type; the public image URL then "
                    + "404s and imageUrl returns to null. Removing an absent image is a no-op 200. "
                    + "Caller must own the listing (SUPER_ADMIN may manage any).")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Image removed; listing returned with null imageUrl",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(name = "image-deleted", value = EXAMPLE_IMAGE_DELETED_200))),
            @ApiResponse(responseCode = "400", description = "Malformed id",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(name = "invalid-id", value = EXAMPLE_INVALID_ID_400))),
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
    @DeleteMapping("/{id}/image")
    public ApiResult<ListingResponse> deleteImage(
            @Parameter(description = "Listing id", example = "b4c2f0a8-3d1e-4e5a-9c7b-2f8d6a1e4b93",
                    schema = @Schema(type = "string", format = "uuid"))
            @PathVariable("id") String id) {
        return ApiResult.ok(listingService.deleteImage(CurrentUser.get(), parseListingId(id)));
    }

    @Operation(summary = "List my listings",
            description = "All of the caller's merchant's listings (every status), newest first. "
                    + "Page size is clamped to 50. SUPER_ADMIN gets ALL merchants' listings (any "
                    + "status), optionally narrowed with ?merchantId=; for MERCHANT_ADMIN callers "
                    + "that filter is ignored — their scope always comes from the JWT claim.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "One page of listings",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(name = "mine", value = EXAMPLE_MINE_200))),
            @ApiResponse(responseCode = "400", description = "Malformed merchantId filter",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(name = "invalid-merchant-id",
                                    value = EXAMPLE_INVALID_MERCHANT_FILTER_400))),
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
            @RequestParam(value = "size", defaultValue = "20") String size,
            @Parameter(description = "SUPER_ADMIN only: narrow the all-listings view to one merchant. "
                    + "Ignored for MERCHANT_ADMIN callers.",
                    example = "7e2a9c41-5b8f-4d36-a1c9-8f3b6d2e7a54",
                    schema = @Schema(type = "string", format = "uuid"))
            @RequestParam(value = "merchantId", required = false) String merchantId) {
        return ApiResult.ok(listingService.listMine(CurrentUser.get(),
                intParam(page, 0), intParam(size, 20), parseOptionalMerchantId(merchantId)));
    }

    private static UUID parseListingId(String raw) {
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException ex) {
            throw ApiException.badRequest("invalid_listing_id", "Listing id must be a UUID");
        }
    }

    /** Absent/blank → null (no filter); present garbage is a clean 400 rather
     *  than silently returning the unfiltered set. */
    private static UUID parseOptionalMerchantId(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(raw.trim());
        } catch (IllegalArgumentException ex) {
            throw ApiException.badRequest("invalid_merchant_id", "merchantId filter must be a UUID");
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
