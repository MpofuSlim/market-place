package com.innbucks.marketplaceservice.report;

import com.innbucks.marketplaceservice.api.ApiException;
import com.innbucks.marketplaceservice.api.ApiResult;
import com.innbucks.marketplaceservice.report.dto.ReportRequest;
import com.innbucks.marketplaceservice.report.dto.ReportResponse;
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
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Filing a listing report. Lives under {@code /marketplace/catalog/**} (report
 * FROM the catalog page), but the SecurityConfig permitAll on that prefix is
 * GET-scoped — this POST rides {@code anyRequest().authenticated()}, so an
 * anonymous report is a 401 (pinned by SecuritySurfaceIT). Any authenticated
 * role may file — spam control is the auth requirement plus the one-open-
 * report-per-user-per-listing rule.
 */
@Tag(name = "Reports",
        description = "Flag a listing to the SUPER_ADMIN moderation queue. Requires any "
                + "authenticated fleet token (never anonymous — spam control); one OPEN report "
                + "per user per listing. Moderation itself lives under /marketplace/reports.")
@RestController
@RequiredArgsConstructor
public class ReportController {

    private final ReportService reportService;

    private static final String EXAMPLE_REPORT_201 = """
            {
              "code": "CREATED",
              "message": "Created",
              "data": {
                "id": "8c1d2e3f-4a5b-4c6d-8e9f-0a1b2c3d4e5f",
                "listingId": "b4c2f0a8-3d1e-4e5a-9c7b-2f8d6a1e4b93",
                "listingTitle": "Wireless Bluetooth Speaker",
                "merchantId": "7e2a9c41-5b8f-4d36-a1c9-8f3b6d2e7a54",
                "listingStatus": "ACTIVE",
                "reporterUuid": "6f9619ff-8b86-4011-b42d-00c04fc964ff",
                "reason": "COUNTERFEIT",
                "detail": "Logo is fake — the brand does not make this model.",
                "status": "OPEN",
                "resolvedBy": null,
                "resolutionNote": null,
                "createdAt": "2026-08-06T12:00:00Z",
                "resolvedAt": null
              }
            }""";

    @PostMapping("/marketplace/catalog/{listingId}/report")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Report a listing",
            description = "Files a report against the listing for the moderation queue. Any "
                    + "authenticated user may report (CUSTOMER or MERCHANT_ADMIN — anonymous "
                    + "callers get 401); each user may have ONE open report per listing at a time "
                    + "(409 on repeat until moderation closes it). The detail text is "
                    + "HTML-stripped before storage.",
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ReportRequest.class),
                            examples = @ExampleObject(name = "Counterfeit report", value = """
                                    {
                                      "reason": "COUNTERFEIT",
                                      "detail": "Logo is fake — the brand does not make this model."
                                    }
                                    """))))
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Report filed to the moderation queue",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(name = "Filed report", value = EXAMPLE_REPORT_201))),
            @ApiResponse(responseCode = "400", description = "Malformed listing id, or an unknown reason",
                    content = @Content(mediaType = "application/json", examples = {
                            @ExampleObject(name = "Invalid id", value = """
                                    {"code":"invalid_listing_id","message":"Listing id must be a UUID"}
                                    """),
                            @ExampleObject(name = "Unknown reason", value = """
                                    {"code":"MALFORMED_REQUEST","message":"Request body is malformed"}
                                    """)})),
            @ApiResponse(responseCode = "401", description = "Missing/invalid JWT (reporting is never anonymous)",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    {"code":"UNAUTHORIZED","message":"Invalid or missing token","data":null}
                                    """))),
            @ApiResponse(responseCode = "404", description = "Unknown listing id",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    {"code":"listing_not_found","message":"Listing not found"}
                                    """))),
            @ApiResponse(responseCode = "409", description = "The caller already has an open report "
                    + "for this listing",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    {"code":"report_already_open","message":"You already have an open report for this listing"}
                                    """)))
    })
    public ResponseEntity<ApiResult<ReportResponse>> report(
            @Parameter(description = "Listing id", example = "b4c2f0a8-3d1e-4e5a-9c7b-2f8d6a1e4b93",
                    schema = @Schema(type = "string", format = "uuid"))
            @PathVariable("listingId") String listingId,
            @Valid @RequestBody ReportRequest request) {
        ReportResponse response = reportService.report(
                CurrentUser.get(), parseListingId(listingId), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResult.created(response));
    }

    /** Manual parse — GlobalExceptionHandler has no MethodArgumentTypeMismatch
     *  mapping, so a typed UUID @PathVariable would 500 on garbage. */
    private static UUID parseListingId(String raw) {
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException ex) {
            throw ApiException.badRequest("invalid_listing_id", "Listing id must be a UUID");
        }
    }
}
