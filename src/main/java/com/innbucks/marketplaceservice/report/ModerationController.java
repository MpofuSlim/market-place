package com.innbucks.marketplaceservice.report;

import com.innbucks.marketplaceservice.api.ApiException;
import com.innbucks.marketplaceservice.api.ApiResult;
import com.innbucks.marketplaceservice.report.dto.ReportPageResponse;
import com.innbucks.marketplaceservice.report.dto.ReportResolutionRequest;
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
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Locale;
import java.util.UUID;

/**
 * SUPER_ADMIN moderation queue over listing reports. Sits under
 * {@code /marketplace/reports} — inside the fleet gateway's existing
 * {@code /marketplace/**} route, so no gateway change is needed (and it is
 * NOT an internal S2S surface: the user JWT + role gate protect it, exactly
 * like the SUPER_ADMIN order reads).
 */
@Tag(name = "Moderation (SUPER_ADMIN)",
        description = "Fleet-oversight queue over listing reports: list by status (default OPEN, "
                + "oldest first — FIFO) and close reports as RESOLVED or DISMISSED. Resolving may "
                + "also deactivate the reported listing.")
@RestController
@RequestMapping("/marketplace/reports")
@RequiredArgsConstructor
@PreAuthorize("hasRole('SUPER_ADMIN')")
public class ModerationController {

    private final ReportService reportService;

    private static final String EXAMPLE_QUEUE_200 = """
            {
              "code": "OK",
              "message": "Success",
              "data": {
                "items": [
                  {
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
                ],
                "page": 0,
                "size": 20,
                "totalItems": 1,
                "totalPages": 1
              }
            }""";

    private static final String EXAMPLE_RESOLVED_200 = """
            {
              "code": "OK",
              "message": "Success",
              "data": {
                "id": "8c1d2e3f-4a5b-4c6d-8e9f-0a1b2c3d4e5f",
                "listingId": "b4c2f0a8-3d1e-4e5a-9c7b-2f8d6a1e4b93",
                "listingTitle": "Wireless Bluetooth Speaker",
                "merchantId": "7e2a9c41-5b8f-4d36-a1c9-8f3b6d2e7a54",
                "listingStatus": "INACTIVE",
                "reporterUuid": "6f9619ff-8b86-4011-b42d-00c04fc964ff",
                "reason": "COUNTERFEIT",
                "detail": "Logo is fake — the brand does not make this model.",
                "status": "RESOLVED",
                "resolvedBy": "0a1b2c3d-4e5f-4a6b-8c9d-e0f1a2b3c4d5",
                "resolutionNote": "Confirmed counterfeit; listing deactivated.",
                "createdAt": "2026-08-06T12:00:00Z",
                "resolvedAt": "2026-08-06T14:30:00Z"
              }
            }""";

    private static final String EXAMPLE_401 = """
            {"code":"UNAUTHORIZED","message":"Invalid or missing token","data":null}""";

    private static final String EXAMPLE_ROLE_403 = """
            {"code":"FORBIDDEN","message":"Forbidden - insufficient role","data":null}""";

