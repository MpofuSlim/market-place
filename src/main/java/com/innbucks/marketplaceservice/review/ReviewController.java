package com.innbucks.marketplaceservice.review;

import com.innbucks.marketplaceservice.api.ApiException;
import com.innbucks.marketplaceservice.api.ApiResult;
import com.innbucks.marketplaceservice.review.dto.ReviewRequest;
import com.innbucks.marketplaceservice.review.dto.ReviewResponse;
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
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Buyer-side review writes. Creating and editing is CUSTOMER-only (merchants
 * and admins cannot mint ratings); deleting additionally admits SUPER_ADMIN
 * for moderation removal. The verified-purchase gate lives in
 * {@link ReviewService}; public reads live on the catalog surface
 * ({@code PublicReviewController}).
 */
@Tag(name = "Reviews",
        description = "Verified-purchase reviews: only a CUSTOMER with a PAID order containing the "
                + "listing may review it, once per listing. Rating aggregates appear on every "
                + "listing read (ratingAvg/reviewCount); public review reads are on the catalog "
                + "surface. Any listing status is reviewable — a delisted product was still bought.")
@RestController
@RequestMapping("/marketplace/listings/{listingId}/reviews")
@RequiredArgsConstructor
public class ReviewController {

    private final ReviewService reviewService;

    private static final String EXAMPLE_REVIEW_201 = """
            {
              "code": "CREATED",
              "message": "Created",
              "data": {
                "id": "3a9d5c7e-1b2f-4a8c-9d6e-5f4a3b2c1d0e",
                "listingId": "b4c2f0a8-3d1e-4e5a-9c7b-2f8d6a1e4b93",
                "orderId": "b4a8e2d1-7c3f-4b5a-9e6d-2f1a8c7b5d4e",
                "rating": 5,
                "comment": "Great speaker, battery really does last all day.",
                "createdAt": "2026-08-06T10:15:00Z",
                "updatedAt": "2026-08-06T10:15:00Z"
              }
            }""";

    private static final String EXAMPLE_REVIEW_UPDATED_200 = """
            {
              "code": "OK",
              "message": "Success",
              "data": {
                "id": "3a9d5c7e-1b2f-4a8c-9d6e-5f4a3b2c1d0e",
                "listingId": "b4c2f0a8-3d1e-4e5a-9c7b-2f8d6a1e4b93",
                "orderId": "b4a8e2d1-7c3f-4b5a-9e6d-2f1a8c7b5d4e",
                "rating": 3,
                "comment": "Battery degraded after a month.",
                "createdAt": "2026-08-06T10:15:00Z",
                "updatedAt": "2026-08-06T11:40:00Z"
              }
            }""";

    private static final String EXAMPLE_401 = """
            {"code":"UNAUTHORIZED","message":"Invalid or missing token","data":null}""";

    private static final String EXAMPLE_ROLE_403 = """
            {"code":"FORBIDDEN","message":"Forbidden - insufficient role","data":null}""";

    private static final String EXAMPLE_LISTING_404 = """
            {"code":"listing_not_found","message":"Listing not found"}""";

    private static final String EXAMPLE_INVALID_ID_400 = """
            {"code":"invalid_listing_id","message":"Listing id must be a UUID"}""";

