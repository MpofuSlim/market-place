package com.innbucks.marketplaceservice.fulfilment.tracking;

import com.innbucks.marketplaceservice.api.ApiException;
import com.innbucks.marketplaceservice.api.ApiResult;
import com.innbucks.marketplaceservice.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * The courier's screen: the selling organization's deliveries on the road, and
 * the position reports that feed the buyer's map.
 *
 * <p>{@code COURIER} is derived by {@code JwtFilter} for ANY member of an
 * organization that sells on the marketplace — OWNER, ADMIN or STAFF — so a
 * driver needs a staff seat on the business, not a seller's authority. What
 * they can do here is deliberately narrow: read the run (no money on it) and
 * report where they are. Dispatching and closing parcels stay on
 * {@code /marketplace/fulfilments}, a seller's action.
 */
@Tag(name = "Deliveries",
        description = "For the person driving. Any member of a selling business (OWNER, ADMIN or "
                + "STAFF) sees that business's DELIVERY parcels that are on the road, and reports "
                + "the phone's position so the buyer can watch the parcel come.\n\n"
                + "Built for a phone browser: read `navigator.geolocation.watchPosition` and POST "
                + "each fix to `/{fulfilmentId}/location`. Only the LATEST position is kept — no "
                + "route history.")
@RestController
@RequestMapping("/marketplace/deliveries")
@RequiredArgsConstructor
@PreAuthorize("hasRole('COURIER')")
public class CourierController {

    private final ParcelTrackingService trackingService;

    private static final String EXAMPLE_SCOPE_403 = """
            {
              "code": "courier_scope_missing",
              "message": "Sign in with a business that sells on the marketplace to deliver its parcels"
            }""";

    private static final String EXAMPLE_NOT_FOUND_404 = """
            {
              "code": "fulfilment_not_found",
              "message": "Fulfilment not found"
            }""";

    @GetMapping
    @Operation(summary = "My delivery run",
            description = "This business's DELIVERY parcels that are DISPATCHED — on the road — "
                    + "longest out first. Each carries where it goes, who to ring and what to hand "
                    + "over. **No prices, subtotals or settlement**: a driver does not need them.\n\n"
                    + "Collection parcels never appear here (nothing is driven anywhere), and a "
                    + "parcel leaves the run the moment it is delivered or cancelled.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The run",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    {
                                      "code": "OK",
                                      "message": "Success",
                                      "data": [
                                        {
                                          "fulfilmentId": "3a7b19e4-8c25-4f6d-b019-5e2c7a4d8f31",
                                          "trackingCode": "TRK-7F3K9Q2M4X",
                                          "orderRef": "MKT-4F9A1C22B7D3",
                                          "trackingStatus": "DISPATCHED",
                                          "dispatchedAt": "2026-09-24T09:20:00Z",
                                          "destination": {
                                            "recipientName": "Tariro Moyo",
                                            "recipientMsisdn": "+263771234567",
                                            "line1": "14 Samora Machel Ave",
                                            "line2": "Flat 3B",
                                            "city": "Harare",
                                            "area": "Avondale",
                                            "landmark": "Opposite the clinic, blue gate"
                                          },
                                          "items": [
                                            { "title": "Wireless Bluetooth Speaker", "quantity": 2 }
                                          ],
                                          "lastLocationAt": "2026-09-24T12:14:05Z"
                                        }
                                      ]
                                    }"""))),
            @ApiResponse(responseCode = "403", description = "Not a member of a business that "
                    + "sells on the marketplace",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = EXAMPLE_SCOPE_403)))
    })
    public ResponseEntity<ApiResult<List<CourierParcelResponse>>> run(
            @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Page size, at most 100")
            @RequestParam(defaultValue = "50") int size) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(ApiResult.ok(trackingService.courierRun(CurrentUser.get(), page, size)));
    }

    @PostMapping("/{fulfilmentId}/location")
    @Operation(summary = "Report where I am with a parcel",
            description = "Stores the courier's current position for one parcel; the buyer's "
                    + "tracking screen shows it on a map. Send `coords.latitude`, "
                    + "`coords.longitude`, `coords.accuracy` and the fix's `timestamp` from the "
                    + "browser's Geolocation API.\n\n"
                    + "**A report that is not stored is still a 200**, with `accepted: false`: "
                    + "one sent within a few seconds of the last, one older than the stored "
                    + "position (a phone catching up after losing signal), or one more than ten "
                    + "minutes old. Keep sending — there is nothing to retry and nothing to show "
                    + "the driver.\n\n"
                    + "Refused only when it can never succeed: not this business's parcel (404), "
                    + "not a delivery on the road (409 — collection parcels, and parcels not yet "
                    + "dispatched or already closed), or a position outside the country (422, "
                    + "usually a phone with no GPS fix yet). Stop reporting for a parcel on a 409.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Stored, or harmlessly ignored",
                    content = @Content(mediaType = "application/json",
                            examples = {
                                    @ExampleObject(name = "Stored", value = """
                                            {
                                              "code": "OK",
                                              "message": "Success",
                                              "data": { "accepted": true, "lastLocationAt": "2026-09-24T12:14:05Z" }
                                            }"""),
                                    @ExampleObject(name = "Ignored (too soon)", value = """
                                            {
                                              "code": "OK",
                                              "message": "Success",
                                              "data": { "accepted": false, "lastLocationAt": "2026-09-24T12:14:05Z" }
                                            }""")})),
            @ApiResponse(responseCode = "400", description = "Missing or impossible coordinates, "
                    + "or a malformed parcel id",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    {"code":"invalid_fulfilment_id","message":"Fulfilment id must be a UUID"}"""))),
            @ApiResponse(responseCode = "403", description = "Not a member of a business that "
                    + "sells on the marketplace",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = EXAMPLE_SCOPE_403))),
            @ApiResponse(responseCode = "404", description = "No such parcel for this business",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = EXAMPLE_NOT_FOUND_404))),
            @ApiResponse(responseCode = "409", description = "Not a delivery on the road",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    {"code":"parcel_not_in_transit","message":"Positions can only be reported for a delivery that is on its way"}"""))),
            @ApiResponse(responseCode = "422", description = "Outside the area this market "
                    + "delivers in",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    {"code":"location_out_of_bounds","message":"That position is outside the area we deliver in - check the phone's GPS"}""")))
    })
    public ResponseEntity<ApiResult<LocationPingResponse>> reportLocation(
            @Parameter(description = "The parcel, from the run",
                    example = "3a7b19e4-8c25-4f6d-b019-5e2c7a4d8f31")
            @PathVariable("fulfilmentId") String fulfilmentId,
            @Valid @RequestBody LocationPingRequest request) {
        return ResponseEntity.ok(ApiResult.ok(trackingService.recordLocation(CurrentUser.get(),
                parseFulfilmentId(fulfilmentId), request)));
    }

    /** A UUID-typed path variable would 500 on garbage — parse here and 400. */
    private static UUID parseFulfilmentId(String raw) {
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException ex) {
            throw ApiException.badRequest("invalid_fulfilment_id", "Fulfilment id must be a UUID");
        }
    }
}