    @GetMapping
    @Operation(summary = "List reports (moderation queue)",
            description = "Reports filtered by ?status= (OPEN — the default — RESOLVED or "
                    + "DISMISSED), OLDEST first: the queue is worked FIFO so the oldest complaint "
                    + "never starves. Rows carry a live summary of the reported listing "
                    + "(title/merchant/current status). Page size is clamped to 50.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "One page of reports",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(name = "queue", value = EXAMPLE_QUEUE_200))),
            @ApiResponse(responseCode = "400", description = "status outside the enum",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    {"code":"invalid_status","message":"status must be one of OPEN, RESOLVED, DISMISSED"}
                                    """))),
            @ApiResponse(responseCode = "401", description = "Missing/invalid JWT",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = EXAMPLE_401))),
            @ApiResponse(responseCode = "403", description = "Authenticated but not SUPER_ADMIN",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = EXAMPLE_ROLE_403)))
    })
    public ApiResult<ReportPageResponse> queue(
            @Parameter(description = "Status filter", example = "OPEN",
                    schema = @Schema(implementation = ReportStatus.class, defaultValue = "OPEN"))
            @RequestParam(value = "status", defaultValue = "OPEN") String status,
            @Parameter(description = "Zero-based page index",
                    schema = @Schema(type = "integer", defaultValue = "0"))
            @RequestParam(value = "page", defaultValue = "0") String page,
            @Parameter(description = "Page size (clamped to 50)",
                    schema = @Schema(type = "integer", defaultValue = "20"))
            @RequestParam(value = "size", defaultValue = "20") String size) {
        return ApiResult.ok(reportService.queue(parseStatus(status),
                intParam(page, 0), intParam(size, 20)));
    }

    @PatchMapping("/{id}")
    @Operation(summary = "Close a report (resolve or dismiss)",
            description = "Closes an OPEN report as RESOLVED (founded) or DISMISSED (unfounded). "
                    + "With action RESOLVE, deactivateListing=true additionally sets the reported "
                    + "listing INACTIVE (always allowed — only activation is publish-gated) and "
                    + "audits the status change alongside the resolution. Closed reports are "
                    + "terminal: any further PATCH is 409 report_not_open.",
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ReportResolutionRequest.class),
                            examples = {
                                    @ExampleObject(name = "Resolve and deactivate", value = """
                                            {
                                              "action": "RESOLVE",
                                              "resolutionNote": "Confirmed counterfeit; listing deactivated.",
                                              "deactivateListing": true
                                            }
                                            """),
                                    @ExampleObject(name = "Dismiss", value = """
                                            {
                                              "action": "DISMISS",
                                              "resolutionNote": "Genuine product — reporter mistaken."
                                            }
                                            """)})))
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Report closed (listing deactivated when requested)",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(name = "Resolved report",
                                    value = EXAMPLE_RESOLVED_200))),
            @ApiResponse(responseCode = "400", description = "Malformed id, or deactivateListing on a DISMISS",
                    content = @Content(mediaType = "application/json", examples = {
                            @ExampleObject(name = "Invalid id", value = """
                                    {"code":"invalid_report_id","message":"Report id must be a UUID"}
                                    """),
                            @ExampleObject(name = "Deactivate on dismiss", value = """
                                    {"code":"deactivate_requires_resolve","message":"deactivateListing is only valid with action RESOLVE"}
                                    """)})),
            @ApiResponse(responseCode = "401", description = "Missing/invalid JWT",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = EXAMPLE_401))),
            @ApiResponse(responseCode = "403", description = "Authenticated but not SUPER_ADMIN",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = EXAMPLE_ROLE_403))),
            @ApiResponse(responseCode = "404", description = "Unknown report id",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    {"code":"report_not_found","message":"Report not found"}
                                    """))),
            @ApiResponse(responseCode = "409", description = "The report is already closed",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    {"code":"report_not_open","message":"Report 8c1d2e3f-4a5b-4c6d-8e9f-0a1b2c3d4e5f is already RESOLVED"}
                                    """)))
    })
    public ApiResult<ReportResponse> resolve(
            @Parameter(description = "Report id", example = "8c1d2e3f-4a5b-4c6d-8e9f-0a1b2c3d4e5f",
                    schema = @Schema(type = "string", format = "uuid"))
            @PathVariable("id") String id,
            @Valid @RequestBody ReportResolutionRequest request) {
        return ApiResult.ok(reportService.resolve(CurrentUser.get(), parseReportId(id), request));
    }

    /** Manual parses — GlobalExceptionHandler has no MethodArgumentTypeMismatch
     *  mapping, so typed @PathVariable/@RequestParam would 500 on garbage. */
    private static UUID parseReportId(String raw) {
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException ex) {
            throw ApiException.badRequest("invalid_report_id", "Report id must be a UUID");
        }
    }

    private static ReportStatus parseStatus(String raw) {
        try {
            return ReportStatus.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw ApiException.badRequest("invalid_status",
                    "status must be one of OPEN, RESOLVED, DISMISSED");
        }
    }

    private static int intParam(String raw, int fallback) {
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }
}