    @PostMapping
    @PreAuthorize("hasRole('CUSTOMER')")
    @Operation(summary = "Review a listing (verified purchase only)",
            description = "Creates the caller's review of the listing. THE gate: the caller must "
                    + "have a PAID order containing this listing — otherwise 403 "
                    + "review_requires_purchase. One review per buyer per listing (409 on repeat; "
                    + "edit via PUT /mine instead). The comment is HTML-stripped before storage. "
                    + "The listing's ratingAvg/reviewCount update atomically in the same "
                    + "transaction.",
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ReviewRequest.class),
                            examples = @ExampleObject(name = "Five stars", value = """
                                    {
                                      "rating": 5,
                                      "comment": "Great speaker, battery really does last all day."
                                    }
                                    """))))
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Review created; listing aggregates updated",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(name = "Created review", value = EXAMPLE_REVIEW_201))),
            @ApiResponse(responseCode = "400", description = "Malformed listing id, or rating out of range",
                    content = @Content(mediaType = "application/json", examples = {
                            @ExampleObject(name = "Invalid id", value = EXAMPLE_INVALID_ID_400),
                            @ExampleObject(name = "Invalid rating", value = """
                                    {"code":"invalid_rating","message":"rating must be between 1 and 5"}
                                    """)})),
            @ApiResponse(responseCode = "401", description = "Missing/invalid JWT",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = EXAMPLE_401))),
            @ApiResponse(responseCode = "403", description = "Not a CUSTOMER, or no qualifying paid order",
                    content = @Content(mediaType = "application/json", examples = {
                            @ExampleObject(name = "Not a customer", value = EXAMPLE_ROLE_403),
                            @ExampleObject(name = "No verified purchase", value = """
                                    {"code":"review_requires_purchase","message":"Only buyers with a paid order containing this listing may review it"}
                                    """)})),
            @ApiResponse(responseCode = "404", description = "Unknown listing id",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = EXAMPLE_LISTING_404))),
            @ApiResponse(responseCode = "409", description = "The caller already reviewed this listing",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    {"code":"review_already_exists","message":"You have already reviewed this listing"}
                                    """)))
    })
    public ResponseEntity<ApiResult<ReviewResponse>> create(
            @Parameter(description = "Listing id", example = "b4c2f0a8-3d1e-4e5a-9c7b-2f8d6a1e4b93",
                    schema = @Schema(type = "string", format = "uuid"))
            @PathVariable("listingId") String listingId,
            @Valid @RequestBody ReviewRequest request) {
        ReviewResponse response = reviewService.create(
                CurrentUser.get(), parseListingId(listingId), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResult.created(response));
    }

    @PutMapping("/mine")
    @PreAuthorize("hasRole('CUSTOMER')")
    @Operation(summary = "Edit my review of a listing",
            description = "Replaces the caller's OWN review (rating and comment). The listing's "
                    + "rating aggregates absorb the rating delta atomically. 404 when the caller "
                    + "has no review of this listing.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Review updated; aggregates adjusted",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(name = "Updated review",
                                    value = EXAMPLE_REVIEW_UPDATED_200))),
            @ApiResponse(responseCode = "400", description = "Malformed listing id, or rating out of range",
                    content = @Content(mediaType = "application/json", examples = {
                            @ExampleObject(name = "Invalid id", value = EXAMPLE_INVALID_ID_400),
                            @ExampleObject(name = "Invalid rating", value = """
                                    {"code":"invalid_rating","message":"rating must be between 1 and 5"}
                                    """)})),
            @ApiResponse(responseCode = "401", description = "Missing/invalid JWT",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = EXAMPLE_401))),
            @ApiResponse(responseCode = "403", description = "Authenticated but not CUSTOMER",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = EXAMPLE_ROLE_403))),
            @ApiResponse(responseCode = "404", description = "The caller has no review of this listing",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    {"code":"review_not_found","message":"You have no review of this listing"}
                                    """)))
    })
    public ResponseEntity<ApiResult<ReviewResponse>> updateMine(
            @Parameter(description = "Listing id", example = "b4c2f0a8-3d1e-4e5a-9c7b-2f8d6a1e4b93",
                    schema = @Schema(type = "string", format = "uuid"))
            @PathVariable("listingId") String listingId,
            @Valid @RequestBody ReviewRequest request) {
        return ResponseEntity.ok(ApiResult.ok(reviewService.updateMine(
                CurrentUser.get(), parseListingId(listingId), request)));
    }

    @DeleteMapping("/{reviewId}")
    @PreAuthorize("hasAnyRole('CUSTOMER','SUPER_ADMIN')")
    @Operation(summary = "Delete a review (author or SUPER_ADMIN)",
            description = "The review's AUTHOR removes their own review, or SUPER_ADMIN removes "
                    + "anyone's (moderation removal — audited with adminRemoval=true). The "
                    + "listing's rating aggregates decrement atomically.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Review deleted; aggregates decremented",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    {"code":"OK","message":"Review deleted"}
                                    """))),
            @ApiResponse(responseCode = "400", description = "Malformed listing or review id",
                    content = @Content(mediaType = "application/json", examples = {
                            @ExampleObject(name = "Invalid listing id", value = EXAMPLE_INVALID_ID_400),
                            @ExampleObject(name = "Invalid review id", value = """
                                    {"code":"invalid_review_id","message":"Review id must be a UUID"}
                                    """)})),
            @ApiResponse(responseCode = "401", description = "Missing/invalid JWT",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = EXAMPLE_401))),
            @ApiResponse(responseCode = "403", description = "Wrong role, or not the review's author",
                    content = @Content(mediaType = "application/json", examples = {
                            @ExampleObject(name = "Wrong role", value = EXAMPLE_ROLE_403),
                            @ExampleObject(name = "Not the author", value = """
                                    {"code":"review_not_owned","message":"Only the review's author or an administrator may delete it"}
                                    """)})),
            @ApiResponse(responseCode = "404", description = "The reviewId is not a review of this listing",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    {"code":"review_not_found","message":"No such review for this listing"}
                                    """)))
    })
    public ResponseEntity<ApiResult<Void>> delete(
            @Parameter(description = "Listing id", example = "b4c2f0a8-3d1e-4e5a-9c7b-2f8d6a1e4b93",
                    schema = @Schema(type = "string", format = "uuid"))
            @PathVariable("listingId") String listingId,
            @Parameter(description = "Review id", example = "3a9d5c7e-1b2f-4a8c-9d6e-5f4a3b2c1d0e",
                    schema = @Schema(type = "string", format = "uuid"))
            @PathVariable("reviewId") String reviewId) {
        reviewService.delete(CurrentUser.get(), parseListingId(listingId), parseReviewId(reviewId));
        return ResponseEntity.ok(ApiResult.ok("Review deleted", null));
    }

    /** GlobalExceptionHandler has no MethodArgumentTypeMismatch mapping, so a
     *  typed UUID @PathVariable would 500 on garbage — parse here and 400. */
    private static UUID parseListingId(String raw) {
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException ex) {
            throw ApiException.badRequest("invalid_listing_id", "Listing id must be a UUID");
        }
    }

    private static UUID parseReviewId(String raw) {
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException ex) {
            throw ApiException.badRequest("invalid_review_id", "Review id must be a UUID");
        }
    }
}
