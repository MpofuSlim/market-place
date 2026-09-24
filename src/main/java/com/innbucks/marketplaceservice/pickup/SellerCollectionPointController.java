package com.innbucks.marketplaceservice.pickup;

import com.innbucks.marketplaceservice.api.ApiException;
import com.innbucks.marketplaceservice.api.ApiResult;
import com.innbucks.marketplaceservice.pickup.dto.CollectionPointRequest;
import com.innbucks.marketplaceservice.pickup.dto.CollectionPointResponse;
import com.innbucks.marketplaceservice.security.AuthenticatedUser;
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
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * A seller's own collection points — where buyers come for their goods.
 *
 * <p>Scoped by SHAPE, like the payout destination: no path or query parameter
 * names a merchant, the subject is always the caller's own {@code merchantId}
 * claim, and another seller's point is the same 404 as a missing one. An
 * operator acting for a seller uses
 * {@code /marketplace/admin/sellers/{merchantId}/collection-points}.
 *
 * <p>Deliberately NOT under {@code /marketplace/catalog/**}: that prefix is
 * anonymous for GET, and a seller's management view is not public. Buyers see
 * points on the public seller profile instead.
 */
@Tag(name = "Seller collection points",
        description = "Where buyers collect your goods: up to 10 points, each in a town from "
                + "GET /marketplace/delivery-towns, with an address, optional weekly hours and an "
                + "optional map pin. Every listing of yours is collectable at every point. Exactly "
                + "one is your DEFAULT — where a buyer who does not choose collects; the first "
                + "point you add becomes it.\n\n"
                + "Orders keep a copy of the point chosen when they were placed, so editing or "
                + "removing a point never moves a collection a buyer has already been told about.")
@RestController
@RequestMapping("/marketplace/sellers/me/collection-points")
@RequiredArgsConstructor
@PreAuthorize("hasRole('MERCHANT_ADMIN')")
public class SellerCollectionPointController {

    static final String EXAMPLE_POINT = """
            {
              "id": "5c1d8e2a-3b4f-4a6d-9e7c-2f8a1b3c4d5e",
              "name": "Avondale shop",
              "townCode": "harare",
              "townName": "Harare",
              "line1": "14 Samora Machel Ave",
              "line2": "Shop 3, Avondale Shopping Centre",
              "area": "Avondale",
              "landmark": "Next to the pharmacy",
              "phone": "+263242123456",
              "hoursNote": "Closed on public holidays",
              "latitude": -17.798500,
              "longitude": 31.045200,
              "openingHours": [
                { "day": "MONDAY", "opens": "08:00", "closes": "17:00" },
                { "day": "TUESDAY", "opens": "08:00", "closes": "17:00" },
                { "day": "WEDNESDAY", "opens": "08:00", "closes": "17:00" },
                { "day": "THURSDAY", "opens": "08:00", "closes": "17:00" },
                { "day": "FRIDAY", "opens": "08:00", "closes": "17:00" },
                { "day": "SATURDAY", "opens": "08:00", "closes": "13:00" }
              ],
              "openingHoursSummary": "Mon-Fri 08:00-17:00, Sat 08:00-13:00",
              "openNow": true,
              "defaultPoint": true,
              "updatedAt": "2026-09-24T08:10:22Z"
            }""";

    static final String EXAMPLE_REQUEST = """
            {
              "name": "Avondale shop",
              "townCode": "harare",
              "line1": "14 Samora Machel Ave",
              "line2": "Shop 3, Avondale Shopping Centre",
              "area": "Avondale",
              "landmark": "Next to the pharmacy",
              "phone": "0242123456",
              "hoursNote": "Closed on public holidays",
              "latitude": -17.7985,
              "longitude": 31.0452,
              "openingHours": [
                { "day": "MONDAY", "opens": "08:00", "closes": "17:00" },
                { "day": "SATURDAY", "opens": "08:00", "closes": "13:00" }
              ]
            }""";

    static final String EXAMPLE_LIST_200 = "{\n  \"code\": \"OK\",\n  \"message\": \"Success\",\n"
            + "  \"data\": [" + EXAMPLE_POINT + "]\n}";

    static final String EXAMPLE_ONE_200 = "{\n  \"code\": \"OK\",\n  \"message\": "
            + "\"Collection point saved\",\n  \"data\": " + EXAMPLE_POINT + "\n}";

    static final String EXAMPLE_NOT_FOUND_404 = """
            {"code":"collection_point_not_found","message":"Collection point not found"}""";

    static final String EXAMPLE_UNKNOWN_TOWN_400 = """
            {"code":"unknown_town","message":"townCode 'johannesburg' is not one of our delivery towns - choose one from the list"}""";

    static final String EXAMPLE_HOURS_400 = """
            {"code":"invalid_opening_hours","message":"Sat closes before it opens"}""";

    static final String EXAMPLE_LIMIT_409 = """
            {"code":"collection_point_limit_reached","message":"You can have at most 10 collection points"}""";

    static final String EXAMPLE_OUT_OF_BOUNDS_422 = """
            {"code":"location_out_of_bounds","message":"That position is outside the area we deliver in - check the map pin"}""";

    static final String EXAMPLE_SCOPE_403 = """
            {"code":"merchant_scope_missing","message":"Caller token carries no merchant scope"}""";

    private final CollectionPointService service;

    @GetMapping
    @Operation(summary = "Your collection points",
            description = "Default first, then oldest first. Empty when you have none — buyers then "
                    + "arrange collection with you directly, as before points existed.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Your points",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = EXAMPLE_LIST_200))),
            @ApiResponse(responseCode = "403", description = "Not a selling business's admin",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = EXAMPLE_SCOPE_403)))
    })
    public ResponseEntity<ApiResult<List<CollectionPointResponse>>> list() {
        return ResponseEntity.ok(ApiResult.ok(service.list(merchantScope())));
    }

    @PostMapping
    @Operation(summary = "Add a collection point",
            description = "Your first point becomes your default. `townCode` comes from "
                    + "GET /marketplace/delivery-towns. `openingHours` are in local time "
                    + "(24-hour HH:mm); two entries for one day mean you close for a break. "
                    + "`latitude`/`longitude` are optional but go together, and must be inside the "
                    + "country.\n\nRequest example:\n```json\n" + EXAMPLE_REQUEST + "\n```")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Added",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = EXAMPLE_ONE_200))),
            @ApiResponse(responseCode = "400", description = "A required field is missing, the town is "
                    + "not ours, the hours overlap or close before they open, the phone is not a "
                    + "number, or only one half of the pin was sent",
                    content = @Content(mediaType = "application/json", examples = {
                            @ExampleObject(name = "unknown-town", value = EXAMPLE_UNKNOWN_TOWN_400),
                            @ExampleObject(name = "bad-hours", value = EXAMPLE_HOURS_400)})),
            @ApiResponse(responseCode = "403", description = "Not a selling business's admin",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = EXAMPLE_SCOPE_403))),
            @ApiResponse(responseCode = "409", description = "Already 10 points",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = EXAMPLE_LIMIT_409))),
            @ApiResponse(responseCode = "422", description = "The map pin is outside the country",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = EXAMPLE_OUT_OF_BOUNDS_422)))
    })
    public ResponseEntity<ApiResult<CollectionPointResponse>> create(
            @Valid @RequestBody CollectionPointRequest request) {
        AuthenticatedUser caller = CurrentUser.get();
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResult.ok("Collection point saved",
                service.create(caller, merchantScope(), request, true)));
    }

    @PutMapping("/{pointId}")
    @Operation(summary = "Replace a collection point",
            description = "Send the whole point, hours included — this is a replace, not a merge "
                    + "(omitted hours mean none). Orders already placed keep the address they "
                    + "were given.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Saved",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = EXAMPLE_ONE_200))),
            @ApiResponse(responseCode = "400", description = "As for add",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = EXAMPLE_HOURS_400))),
            @ApiResponse(responseCode = "404", description = "Not one of your points",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = EXAMPLE_NOT_FOUND_404))),
            @ApiResponse(responseCode = "422", description = "The map pin is outside the country",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = EXAMPLE_OUT_OF_BOUNDS_422)))
    })
    public ResponseEntity<ApiResult<CollectionPointResponse>> update(
            @Parameter(example = "5c1d8e2a-3b4f-4a6d-9e7c-2f8a1b3c4d5e")
            @PathVariable("pointId") String pointId,
            @Valid @RequestBody CollectionPointRequest request) {
        AuthenticatedUser caller = CurrentUser.get();
        return ResponseEntity.ok(ApiResult.ok("Collection point saved",
                service.update(caller, merchantScope(), parsePointId(pointId), request, true)));
    }

    @PutMapping("/{pointId}/default")
    @Operation(summary = "Make a point your default",
            description = "Where a buyer who does not choose collects. The previous default "
                    + "stops being one.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "It is now the default",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = EXAMPLE_ONE_200))),
            @ApiResponse(responseCode = "404", description = "Not one of your points",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = EXAMPLE_NOT_FOUND_404)))
    })
    public ResponseEntity<ApiResult<CollectionPointResponse>> makeDefault(
            @Parameter(example = "5c1d8e2a-3b4f-4a6d-9e7c-2f8a1b3c4d5e")
            @PathVariable("pointId") String pointId) {
        AuthenticatedUser caller = CurrentUser.get();
        return ResponseEntity.ok(ApiResult.ok("Default collection point changed",
                service.makeDefault(caller, merchantScope(), parsePointId(pointId), true)));
    }

    @DeleteMapping("/{pointId}")
    @Operation(summary = "Remove a collection point",
            description = "Orders already placed keep the address they were given. Removing your "
                    + "default makes your oldest remaining point the default.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Removed",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    {"code":"OK","message":"Collection point removed","data":null}"""))),
            @ApiResponse(responseCode = "404", description = "Not one of your points",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = EXAMPLE_NOT_FOUND_404)))
    })
    public ResponseEntity<ApiResult<Void>> delete(
            @Parameter(example = "5c1d8e2a-3b4f-4a6d-9e7c-2f8a1b3c4d5e")
            @PathVariable("pointId") String pointId) {
        AuthenticatedUser caller = CurrentUser.get();
        service.delete(caller, merchantScope(), parsePointId(pointId), true);
        return ResponseEntity.ok(ApiResult.ok("Collection point removed", null));
    }

    // ------------------------------------------------------------------

    /** Merchant scope comes from the JWT, never from a request. */
    private static UUID merchantScope() {
        AuthenticatedUser caller = CurrentUser.get();
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

    /** A UUID-typed path variable would surface as a generic 400; parse here and name it. */
    static UUID parsePointId(String raw) {
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException ex) {
            throw ApiException.badRequest("invalid_collection_point_id",
                    "Collection point id must be a UUID");
        }
    }
}
