package com.innbucks.marketplaceservice.catalog;

import com.innbucks.marketplaceservice.api.ApiException;
import com.innbucks.marketplaceservice.api.ApiResult;
import com.innbucks.marketplaceservice.catalog.dto.ListingCreateRequest;
import com.innbucks.marketplaceservice.catalog.dto.ListingPageResponse;
import com.innbucks.marketplaceservice.catalog.dto.ListingResponse;
import com.innbucks.marketplaceservice.catalog.dto.ListingStatusRequest;
import com.innbucks.marketplaceservice.catalog.dto.ListingUpdateRequest;
import com.innbucks.marketplaceservice.catalog.dto.VariantStockRequest;
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
                + "publish) plus up to 9 additional. A listing may also sell OPTIONS (V19): up to 2 "
                + "axes such as Size and Colour and up to 50 options, each with its own stock and "
                + "an optional own price above the listing's - see create and update.")
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
                "imageUrls": [],
                "seller": {
                  "merchantId": "7e2a9c41-5b8f-4d36-a1c9-8f3b6d2e7a54",
                  "displayName": "Sunrise Electronics",
                  "verified": true,
                  "since": "2026-04-01T09:15:00Z"
                },
                "deliverable": true,
                "deliveryTowns": [
                  { "townCode": "harare", "townName": "Harare", "feeCents": 300 },
                  { "townCode": "bulawayo", "townName": "Bulawayo", "feeCents": 1200 }
                ],
                "collectionTowns": [
                  { "townCode": "harare", "townName": "Harare" }
                ],
                "hasVariants": false,
                "options": [],
                "variants": [],
                "maxPriceCents": 2599
              }
            }""";

    /** V19: the canonical listing WITH options as the JSON create returns it —
     *  a DRAFT with no photo yet. Every controller's examples use these ids
     *  (Cotton Crew Tee, sizes M/L/XL in Black, XL dearer, L sold out). */
    private static final String EXAMPLE_VARIANT_CREATED_201 = """
            {
              "code": "CREATED",
              "message": "Created",
              "data": {
                "id": "e3a91c57-2b4d-4f8e-9a16-7c5d0b2e8f41",
                "merchantId": "7e2a9c41-5b8f-4d36-a1c9-8f3b6d2e7a54",
                "title": "Cotton Crew Tee",
                "description": "100% cotton, pre-shrunk",
                "categoryCode": "other",
                "categoryName": "Other",
                "condition": "NEW",
                "city": "Harare",
                "area": "Avondale",
                "priceCents": 1999,
                "currency": "USD",
                "stockQty": 10,
                "status": "DRAFT",
                "ratingAvg": null,
                "reviewCount": 0,
                "createdAt": "2026-09-24T08:00:00Z",
                "updatedAt": "2026-09-24T08:00:00Z",
                "imageUrl": null,
                "imageUrls": [],
                "seller": {
                  "merchantId": "7e2a9c41-5b8f-4d36-a1c9-8f3b6d2e7a54",
                  "displayName": "Sunrise Electronics",
                  "verified": true,
                  "since": "2026-04-01T09:15:00Z"
                },
                "deliverable": true,
                "deliveryTowns": [
                  { "townCode": "harare", "townName": "Harare", "feeCents": 300 }
                ],
                "collectionTowns": [
                  { "townCode": "harare", "townName": "Harare" }
                ],
                "hasVariants": true,
                "options": [
                  { "name": "Size", "values": ["M", "L", "XL"] },
                  { "name": "Colour", "values": ["Black"] }
                ],
                "variants": [
                  { "id": "0a6f2d18-5c3b-4e97-8d21-b4f7e9c1a352", "values": ["M", "Black"],
                    "label": "M - Black", "priceCents": 1999, "stockQty": 4 },
                  { "id": "1b7e3e29-6d4c-4fa8-9e32-c5a8f0d2b463", "values": ["L", "Black"],
                    "label": "L - Black", "priceCents": 1999, "stockQty": 0 },
                  { "id": "2c8f4f3a-7e5d-40b9-af43-d6b9a1e3c574", "values": ["XL", "Black"],
                    "label": "XL - Black", "priceCents": 2299, "priceOverrideCents": 2299,
                    "stockQty": 6 }
                ],
                "maxPriceCents": 2299
              }
            }""";

    /** The multipart create of the same listing with its photo in the {@code image}
     *  part: identical but for the gallery (the photo the later examples show). */
    private static final String EXAMPLE_VARIANT_CREATED_WITH_IMAGE_201 = """
            {
              "code": "CREATED",
              "message": "Created",
              "data": {
                "id": "e3a91c57-2b4d-4f8e-9a16-7c5d0b2e8f41",
                "merchantId": "7e2a9c41-5b8f-4d36-a1c9-8f3b6d2e7a54",
                "title": "Cotton Crew Tee",
                "description": "100% cotton, pre-shrunk",
                "categoryCode": "other",
                "categoryName": "Other",
                "condition": "NEW",
                "city": "Harare",
                "area": "Avondale",
                "priceCents": 1999,
                "currency": "USD",
                "stockQty": 10,
                "status": "DRAFT",
                "ratingAvg": null,
                "reviewCount": 0,
                "createdAt": "2026-09-24T08:00:00Z",
                "updatedAt": "2026-09-24T08:00:00Z",
                "imageUrl": "/marketplace/catalog/e3a91c57-2b4d-4f8e-9a16-7c5d0b2e8f41/image",
                "imageUrls": [
                  "/marketplace/catalog/e3a91c57-2b4d-4f8e-9a16-7c5d0b2e8f41/images/4d1c7e2a-9b3f-4a58-8e6d-0f2a1b3c4d5e"
                ],
                "seller": {
                  "merchantId": "7e2a9c41-5b8f-4d36-a1c9-8f3b6d2e7a54",
                  "displayName": "Sunrise Electronics",
                  "verified": true,
                  "since": "2026-04-01T09:15:00Z"
                },
                "deliverable": true,
                "deliveryTowns": [
                  { "townCode": "harare", "townName": "Harare", "feeCents": 300 }
                ],
                "collectionTowns": [
                  { "townCode": "harare", "townName": "Harare" }
                ],
                "hasVariants": true,
                "options": [
                  { "name": "Size", "values": ["M", "L", "XL"] },
                  { "name": "Colour", "values": ["Black"] }
                ],
                "variants": [
                  { "id": "0a6f2d18-5c3b-4e97-8d21-b4f7e9c1a352", "values": ["M", "Black"],
                    "label": "M - Black", "priceCents": 1999, "stockQty": 4 },
                  { "id": "1b7e3e29-6d4c-4fa8-9e32-c5a8f0d2b463", "values": ["L", "Black"],
                    "label": "L - Black", "priceCents": 1999, "stockQty": 0 },
                  { "id": "2c8f4f3a-7e5d-40b9-af43-d6b9a1e3c574", "values": ["XL", "Black"],
                    "label": "XL - Black", "priceCents": 2299, "priceOverrideCents": 2299,
                    "stockQty": 6 }
                ],
                "maxPriceCents": 2299
              }
            }""";

    /** The same listing after PUT /{id} kept every option by id (see the update
     *  request example) — nothing moved but {@code updatedAt}, which is the
     *  point: omitting an option's stockQty keeps its stock. */
    private static final String EXAMPLE_VARIANT_UPDATED_200 = """
            {
              "code": "OK",
              "message": "Success",
              "data": {
                "id": "e3a91c57-2b4d-4f8e-9a16-7c5d0b2e8f41",
                "merchantId": "7e2a9c41-5b8f-4d36-a1c9-8f3b6d2e7a54",
                "title": "Cotton Crew Tee",
                "description": "100% cotton, pre-shrunk",
                "categoryCode": "other",
                "categoryName": "Other",
                "condition": "NEW",
                "city": "Harare",
                "area": "Avondale",
                "priceCents": 1999,
                "currency": "USD",
                "stockQty": 10,
                "status": "DRAFT",
                "ratingAvg": null,
                "reviewCount": 0,
                "createdAt": "2026-09-24T08:00:00Z",
                "updatedAt": "2026-09-24T08:20:00Z",
                "imageUrl": null,
                "imageUrls": [],
                "seller": {
                  "merchantId": "7e2a9c41-5b8f-4d36-a1c9-8f3b6d2e7a54",
                  "displayName": "Sunrise Electronics",
                  "verified": true,
                  "since": "2026-04-01T09:15:00Z"
                },
                "deliverable": true,
                "deliveryTowns": [
                  { "townCode": "harare", "townName": "Harare", "feeCents": 300 }
                ],
                "collectionTowns": [
                  { "townCode": "harare", "townName": "Harare" }
                ],
                "hasVariants": true,
                "options": [
                  { "name": "Size", "values": ["M", "L", "XL"] },
                  { "name": "Colour", "values": ["Black"] }
                ],
                "variants": [
                  { "id": "0a6f2d18-5c3b-4e97-8d21-b4f7e9c1a352", "values": ["M", "Black"],
                    "label": "M - Black", "priceCents": 1999, "stockQty": 4 },
                  { "id": "1b7e3e29-6d4c-4fa8-9e32-c5a8f0d2b463", "values": ["L", "Black"],
                    "label": "L - Black", "priceCents": 1999, "stockQty": 0 },
                  { "id": "2c8f4f3a-7e5d-40b9-af43-d6b9a1e3c574", "values": ["XL", "Black"],
                    "label": "XL - Black", "priceCents": 2299, "priceOverrideCents": 2299,
                    "stockQty": 6 }
                ],
                "maxPriceCents": 2299
              }
            }""";

    /** V19: the canonical listing WITH options — the same ids every controller's
     *  examples use (Cotton Crew Tee, sizes M/L/XL in Black, XL dearer). Shown
     *  here after PATCH .../variants/{L}/stock restocked size L to 12. */
    static final String EXAMPLE_VARIANT_LISTING_200 = """
            {
              "code": "OK",
              "message": "Success",
              "data": {
                "id": "e3a91c57-2b4d-4f8e-9a16-7c5d0b2e8f41",
                "merchantId": "7e2a9c41-5b8f-4d36-a1c9-8f3b6d2e7a54",
                "title": "Cotton Crew Tee",
                "description": "100% cotton, pre-shrunk",
                "categoryCode": "other",
                "categoryName": "Other",
                "condition": "NEW",
                "city": "Harare",
                "area": "Avondale",
                "priceCents": 1999,
                "currency": "USD",
                "stockQty": 22,
                "status": "ACTIVE",
                "ratingAvg": null,
                "reviewCount": 0,
                "createdAt": "2026-09-24T08:00:00Z",
                "updatedAt": "2026-09-24T10:30:00Z",
                "imageUrl": "/marketplace/catalog/e3a91c57-2b4d-4f8e-9a16-7c5d0b2e8f41/image",
                "imageUrls": [
                  "/marketplace/catalog/e3a91c57-2b4d-4f8e-9a16-7c5d0b2e8f41/images/4d1c7e2a-9b3f-4a58-8e6d-0f2a1b3c4d5e"
                ],
                "seller": {
                  "merchantId": "7e2a9c41-5b8f-4d36-a1c9-8f3b6d2e7a54",
                  "displayName": "Sunrise Electronics",
                  "verified": true,
                  "since": "2026-04-01T09:15:00Z"
                },
                "deliverable": true,
                "deliveryTowns": [
                  { "townCode": "harare", "townName": "Harare", "feeCents": 300 }
                ],
                "collectionTowns": [
                  { "townCode": "harare", "townName": "Harare" }
                ],
                "hasVariants": true,
                "options": [
                  { "name": "Size", "values": ["M", "L", "XL"] },
                  { "name": "Colour", "values": ["Black"] }
                ],
                "variants": [
                  { "id": "0a6f2d18-5c3b-4e97-8d21-b4f7e9c1a352", "values": ["M", "Black"],
                    "label": "M - Black", "priceCents": 1999, "stockQty": 4 },
                  { "id": "1b7e3e29-6d4c-4fa8-9e32-c5a8f0d2b463", "values": ["L", "Black"],
                    "label": "L - Black", "priceCents": 1999, "stockQty": 12 },
                  { "id": "2c8f4f3a-7e5d-40b9-af43-d6b9a1e3c574", "values": ["XL", "Black"],
                    "label": "XL - Black", "priceCents": 2299, "priceOverrideCents": 2299,
                    "stockQty": 6 }
                ],
                "maxPriceCents": 2299
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
                "imageUrls": [],
                "seller": {
                  "merchantId": "7e2a9c41-5b8f-4d36-a1c9-8f3b6d2e7a54",
                  "displayName": "Sunrise Electronics",
                  "verified": true,
                  "since": "2026-04-01T09:15:00Z"
                },
                "deliverable": true,
                "deliveryTowns": [
                  { "townCode": "harare", "townName": "Harare", "feeCents": 300 },
                  { "townCode": "bulawayo", "townName": "Bulawayo", "feeCents": 1200 }
                ],
                "collectionTowns": [
                  { "townCode": "harare", "townName": "Harare" }
                ],
                "hasVariants": false,
                "options": [],
                "variants": [],
                "maxPriceCents": 2399
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
                ],
                "seller": {
                  "merchantId": "7e2a9c41-5b8f-4d36-a1c9-8f3b6d2e7a54",
                  "displayName": "Sunrise Electronics",
                  "verified": true,
                  "since": "2026-04-01T09:15:00Z"
                },
                "deliverable": true,
                "deliveryTowns": [
                  { "townCode": "harare", "townName": "Harare", "feeCents": 300 },
                  { "townCode": "bulawayo", "townName": "Bulawayo", "feeCents": 1200 }
                ],
                "collectionTowns": [
                  { "townCode": "harare", "townName": "Harare" }
                ],
                "hasVariants": false,
                "options": [],
                "variants": [],
                "maxPriceCents": 2399
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
                ],
                "seller": {
                  "merchantId": "7e2a9c41-5b8f-4d36-a1c9-8f3b6d2e7a54",
                  "displayName": "Sunrise Electronics",
                  "verified": true,
                  "since": "2026-04-01T09:15:00Z"
                },
                "deliverable": true,
                "deliveryTowns": [
                  { "townCode": "harare", "townName": "Harare", "feeCents": 300 },
                  { "townCode": "bulawayo", "townName": "Bulawayo", "feeCents": 1200 }
                ],
                "collectionTowns": [
                  { "townCode": "harare", "townName": "Harare" }
                ],
                "hasVariants": false,
                "options": [],
                "variants": [],
                "maxPriceCents": 2399
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
                ],
                "seller": {
                  "merchantId": "7e2a9c41-5b8f-4d36-a1c9-8f3b6d2e7a54",
                  "displayName": "Sunrise Electronics",
                  "verified": true,
                  "since": "2026-04-01T09:15:00Z"
                },
                "deliverable": true,
                "deliveryTowns": [
                  { "townCode": "harare", "townName": "Harare", "feeCents": 300 },
                  { "townCode": "bulawayo", "townName": "Bulawayo", "feeCents": 1200 }
                ],
                "collectionTowns": [
                  { "townCode": "harare", "townName": "Harare" }
                ],
                "hasVariants": false,
                "options": [],
                "variants": [],
                "maxPriceCents": 2399
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
                "imageUrls": [],
                "seller": {
                  "merchantId": "7e2a9c41-5b8f-4d36-a1c9-8f3b6d2e7a54",
                  "displayName": "Sunrise Electronics",
                  "verified": true,
                  "since": "2026-04-01T09:15:00Z"
                },
                "deliverable": true,
                "deliveryTowns": [
                  { "townCode": "harare", "townName": "Harare", "feeCents": 300 },
                  { "townCode": "bulawayo", "townName": "Bulawayo", "feeCents": 1200 }
                ],
                "collectionTowns": [
                  { "townCode": "harare", "townName": "Harare" }
                ],
                "hasVariants": false,
                "options": [],
                "variants": [],
                "maxPriceCents": 2399
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
                ],
                "seller": {
                  "merchantId": "7e2a9c41-5b8f-4d36-a1c9-8f3b6d2e7a54",
                  "displayName": "Sunrise Electronics",
                  "verified": true,
                  "since": "2026-04-01T09:15:00Z"
                },
                "deliverable": true,
                "deliveryTowns": [
                  { "townCode": "harare", "townName": "Harare", "feeCents": 300 },
                  { "townCode": "bulawayo", "townName": "Bulawayo", "feeCents": 1200 }
                ],
                "collectionTowns": [
                  { "townCode": "harare", "townName": "Harare" }
                ],
                "hasVariants": false,
                "options": [],
                "variants": [],
                "maxPriceCents": 2399
              }
            }""";

    /** Newest first: the Cotton Crew Tee (published, size L still sold out -
     *  before the quick restock) above the speaker. */
    private static final String EXAMPLE_MINE_200 = """
            {
              "code": "OK",
              "message": "Success",
              "data": {
                "items": [
                  {
                    "id": "e3a91c57-2b4d-4f8e-9a16-7c5d0b2e8f41",
                    "merchantId": "7e2a9c41-5b8f-4d36-a1c9-8f3b6d2e7a54",
                    "title": "Cotton Crew Tee",
                    "description": "100% cotton, pre-shrunk",
                    "categoryCode": "other",
                    "categoryName": "Other",
                    "condition": "NEW",
                    "city": "Harare",
                    "area": "Avondale",
                    "priceCents": 1999,
                    "currency": "USD",
                    "stockQty": 10,
                    "status": "ACTIVE",
                    "ratingAvg": null,
                    "reviewCount": 0,
                    "createdAt": "2026-09-24T08:00:00Z",
                    "updatedAt": "2026-09-24T09:00:00Z",
                    "imageUrl": "/marketplace/catalog/e3a91c57-2b4d-4f8e-9a16-7c5d0b2e8f41/image",
                    "imageUrls": [
                      "/marketplace/catalog/e3a91c57-2b4d-4f8e-9a16-7c5d0b2e8f41/images/4d1c7e2a-9b3f-4a58-8e6d-0f2a1b3c4d5e"
                    ],
                    "seller": {
                      "merchantId": "7e2a9c41-5b8f-4d36-a1c9-8f3b6d2e7a54",
                      "displayName": "Sunrise Electronics",
                      "verified": true,
                      "since": "2026-04-01T09:15:00Z"
                    },
                    "deliverable": true,
                    "deliveryTowns": [
                      { "townCode": "harare", "townName": "Harare", "feeCents": 300 }
                    ],
                    "collectionTowns": [
                      { "townCode": "harare", "townName": "Harare" }
                    ],
                    "hasVariants": true,
                    "options": [
                      { "name": "Size", "values": ["M", "L", "XL"] },
                      { "name": "Colour", "values": ["Black"] }
                    ],
                    "variants": [
                      { "id": "0a6f2d18-5c3b-4e97-8d21-b4f7e9c1a352", "values": ["M", "Black"],
                        "label": "M - Black", "priceCents": 1999, "stockQty": 4 },
                      { "id": "1b7e3e29-6d4c-4fa8-9e32-c5a8f0d2b463", "values": ["L", "Black"],
                        "label": "L - Black", "priceCents": 1999, "stockQty": 0 },
                      { "id": "2c8f4f3a-7e5d-40b9-af43-d6b9a1e3c574", "values": ["XL", "Black"],
                        "label": "XL - Black", "priceCents": 2299, "priceOverrideCents": 2299,
                        "stockQty": 6 }
                    ],
                    "maxPriceCents": 2299
                  },
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
                    ],
                    "seller": {
                      "merchantId": "7e2a9c41-5b8f-4d36-a1c9-8f3b6d2e7a54",
                      "displayName": "Sunrise Electronics",
                      "verified": true,
                      "since": "2026-04-01T09:15:00Z"
                    },
                    "deliverable": true,
                    "deliveryTowns": [
                      { "townCode": "harare", "townName": "Harare", "feeCents": 300 },
                      { "townCode": "bulawayo", "townName": "Bulawayo", "feeCents": 1200 }
                    ],
                    "collectionTowns": [
                      { "townCode": "harare", "townName": "Harare" }
                    ],
                    "hasVariants": false,
                    "options": [],
                    "variants": [],
                    "maxPriceCents": 2399
                  }
                ],
                "page": 0,
                "size": 20,
                "totalItems": 2,
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

    private static final String EXAMPLE_UNKNOWN_TOWN_400 = """
            {
              "code": "unknown_town",
              "message": "deliveryTowns.townCode 'johannesburg' is not one of our delivery towns - choose one from the list"
            }""";

    private static final String EXAMPLE_DUPLICATE_TOWN_400 = """
            {
              "code": "duplicate_delivery_town",
              "message": "deliveryTowns names Harare more than once"
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

    // -- V19 options editor refusals (messages exactly as VariantSetResolver,
    //    VariantLabels, ListingVariantService and ListingService throw them) --

    private static final String EXAMPLE_INVALID_VARIANT_OPTION_400 = """
            {
              "code": "invalid_variant_option",
              "message": "variants[0].values[1] must not contain a comma"
            }""";

    private static final String EXAMPLE_VARIANT_VALUES_MISMATCH_400 = """
            {
              "code": "variant_values_mismatch",
              "message": "variants[2].values must give one value for each of Size, Colour"
            }""";

    private static final String EXAMPLE_DUPLICATE_VARIANT_400 = """
            {
              "code": "duplicate_variant",
              "message": "variants names M - Black more than once"
            }""";

    private static final String EXAMPLE_UNKNOWN_VARIANT_400 = """
            {
              "code": "unknown_variant",
              "message": "variants[1].id is not a variant of this listing"
            }""";

    /** A request whose priceCents (2299) is above the M option's own 1999. */
    private static final String EXAMPLE_VARIANT_PRICE_BELOW_400 = """
            {
              "code": "variant_price_below_listing_price",
              "message": "variants[0] (M - Black) costs less than priceCents - make priceCents the lowest option price"
            }""";

    /** An update that omits variants but raises priceCents above the XL
     *  option's own 2299: the kept options are re-checked against the floor. */
    private static final String EXAMPLE_KEPT_PRICE_BELOW_400 = """
            {
              "code": "variant_price_below_listing_price",
              "message": "The option XL - Black costs less than priceCents - make priceCents the lowest option price"
            }""";

    private static final String EXAMPLE_LISTING_PRICE_NOT_OFFERED_400 = """
            {
              "code": "listing_price_not_offered",
              "message": "No option sells at priceCents - make priceCents the lowest option price"
            }""";

    private static final String EXAMPLE_STOCK_REQUIRED_400 = """
            {
              "code": "stock_required",
              "message": "stockQty is required for a listing without variants"
            }""";

    private static final String EXAMPLE_VARIANT_STOCK_REQUIRED_400 = """
            {
              "code": "variant_stock_required",
              "message": "variants[2].stockQty is required for a new option"
            }""";

    private static final String EXAMPLE_OPTIONS_WITHOUT_VARIANTS_400 = """
            {
              "code": "options_without_variants",
              "message": "options can only be sent together with variants"
            }""";

    private static final String EXAMPLE_VARIANTS_DISABLED_422 = """
            {
              "code": "variants_disabled",
              "message": "Product options are not available on this marketplace yet"
            }""";

    /** The listing request with options, shared by the JSON create example and
     *  the multipart {@code listing} part. */
    private static final String EXAMPLE_VARIANT_CREATE_REQUEST = """
            {
              "title": "Cotton Crew Tee",
              "description": "100% cotton, pre-shrunk",
              "categoryCode": "other",
              "condition": "NEW",
              "city": "Harare",
              "area": "Avondale",
              "priceCents": 1999,
              "options": ["Size", "Colour"],
              "variants": [
                { "values": ["M", "Black"], "stockQty": 4 },
                { "values": ["L", "Black"], "stockQty": 0 },
                { "values": ["XL", "Black"], "priceCents": 2299, "stockQty": 6 }
              ],
              "deliveryTowns": [
                { "townCode": "harare", "feeCents": 300 }
              ]
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
                    + "merchantId different from their own claim is refused with 422.\n\n"
                    + "**Options (V19).** A product sold in sizes or colours sends `options` (1-2 "
                    + "axis names, e.g. `[\"Size\", \"Colour\"]`) and `variants` (up to 50: one value "
                    + "per axis, each option's own `stockQty`, and an optional own `priceCents` that "
                    + "may only be ABOVE the listing's). `priceCents` must then be the CHEAPEST "
                    + "option's price - leave `priceCents` off the options that sell at it - and the "
                    + "top-level `stockQty` is ignored: the listing's stock is the options' total. "
                    + "Names are 1-30 characters, values 1-40, and neither may contain a comma. "
                    + "Without `variants`, `stockQty` is required (400 `stock_required`). A cell "
                    + "with options switched off refuses a create that has them (422 "
                    + "`variants_disabled`).",
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ListingCreateRequest.class),
                            examples = {
                                    @ExampleObject(name = "with options (sizes)",
                                            summary = "A listing sold in sizes - priceCents is the "
                                                    + "cheapest option, XL costs more",
                                            value = EXAMPLE_VARIANT_CREATE_REQUEST),
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
                                                      "stockQty": 120,
                                                      "deliveryTowns": [
                                                        { "townCode": "harare", "feeCents": 300 },
                                                        { "townCode": "bulawayo", "feeCents": 1200 }
                                                      ]
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
                    content = @Content(mediaType = "application/json", examples = {
                            @ExampleObject(name = "created", value = EXAMPLE_CREATED_201),
                            @ExampleObject(name = "created-with-options",
                                    value = EXAMPLE_VARIANT_CREATED_201)})),
            @ApiResponse(responseCode = "400", description = "Validation failed, unknown categoryCode, "
                    + "SUPER_ADMIN omitted the target merchantId, no stockQty for a listing without "
                    + "options, or options that break the editor's rules",
                    content = @Content(mediaType = "application/json", examples = {
                            @ExampleObject(name = "bean-validation", value = EXAMPLE_VALIDATION_400),
                            @ExampleObject(name = "title-empty-after-sanitization", value = EXAMPLE_TITLE_400),
                            @ExampleObject(name = "unknown-category", value = EXAMPLE_UNKNOWN_CATEGORY_400),
                            @ExampleObject(name = "unknown-town", value = EXAMPLE_UNKNOWN_TOWN_400),
                            @ExampleObject(name = "town-named-twice", value = EXAMPLE_DUPLICATE_TOWN_400),
                            @ExampleObject(name = "super-admin-without-merchant-id",
                                    value = EXAMPLE_MERCHANT_ID_REQUIRED_400),
                            @ExampleObject(name = "stock-required", value = EXAMPLE_STOCK_REQUIRED_400),
                            @ExampleObject(name = "options-without-variants",
                                    value = EXAMPLE_OPTIONS_WITHOUT_VARIANTS_400),
                            @ExampleObject(name = "invalid-variant-option",
                                    value = EXAMPLE_INVALID_VARIANT_OPTION_400),
                            @ExampleObject(name = "variant-values-mismatch",
                                    value = EXAMPLE_VARIANT_VALUES_MISMATCH_400),
                            @ExampleObject(name = "duplicate-variant", value = EXAMPLE_DUPLICATE_VARIANT_400),
                            @ExampleObject(name = "variant-stock-required",
                                    value = EXAMPLE_VARIANT_STOCK_REQUIRED_400),
                            @ExampleObject(name = "variant-price-below-listing-price",
                                    value = EXAMPLE_VARIANT_PRICE_BELOW_400),
                            @ExampleObject(name = "listing-price-not-offered",
                                    value = EXAMPLE_LISTING_PRICE_NOT_OFFERED_400)})),
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
                    + "not their own, or options were sent while this cell has them switched off",
                    content = @Content(mediaType = "application/json", examples = {
                            @ExampleObject(name = "merchant-scope-mismatch",
                                    value = EXAMPLE_SCOPE_MISMATCH_422),
                            @ExampleObject(name = "variants-disabled",
                                    value = EXAMPLE_VARIANTS_DISABLED_422)}))
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
                    - `listing` — JSON body matching the plain create request, \
                    including `options` + `variants` for a product sold in sizes or \
                    colours (same rules as the JSON create).
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
                    content = @Content(mediaType = "application/json", examples = {
                            @ExampleObject(name = "created", value = EXAMPLE_CREATED_201),
                            @ExampleObject(name = "created-with-options-and-photo",
                                    value = EXAMPLE_VARIANT_CREATED_WITH_IMAGE_201)})),
            @ApiResponse(responseCode = "400", description = "Validation failed, bad image "
                    + "(unsupported_image_type / image_too_large / image_required), more than 9 "
                    + "additional images, unknown categoryCode, SUPER_ADMIN without merchantId, no "
                    + "stockQty for a listing without options, or options that break the editor's "
                    + "rules",
                    content = @Content(mediaType = "application/json", examples = {
                            @ExampleObject(name = "unsupported-image", value = EXAMPLE_UNSUPPORTED_IMAGE_400),
                            @ExampleObject(name = "too-many-images", value = EXAMPLE_TOO_MANY_IMAGES_400),
                            @ExampleObject(name = "unknown-category", value = EXAMPLE_UNKNOWN_CATEGORY_400),
                            @ExampleObject(name = "merchant-id-required",
                                    value = EXAMPLE_MERCHANT_ID_REQUIRED_400),
                            @ExampleObject(name = "stock-required", value = EXAMPLE_STOCK_REQUIRED_400),
                            @ExampleObject(name = "invalid-variant-option",
                                    value = EXAMPLE_INVALID_VARIANT_OPTION_400),
                            @ExampleObject(name = "duplicate-variant", value = EXAMPLE_DUPLICATE_VARIANT_400),
                            @ExampleObject(name = "variant-price-below-listing-price",
                                    value = EXAMPLE_VARIANT_PRICE_BELOW_400),
                            @ExampleObject(name = "listing-price-not-offered",
                                    value = EXAMPLE_LISTING_PRICE_NOT_OFFERED_400)})),
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
            @ApiResponse(responseCode = "422", description = "MERCHANT_ADMIN sent a foreign merchantId, "
                    + "or options were sent while this cell has them switched off",
                    content = @Content(mediaType = "application/json", examples = {
                            @ExampleObject(name = "merchant-scope-mismatch",
                                    value = EXAMPLE_SCOPE_MISMATCH_422),
                            @ExampleObject(name = "variants-disabled",
                                    value = EXAMPLE_VARIANTS_DISABLED_422)}))
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
                + "from your JWT (SUPER_ADMIN on-behalf creation adds it). The example sells in "
                + "sizes (`options` + `variants`); a listing without options sends `stockQty` "
                + "instead.",
                implementation = ListingCreateRequest.class,
                example = EXAMPLE_VARIANT_CREATE_REQUEST)
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
                    + "must own the listing.\n\n"
                    + "**Two fields are exceptions to full replace - OMIT them (or send null) to "
                    + "keep what is there:**\n\n"
                    + "- **`deliveryTowns`**: omit to keep the towns as they are; send a list to "
                    + "replace them; send `[]` to make the listing collection-only. Orders already "
                    + "placed keep the fee they were quoted.\n"
                    + "- **`variants`** (with `options`, V19): omit to keep every option exactly as "
                    + "it is, stock included (a new `priceCents` is still checked against the "
                    + "options' own prices); send `[]` to remove every option - the listing goes "
                    + "back to one stock count, so `stockQty` is then required; send a list to "
                    + "REPLACE the set. In a list, an entry with an `id` keeps that option (400 "
                    + "`unknown_variant` if it is not one of this listing's), an entry without one "
                    + "keeps the existing option with the same values, anything else is new, and "
                    + "options nobody names are removed. A kept entry that omits `stockQty` keeps "
                    + "its stock; a new one must send it. Keeping an `id` while changing its values "
                    + "moves every shopper's cart line to the new values - use that to fix a typo, "
                    + "and add a NEW entry for a different size.\n\n"
                    + "On a listing with options the top-level `stockQty` is ignored; restock one "
                    + "size with `PATCH /marketplace/listings/{id}/variants/{variantId}/stock`. "
                    + "Turning a listing without options INTO one with them is refused while this "
                    + "cell has options switched off (422 `variants_disabled`); editing a listing "
                    + "that already has options, or removing them with `[]`, is always allowed.",
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ListingUpdateRequest.class),
                            examples = {
                                    @ExampleObject(name = "listing without options",
                                            summary = "New price and stock; deliveryTowns omitted, "
                                                    + "so the towns are kept",
                                            value = """
                                                    {
                                                      "title": "Wireless Bluetooth Speaker",
                                                      "description": "Portable speaker with 12h battery life.",
                                                      "categoryCode": "tv-audio",
                                                      "condition": "NEW",
                                                      "city": "Harare",
                                                      "area": "Avondale",
                                                      "priceCents": 2399,
                                                      "stockQty": 150
                                                    }"""),
                                    @ExampleObject(name = "with options, kept by id",
                                            summary = "Every option named by id and no stockQty "
                                                    + "sent, so each keeps its stock",
                                            value = """
                                                    {
                                                      "title": "Cotton Crew Tee",
                                                      "description": "100% cotton, pre-shrunk",
                                                      "categoryCode": "other",
                                                      "condition": "NEW",
                                                      "city": "Harare",
                                                      "area": "Avondale",
                                                      "priceCents": 1999,
                                                      "options": ["Size", "Colour"],
                                                      "variants": [
                                                        { "id": "0a6f2d18-5c3b-4e97-8d21-b4f7e9c1a352",
                                                          "values": ["M", "Black"] },
                                                        { "id": "1b7e3e29-6d4c-4fa8-9e32-c5a8f0d2b463",
                                                          "values": ["L", "Black"] },
                                                        { "id": "2c8f4f3a-7e5d-40b9-af43-d6b9a1e3c574",
                                                          "values": ["XL", "Black"], "priceCents": 2299 }
                                                      ]
                                                    }"""),
                                    @ExampleObject(name = "remove every option",
                                            summary = "variants: [] - back to one stock count, so "
                                                    + "stockQty is required",
                                            value = """
                                                    {
                                                      "title": "Cotton Crew Tee",
                                                      "description": "100% cotton, pre-shrunk",
                                                      "categoryCode": "other",
                                                      "condition": "NEW",
                                                      "city": "Harare",
                                                      "area": "Avondale",
                                                      "priceCents": 1999,
                                                      "stockQty": 10,
                                                      "variants": []
                                                    }""")
                            })))
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Listing updated",
                    content = @Content(mediaType = "application/json", examples = {
                            @ExampleObject(name = "updated", value = EXAMPLE_UPDATED_200),
                            @ExampleObject(name = "updated-with-options",
                                    value = EXAMPLE_VARIANT_UPDATED_200)})),
            @ApiResponse(responseCode = "400", description = "Validation failed, unknown categoryCode, "
                    + "malformed id, no stockQty for a listing without options, or options that "
                    + "break the editor's rules",
                    content = @Content(mediaType = "application/json", examples = {
                            @ExampleObject(name = "bean-validation", value = EXAMPLE_VALIDATION_400),
                            @ExampleObject(name = "title-empty-after-sanitization", value = EXAMPLE_TITLE_400),
                            @ExampleObject(name = "unknown-category", value = EXAMPLE_UNKNOWN_CATEGORY_400),
                            @ExampleObject(name = "unknown-town", value = EXAMPLE_UNKNOWN_TOWN_400),
                            @ExampleObject(name = "town-named-twice", value = EXAMPLE_DUPLICATE_TOWN_400),
                            @ExampleObject(name = "invalid-id", value = EXAMPLE_INVALID_ID_400),
                            @ExampleObject(name = "stock-required", value = EXAMPLE_STOCK_REQUIRED_400),
                            @ExampleObject(name = "options-without-variants",
                                    value = EXAMPLE_OPTIONS_WITHOUT_VARIANTS_400),
                            @ExampleObject(name = "invalid-variant-option",
                                    value = EXAMPLE_INVALID_VARIANT_OPTION_400),
                            @ExampleObject(name = "duplicate-variant", value = EXAMPLE_DUPLICATE_VARIANT_400),
                            @ExampleObject(name = "unknown-variant", value = EXAMPLE_UNKNOWN_VARIANT_400),
                            @ExampleObject(name = "variant-price-below-listing-price",
                                    value = EXAMPLE_VARIANT_PRICE_BELOW_400),
                            @ExampleObject(name = "kept-option-below-new-price",
                                    value = EXAMPLE_KEPT_PRICE_BELOW_400),
                            @ExampleObject(name = "listing-price-not-offered",
                                    value = EXAMPLE_LISTING_PRICE_NOT_OFFERED_400)})),
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
            @ApiResponse(responseCode = "422", description = "Options sent for a listing without "
                    + "them while this cell has options switched off",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(name = "variants-disabled",
                                    value = EXAMPLE_VARIANTS_DISABLED_422)))
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

    @Operation(summary = "Set ONE option's stock (quick restock)",
            description = "V19. The absolute stock of one option of a listing with options - "
                    + "\"size M is back to 12\" - leaving every other option, and the orders "
                    + "already holding units of them, alone. The listing's `stockQty` (the total) "
                    + "follows. Favourites are told when the listing comes back from 0. Allowed "
                    + "even while the cell's options switch is off. Owner or SUPER_ADMIN.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Set; the whole listing comes back",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(name = "restocked",
                                    value = EXAMPLE_VARIANT_LISTING_200))),
            @ApiResponse(responseCode = "400", description = "Malformed listing or option id, a "
                    + "missing or out-of-range stockQty, or a count that would take the options' "
                    + "total over 1000000",
                    content = @Content(mediaType = "application/json", examples = {
                            @ExampleObject(name = "invalid-id", value = EXAMPLE_INVALID_ID_400),
                            @ExampleObject(name = "invalid-variant-id", value = """
                                    {"code":"invalid_variant_id","message":"Variant id must be a UUID"}"""),
                            @ExampleObject(name = "stock-out-of-range", value = """
                                    {"code":"VALIDATION_ERROR","message":"Request validation failed","data":{"stockQty":"must be less than or equal to 1000000"}}"""),
                            @ExampleObject(name = "total-over-the-cap", value = """
                                    {"code":"stock_out_of_range","message":"The options' stock adds up to more than 1000000"}""")})),
            @ApiResponse(responseCode = "401", description = "Missing/invalid token",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(name = "unauthorized", value = EXAMPLE_401))),
            @ApiResponse(responseCode = "403", description = "Wrong role, no merchant scope, or not the owner",
                    content = @Content(mediaType = "application/json", examples = {
                            @ExampleObject(name = "insufficient-role", value = EXAMPLE_ROLE_403),
                            @ExampleObject(name = "merchant-scope-missing", value = EXAMPLE_SCOPE_403),
                            @ExampleObject(name = "not-owned", value = EXAMPLE_NOT_OWNED_403)})),
            @ApiResponse(responseCode = "404", description = "No such listing, or no such option of it",
                    content = @Content(mediaType = "application/json", examples = {
                            @ExampleObject(name = "listing-not-found", value = EXAMPLE_NOT_FOUND_404),
                            @ExampleObject(name = "variant-not-found", value = """
                                    {"code":"variant_not_found","message":"Variant not found"}""")}))
    })
    @PatchMapping("/{id}/variants/{variantId}/stock")
    public ApiResult<ListingResponse> setVariantStock(
            @Parameter(description = "Listing id", example = "e3a91c57-2b4d-4f8e-9a16-7c5d0b2e8f41",
                    schema = @Schema(type = "string", format = "uuid"))
            @PathVariable("id") String id,
            @Parameter(description = "Option id (one of the listing's `variants[].id`)",
                    example = "1b7e3e29-6d4c-4fa8-9e32-c5a8f0d2b463",
                    schema = @Schema(type = "string", format = "uuid"))
            @PathVariable("variantId") String variantId,
            @Valid @RequestBody VariantStockRequest request) {
        return ApiResult.ok(listingService.setVariantStock(CurrentUser.get(), parseListingId(id),
                parseVariantId(variantId), request.stockQty()));
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

    private static UUID parseVariantId(String raw) {
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException ex) {
            throw ApiException.badRequest("invalid_variant_id", "Variant id must be a UUID");
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
