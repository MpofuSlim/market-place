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
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Encoding;
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

import java.util.List;
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
                + "merchant scope is never taken from the request body except that one admin case. "
                + "Listings carry a GALLERY of up to 10 images — exactly one PRIMARY (required to "
                + "publish) plus up to 9 additional.")
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
                "categoryCode": "tv-audio",
                "categoryName": "TV & Audio",
                "condition": "NEW",
                "city": "Harare",
                "area": "Avondale",
                "priceCents": 2599,
                "currency": "USD",
                "stockQty": 120,
                "status": "DRAFT",
                "ratingAvg": null,
                "reviewCount": 0,
                "createdAt": "2026-08-05T09:15:00Z",
                "updatedAt": "2026-08-05T09:15:00Z",
                "imageUrl": null,
                "imageUrls": []
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
                "categoryCode": "tv-audio",
                "categoryName": "TV & Audio",
                "condition": "NEW",
                "city": "Harare",
                "area": "Avondale",
                "priceCents": 2399,
                "currency": "USD",
                "stockQty": 150,
                "status": "DRAFT",
                "ratingAvg": null,
                "reviewCount": 0,
                "createdAt": "2026-08-05T09:15:00Z",
                "updatedAt": "2026-08-05T09:20:00Z",
                "imageUrl": null,
                "imageUrls": []
              }
            }""";

    /** Same record after PUT /{id}/image landed the primary product photo (the
     *  GETs and the status change below then show the populated gallery). */
    private static final String EXAMPLE_IMAGE_200 = """
            {
              "code": "OK",
              "message": "Success",
              "data": {
                "id": "b4c2f0a8-3d1e-4e5a-9c7b-2f8d6a1e4b93",
                "merchantId": "7e2a9c41-5b8f-4d36-a1c9-8f3b6d2e7a54",
                "title": "Wireless Bluetooth Speaker",
                "description": "Portable speaker with 12h battery life.",
                "categoryCode": "tv-audio",
                "categoryName": "TV & Audio",
                "condition": "NEW",
                "city": "Harare",
                "area": "Avondale",
                "priceCents": 2399,
                "currency": "USD",
                "stockQty": 150,
                "status": "DRAFT",
                "ratingAvg": null,
                "reviewCount": 0,
                "createdAt": "2026-08-05T09:15:00Z",
                "updatedAt": "2026-08-05T09:22:00Z",
                "imageUrl": "/marketplace/catalog/b4c2f0a8-3d1e-4e5a-9c7b-2f8d6a1e4b93/image",
                "imageUrls": [
                  "/marketplace/catalog/b4c2f0a8-3d1e-4e5a-9c7b-2f8d6a1e4b93/images/5f0d8c2a-7b3e-4d16-9a8c-1e2f3a4b5c6d"
                ]
              }
            }""";

    /** Same record after POST /{id}/images appended a second (non-primary)
     *  photo — the primary stays first in imageUrls. */
    private static final String EXAMPLE_IMAGE_ADDED_200 = """
            {
              "code": "OK",
              "message": "Success",
              "data": {
                "id": "b4c2f0a8-3d1e-4e5a-9c7b-2f8d6a1e4b93",
                "merchantId": "7e2a9c41-5b8f-4d36-a1c9-8f3b6d2e7a54",
                "title": "Wireless Bluetooth Speaker",
                "description": "Portable speaker with 12h battery life.",
                "categoryCode": "tv-audio",
                "categoryName": "TV & Audio",
                "condition": "NEW",
                "city": "Harare",
                "area": "Avondale",
                "priceCents": 2399,
                "currency": "USD",
                "stockQty": 150,
                "status": "DRAFT",
                "ratingAvg": null,
                "reviewCount": 0,
                "createdAt": "2026-08-05T09:15:00Z",
                "updatedAt": "2026-08-05T09:23:00Z",
                "imageUrl": "/marketplace/catalog/b4c2f0a8-3d1e-4e5a-9c7b-2f8d6a1e4b93/image",
                "imageUrls": [
                  "/marketplace/catalog/b4c2f0a8-3d1e-4e5a-9c7b-2f8d6a1e4b93/images/5f0d8c2a-7b3e-4d16-9a8c-1e2f3a4b5c6d",
                  "/marketplace/catalog/b4c2f0a8-3d1e-4e5a-9c7b-2f8d6a1e4b93/images/8a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d"
                ]
              }
            }""";

    /** Same record after DELETE /{id}/images/{primaryImageId}: the second
     *  photo was auto-promoted to primary (lowest position survivor). */
    private static final String EXAMPLE_IMAGE_PROMOTED_200 = """
            {
              "code": "OK",
              "message": "Success",
              "data": {
                "id": "b4c2f0a8-3d1e-4e5a-9c7b-2f8d6a1e4b93",
                "merchantId": "7e2a9c41-5b8f-4d36-a1c9-8f3b6d2e7a54",
                "title": "Wireless Bluetooth Speaker",
                "description": "Portable speaker with 12h battery life.",
                "categoryCode": "tv-audio",
                "categoryName": "TV & Audio",
                "condition": "NEW",
                "city": "Harare",
                "area": "Avondale",
                "priceCents": 2399,
                "currency": "USD",
                "stockQty": 150,
                "status": "DRAFT",
                "ratingAvg": null,
                "reviewCount": 0,
                "createdAt": "2026-08-05T09:15:00Z",
                "updatedAt": "2026-08-05T09:24:00Z",
                "imageUrl": "/marketplace/catalog/b4c2f0a8-3d1e-4e5a-9c7b-2f8d6a1e4b93/image",
                "imageUrls": [
                  "/marketplace/catalog/b4c2f0a8-3d1e-4e5a-9c7b-2f8d6a1e4b93/images/8a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d"
                ]
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
                "categoryCode": "tv-audio",
                "categoryName": "TV & Audio",
                "condition": "NEW",
                "city": "Harare",
                "area": "Avondale",
                "priceCents": 2399,
                "currency": "USD",
                "stockQty": 150,
                "status": "DRAFT",
                "ratingAvg": null,
                "reviewCount": 0,
                "createdAt": "2026-08-05T09:15:00Z",
                "updatedAt": "2026-08-05T09:25:00Z",
                "imageUrl": null,
                "imageUrls": []
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
                "categoryCode": "tv-audio",
                "categoryName": "TV & Audio",
                "condition": "NEW",
                "city": "Harare",
                "area": "Avondale",
                "priceCents": 2399,
                "currency": "USD",
                "stockQty": 150,
                "status": "ACTIVE",
                "ratingAvg": null,
                "reviewCount": 0,
                "createdAt": "2026-08-05T09:15:00Z",
                "updatedAt": "2026-08-05T09:26:00Z",
                "imageUrl": "/marketplace/catalog/b4c2f0a8-3d1e-4e5a-9c7b-2f8d6a1e4b93/image",
                "imageUrls": [
                  "/marketplace/catalog/b4c2f0a8-3d1e-4e5a-9c7b-2f8d6a1e4b93/images/5f0d8c2a-7b3e-4d16-9a8c-1e2f3a4b5c6d"
                ]
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
                    "categoryCode": "tv-audio",
                    "categoryName": "TV & Audio",
                    "condition": "NEW",
                    "city": "Harare",
                    "area": "Avondale",
                    "priceCents": 2399,
                    "currency": "USD",
                    "stockQty": 150,
                    "status": "ACTIVE",
                    "ratingAvg": null,
                    "reviewCount": 0,
                    "createdAt": "2026-08-05T09:15:00Z",
                    "updatedAt": "2026-08-05T09:26:00Z",
                    "imageUrl": "/marketplace/catalog/b4c2f0a8-3d1e-4e5a-9c7b-2f8d6a1e4b93/image",
                    "imageUrls": [
                      "/marketplace/catalog/b4c2f0a8-3d1e-4e5a-9c7b-2f8d6a1e4b93/images/5f0d8c2a-7b3e-4d16-9a8c-1e2f3a4b5c6d"
                    ]
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

    private static final String EXAMPLE_UNKNOWN_CATEGORY_400 = """
            {
              "code": "unknown_category",
              "message": "categoryCode is not part of the marketplace taxonomy"
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

    private static final String EXAMPLE_INVALID_IMAGE_ID_400 = """
            {
              "code": "invalid_image_id",
              "message": "Image id must be a UUID"
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

    private static final String EXAMPLE_IMAGE_NOT_FOUND_404 = """
            {
              "code": "image_not_found",
              "message": "No such image for this listing"
            }""";

    private static final String EXAMPLE_LIMIT_409 = """
            {
              "code": "listing_limit_reached",
              "message": "Merchant listing limit reached"
            }""";

    private static final String EXAMPLE_IMAGE_LIMIT_409 = """
            {
              "code": "image_limit_reached",
              "message": "A listing can have at most 10 images"
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

    private static final String EXAMPLE_PRIMARY_IMAGE_REQUIRED_422 = """
            {
              "code": "primary_image_required",
              "message": "A primary image is required before a listing can be published"
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

    private static final String EXAMPLE_TOO_MANY_IMAGES_400 = """
            {
              "code": "too_many_images",
              "message": "At most 9 additional images are allowed (10 in total with the primary)"
            }""";

    @Operation(summary = "Create a listing",
            description = "Creates a DRAFT listing owned by the caller's merchant (merchantId JWT claim) — "
                    + "merchants NEVER send a merchantId; scope is automatic from the token. "
                    + "Currency is always the cell currency; HTML in free-text fields is stripped "
                    + "server-side. categoryCode must come from GET /marketplace/categories (omitted "
                    + "defaults to 'other'); condition defaults to NEW; city/area are optional. "
                    + "Publish it via PATCH /marketplace/listings/{id}/status — note that going "
                    + "ACTIVE requires a primary image first. "
                    + "SUPER_ADMIN only: creates ON BEHALF of a merchant and MUST send the request "
                    + "merchantId field (admins carry no merchant claim); a MERCHANT_ADMIN sending a "
                    + "merchantId different from their own claim is refused with 422.",
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ListingCreateRequest.class),
                            examples = {
                                    @ExampleObject(name = "merchant (normal)",
                                            summary = "MERCHANT_ADMIN — no merchantId, scope comes from your JWT",
                                            value = """
                                                    {
                                                      "title": "Wireless Bluetooth Speaker",
                                                      "description": "Portable speaker with 12h battery life.",
                                                      "categoryCode": "tv-audio",
                                                      "condition": "NEW",
                                                      "city": "Harare",
                                                      "area": "Avondale",
                                                      "priceCents": 2599,
                                                      "stockQty": 120
                                                    }"""),
                                    @ExampleObject(name = "super-admin on behalf of a merchant",
                                            summary = "SUPER_ADMIN only — must name the target merchant",
                                            value = """
                                                    {
                                                      "title": "Wireless Bluetooth Speaker",
                                                      "description": "Portable speaker with 12h battery life.",
                                                      "categoryCode": "tv-audio",
                                                      "condition": "NEW",
                                                      "city": "Harare",
                                                      "area": "Avondale",
                                                      "priceCents": 2599,
                                                      "stockQty": 120,
                                                      "merchantId": "7e2a9c41-5b8f-4d36-a1c9-8f3b6d2e7a54"
                                                    }""")
                            })))
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Listing created as DRAFT",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(name = "created", value = EXAMPLE_CREATED_201))),
            @ApiResponse(responseCode = "400", description = "Validation failed, unknown categoryCode, "
                    + "or SUPER_ADMIN omitted the target merchantId",
                    content = @Content(mediaType = "application/json", examples = {
                            @ExampleObject(name = "bean-validation", value = EXAMPLE_VALIDATION_400),
                            @ExampleObject(name = "title-empty-after-sanitization", value = EXAMPLE_TITLE_400),
                            @ExampleObject(name = "unknown-category", value = EXAMPLE_UNKNOWN_CATEGORY_400),
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

    @Operation(summary = "Create a listing WITH its image gallery in one request",
            description = """
                    Multipart variant of the JSON create (same path, selected by \
                    Content-Type). Parts:
                    - `listing` — JSON body matching the plain create request.
                    - `image` — optional; becomes the gallery's PRIMARY image \
                    (JPEG/PNG/WEBP — GIF is not accepted; max 10 MB each, \
                    magic-byte verified).
                    - `images` — optional REPEATED part: up to 9 additional \
                    gallery images (400 too_many_images beyond that; 10 images \
                    total). When `image` is omitted, the FIRST `images` entry \
                    becomes the primary so a created gallery always has one.

                    ANY invalid file refuses the WHOLE create — no half-created \
                    listing is ever left behind. The plain JSON create remains \
                    available for clients without images.""",
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    content = @Content(
                            mediaType = MediaType.MULTIPART_FORM_DATA_VALUE,
                            schema = @Schema(implementation = CreateListingMultipartRequest.class),
                            encoding = {
                                    @Encoding(name = "listing", contentType = MediaType.APPLICATION_JSON_VALUE),
                                    @Encoding(name = "image", contentType = "image/png, image/jpeg, image/webp"),
                                    @Encoding(name = "images", contentType = "image/png, image/jpeg, image/webp")
                            })))
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Listing created (with its gallery when "
                    + "files were supplied — imageUrl serves the primary, imageUrls lists every image "
                    + "primary-first)",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(name = "created", value = EXAMPLE_CREATED_201))),
            @ApiResponse(responseCode = "400", description = "Validation failed, bad image "
                    + "(unsupported_image_type / image_too_large / image_required), more than 9 "
                    + "additional images, unknown categoryCode, or SUPER_ADMIN without merchantId",
                    content = @Content(mediaType = "application/json", examples = {
                            @ExampleObject(name = "unsupported-image", value = EXAMPLE_UNSUPPORTED_IMAGE_400),
                            @ExampleObject(name = "too-many-images", value = EXAMPLE_TOO_MANY_IMAGES_400),
                            @ExampleObject(name = "unknown-category", value = EXAMPLE_UNKNOWN_CATEGORY_400),
                            @ExampleObject(name = "merchant-id-required",
                                    value = EXAMPLE_MERCHANT_ID_REQUIRED_400)})),
            @ApiResponse(responseCode = "401", description = "Missing or invalid bearer token",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(name = "unauthorized", value = EXAMPLE_401))),
            @ApiResponse(responseCode = "403", description = "Caller is not MERCHANT_ADMIN/SUPER_ADMIN, "
                    + "or has no merchant scope",
                    content = @Content(mediaType = "application/json", examples = {
                            @ExampleObject(name = "insufficient-role", value = EXAMPLE_ROLE_403),
                            @ExampleObject(name = "merchant-scope-missing", value = EXAMPLE_SCOPE_403)})),
            @ApiResponse(responseCode = "409", description = "Per-merchant listing cap reached",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(name = "limit-reached", value = EXAMPLE_LIMIT_409))),
            @ApiResponse(responseCode = "422", description = "MERCHANT_ADMIN sent a foreign merchantId",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(name = "merchant-scope-mismatch",
                                    value = EXAMPLE_SCOPE_MISMATCH_422)))
    })
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResult<ListingResponse>> createWithImages(
            @Valid @RequestPart("listing") ListingCreateRequest listing,
            @RequestPart(value = "image", required = false) MultipartFile image,
            @RequestPart(value = "images", required = false) List<MultipartFile> images) {
        ListingResponse created = listingService.create(CurrentUser.get(), listing, image, images);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResult.created(created));
    }

    // Schema-only helper so springdoc renders a usable multipart form in
    // Swagger UI (separate JSON text field + file pickers). Not used at
    // runtime (same pattern as event-service's CreateEventMultipartRequest).
    @Schema(name = "CreateListingMultipartRequest")
    @SuppressWarnings("unused")
    private static class CreateListingMultipartRequest {
        @Schema(description = "Listing JSON payload. MERCHANT_ADMIN: no merchantId — scope comes "
                + "from your JWT (SUPER_ADMIN on-behalf creation adds it).",
                implementation = ListingCreateRequest.class,
                example = """
                        {
                          "title": "Wireless Bluetooth Speaker",
                          "description": "Portable speaker with 12h battery life.",
                          "categoryCode": "tv-audio",
                          "condition": "NEW",
                          "city": "Harare",
                          "area": "Avondale",
                          "priceCents": 2599,
                          "stockQty": 120
                        }""")
        public ListingCreateRequest listing;

        @Schema(type = "string", format = "binary",
                description = "Optional PRIMARY image (JPEG/PNG/WEBP — GIF is not accepted; max 10 MB).")
        public MultipartFile image;

        @ArraySchema(arraySchema = @Schema(description = "Optional repeated part: up to 9 additional "
                + "gallery images (JPEG/PNG/WEBP, max 10 MB each)."),
                schema = @Schema(type = "string", format = "binary"))
        public List<MultipartFile> images;
    }

    @Operation(summary = "Update a listing",
            description = "Full replace of the listing's content (title/description/categoryCode/"
                    + "condition/city/area/price/stock — omitted categoryCode falls back to 'other' "
                    + "and omitted condition to NEW, so send current values to keep them). Status and "
                    + "currency are not updatable here; the gallery has its own endpoints. Caller "
                    + "must own the listing.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Listing updated",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(name = "updated", value = EXAMPLE_UPDATED_200))),
            @ApiResponse(responseCode = "400", description = "Validation failed, unknown categoryCode, "
                    + "or malformed id",
                    content = @Content(mediaType = "application/json", examples = {
                            @ExampleObject(name = "bean-validation", value = EXAMPLE_VALIDATION_400),
                            @ExampleObject(name = "title-empty-after-sanitization", value = EXAMPLE_TITLE_400),
                            @ExampleObject(name = "unknown-category", value = EXAMPLE_UNKNOWN_CATEGORY_400),
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
                    + "PUBLISH GATE: a transition TO ACTIVE requires the gallery to have a primary "
                    + "image — 422 primary_image_required otherwise (drafts may stay imageless; "
                    + "listings already ACTIVE are unaffected). Caller must own the listing.")
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
                            examples = @ExampleObject(name = "not-found", value = EXAMPLE_NOT_FOUND_404))),
            @ApiResponse(responseCode = "422", description = "Publish gate: transition to ACTIVE with "
                    + "no primary image in the gallery",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(name = "primary-image-required",
                                    value = EXAMPLE_PRIMARY_IMAGE_REQUIRED_422)))
    })
    @PatchMapping("/{id}/status")
    public ApiResult<ListingResponse> changeStatus(
            @Parameter(description = "Listing id", example = "b4c2f0a8-3d1e-4e5a-9c7b-2f8d6a1e4b93",
                    schema = @Schema(type = "string", format = "uuid"))
            @PathVariable("id") String id,
            @Valid @RequestBody ListingStatusRequest request) {
        return ApiResult.ok(listingService.changeStatus(CurrentUser.get(), parseListingId(id), request));
    }

    @Operation(summary = "Upload/replace the PRIMARY listing image",
            description = "Multipart single file part named `image` (JPEG/PNG/WEBP — GIF is not "
                    + "accepted; max 10 MB). The declared Content-Type AND the file's magic-byte "
                    + "signature are both validated (event-service banner discipline). REPLACES the "
                    + "gallery's primary image in place, or creates it when the gallery has none "
                    + "(back-compat V2 contract — additional images are untouched). The primary is "
                    + "served publicly at GET /marketplace/catalog/{id}/image — the imageUrl on the "
                    + "response. Caller must own the listing (SUPER_ADMIN may manage any).")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Primary image stored; listing returned "
                    + "with imageUrl + imageUrls",
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
                            examples = @ExampleObject(name = "not-found", value = EXAMPLE_NOT_FOUND_404))),
            @ApiResponse(responseCode = "409", description = "Gallery already holds 10 images and none "
                    + "is primary, so a new primary cannot be added — delete one first",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(name = "image-limit-reached",
                                    value = EXAMPLE_IMAGE_LIMIT_409)))
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

    @Operation(summary = "Remove the PRIMARY listing image",
            description = "Deletes the gallery's primary image. If other images remain, the "
                    + "lowest-position one is PROMOTED to primary (imageUrl keeps working, pointing "
                    + "at the new primary); with no survivors the public primary URL 404s and "
                    + "imageUrl returns to null. Removing an absent primary is a no-op 200. "
                    + "Caller must own the listing (SUPER_ADMIN may manage any).")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Primary removed (next image promoted when "
                    + "one remains); listing returned",
                    content = @Content(mediaType = "application/json", examples = {
                            @ExampleObject(name = "gallery-empty-after-delete", value = EXAMPLE_IMAGE_DELETED_200),
                            @ExampleObject(name = "survivor-promoted", value = EXAMPLE_IMAGE_PROMOTED_200)})),
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

    @Operation(summary = "Add an image to the listing's gallery",
            description = "Multipart single file part named `image` (JPEG/PNG/WEBP, max 10 MB, same "
                    + "magic-byte validation as the primary upload). The image is APPENDED after the "
                    + "current last position as a non-primary — except into an empty gallery, where "
                    + "the sole image becomes the primary (a gallery with images always has exactly "
                    + "one primary). At 10 images the gallery is full: 409 image_limit_reached. "
                    + "Caller must own the listing (SUPER_ADMIN may manage any).")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Image added; listing returned with the "
                    + "grown imageUrls (primary always first)",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(name = "image-added", value = EXAMPLE_IMAGE_ADDED_200))),
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
                            examples = @ExampleObject(name = "not-found", value = EXAMPLE_NOT_FOUND_404))),
            @ApiResponse(responseCode = "409", description = "Gallery already holds 10 images",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(name = "image-limit-reached",
                                    value = EXAMPLE_IMAGE_LIMIT_409)))
    })
    @PostMapping(value = "/{id}/images", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResult<ListingResponse> addImage(
            @Parameter(description = "Listing id", example = "b4c2f0a8-3d1e-4e5a-9c7b-2f8d6a1e4b93",
                    schema = @Schema(type = "string", format = "uuid"))
            @PathVariable("id") String id,
            @Parameter(description = "Image file (JPEG/PNG/WEBP, max 10 MB)")
            @RequestPart(value = "image", required = false) MultipartFile image) {
        return ApiResult.ok(listingService.addImage(CurrentUser.get(), parseListingId(id), image));
    }

    @Operation(summary = "Remove one gallery image",
            description = "Deletes the image with the given id from this listing's gallery (the id "
                    + "must belong to THIS listing — 404 image_not_found otherwise). Deleting the "
                    + "PRIMARY promotes the lowest-position survivor to primary, so a non-empty "
                    + "gallery always keeps one. Caller must own the listing (SUPER_ADMIN may "
                    + "manage any).")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Image removed; listing returned (note the "
                    + "promotion when the primary was deleted)",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(name = "primary-deleted-survivor-promoted",
                                    value = EXAMPLE_IMAGE_PROMOTED_200))),
            @ApiResponse(responseCode = "400", description = "Malformed listing or image id",
                    content = @Content(mediaType = "application/json", examples = {
                            @ExampleObject(name = "invalid-id", value = EXAMPLE_INVALID_ID_400),
                            @ExampleObject(name = "invalid-image-id", value = EXAMPLE_INVALID_IMAGE_ID_400)})),
            @ApiResponse(responseCode = "401", description = "Missing/invalid token",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(name = "unauthorized", value = EXAMPLE_401))),
            @ApiResponse(responseCode = "403", description = "Wrong role, no merchant scope, or not the owner",
                    content = @Content(mediaType = "application/json", examples = {
                            @ExampleObject(name = "insufficient-role", value = EXAMPLE_ROLE_403),
                            @ExampleObject(name = "merchant-scope-missing", value = EXAMPLE_SCOPE_403),
                            @ExampleObject(name = "not-owned", value = EXAMPLE_NOT_OWNED_403)})),
            @ApiResponse(responseCode = "404", description = "No listing with that id, or the imageId "
                    + "is not an image of this listing",
                    content = @Content(mediaType = "application/json", examples = {
                            @ExampleObject(name = "listing-not-found", value = EXAMPLE_NOT_FOUND_404),
                            @ExampleObject(name = "image-not-found", value = EXAMPLE_IMAGE_NOT_FOUND_404)}))
    })
    @DeleteMapping("/{id}/images/{imageId}")
    public ApiResult<ListingResponse> deleteGalleryImage(
            @Parameter(description = "Listing id", example = "b4c2f0a8-3d1e-4e5a-9c7b-2f8d6a1e4b93",
                    schema = @Schema(type = "string", format = "uuid"))
            @PathVariable("id") String id,
            @Parameter(description = "Gallery image id (from imageUrls)",
                    example = "5f0d8c2a-7b3e-4d16-9a8c-1e2f3a4b5c6d",
                    schema = @Schema(type = "string", format = "uuid"))
            @PathVariable("imageId") String imageId) {
        return ApiResult.ok(listingService.deleteGalleryImage(CurrentUser.get(),
                parseListingId(id), parseImageId(imageId)));
    }

    @Operation(summary = "Set a gallery image as the PRIMARY",
            description = "Atomic primary swap: the current primary is demoted and the named image "
                    + "promoted in one transaction (a partial unique index guarantees one primary per "
                    + "listing even under races). The imageId must belong to THIS listing — 404 "
                    + "image_not_found otherwise. Re-marking the current primary is a no-op 200. "
                    + "Caller must own the listing (SUPER_ADMIN may manage any).")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Primary set; listing returned with the "
                    + "new primary first in imageUrls and imageUrl serving it",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(name = "primary-set", value = EXAMPLE_IMAGE_PROMOTED_200))),
            @ApiResponse(responseCode = "400", description = "Malformed listing or image id",
                    content = @Content(mediaType = "application/json", examples = {
                            @ExampleObject(name = "invalid-id", value = EXAMPLE_INVALID_ID_400),
                            @ExampleObject(name = "invalid-image-id", value = EXAMPLE_INVALID_IMAGE_ID_400)})),
            @ApiResponse(responseCode = "401", description = "Missing/invalid token",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(name = "unauthorized", value = EXAMPLE_401))),
            @ApiResponse(responseCode = "403", description = "Wrong role, no merchant scope, or not the owner",
                    content = @Content(mediaType = "application/json", examples = {
                            @ExampleObject(name = "insufficient-role", value = EXAMPLE_ROLE_403),
                            @ExampleObject(name = "merchant-scope-missing", value = EXAMPLE_SCOPE_403),
                            @ExampleObject(name = "not-owned", value = EXAMPLE_NOT_OWNED_403)})),
            @ApiResponse(responseCode = "404", description = "No listing with that id, or the imageId "
                    + "is not an image of this listing",
                    content = @Content(mediaType = "application/json", examples = {
                            @ExampleObject(name = "listing-not-found", value = EXAMPLE_NOT_FOUND_404),
                            @ExampleObject(name = "image-not-found", value = EXAMPLE_IMAGE_NOT_FOUND_404)}))
    })
    @PutMapping("/{id}/images/{imageId}/primary")
    public ApiResult<ListingResponse> setPrimaryImage(
            @Parameter(description = "Listing id", example = "b4c2f0a8-3d1e-4e5a-9c7b-2f8d6a1e4b93",
                    schema = @Schema(type = "string", format = "uuid"))
            @PathVariable("id") String id,
            @Parameter(description = "Gallery image id (from imageUrls)",
                    example = "8a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d",
                    schema = @Schema(type = "string", format = "uuid"))
            @PathVariable("imageId") String imageId) {
        return ApiResult.ok(listingService.setPrimaryImage(CurrentUser.get(),
                parseListingId(id), parseImageId(imageId)));
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

    private static UUID parseImageId(String raw) {
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException ex) {
            throw ApiException.badRequest("invalid_image_id", "Image id must be a UUID");
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
