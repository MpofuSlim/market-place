package com.innbucks.marketplaceservice.pickup;

import com.innbucks.marketplaceservice.api.ApiException;
import com.innbucks.marketplaceservice.api.ApiResult;
import com.innbucks.marketplaceservice.pickup.dto.CollectionPointRequest;
import com.innbucks.marketplaceservice.pickup.dto.CollectionPointResponse;
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

import static com.innbucks.marketplaceservice.pickup.SellerCollectionPointController.EXAMPLE_HOURS_400;
import static com.innbucks.marketplaceservice.pickup.SellerCollectionPointController.EXAMPLE_LIST_200;
import static com.innbucks.marketplaceservice.pickup.SellerCollectionPointController.EXAMPLE_NOT_FOUND_404;
import static com.innbucks.marketplaceservice.pickup.SellerCollectionPointController.EXAMPLE_ONE_200;
import static com.innbucks.marketplaceservice.pickup.SellerCollectionPointController.parsePointId;

/**
 * The operator's override: a SUPER_ADMIN setting a seller's collection points
 * for them (onboarding over the phone, a correction). The same service as the
 * seller's own endpoints, recorded in the audit as NOT by the seller — naming
 * a merchant in the path belongs here, never on the seller's surface.
 */
@Tag(name = "Seller admin", description = "Operator overrides for a seller's collection points")
@RestController
@RequestMapping("/marketplace/admin/sellers/{merchantId}/collection-points")
@RequiredArgsConstructor
@PreAuthorize("hasRole('SUPER_ADMIN')")
public class AdminCollectionPointController {

    private final CollectionPointService service;

    @GetMapping
    @Operation(summary = "A seller's collection points", description = "Default first.")
    @ApiResponses(@ApiResponse(responseCode = "200", description = "The seller's points",
            content = @Content(mediaType = "application/json",
                    examples = @ExampleObject(value = EXAMPLE_LIST_200))))
    public ResponseEntity<ApiResult<List<CollectionPointResponse>>> list(
            @Parameter(example = "7e2a9c41-5b8f-4d36-a1c9-8f3b6d2e7a54")
            @PathVariable("merchantId") String merchantId) {
        return ResponseEntity.ok(ApiResult.ok(service.list(parseMerchantId(merchantId))));
    }

    @PostMapping
    @Operation(summary = "Add a collection point for a seller",
            description = "As the seller's own add; audited as an operator change.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Added",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = EXAMPLE_ONE_200))),
            @ApiResponse(responseCode = "400", description = "As for the seller's add",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = EXAMPLE_HOURS_400)))
    })
    public ResponseEntity<ApiResult<CollectionPointResponse>> create(
            @PathVariable("merchantId") String merchantId,
            @Valid @RequestBody CollectionPointRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResult.ok("Collection point saved",
                service.create(CurrentUser.get(), parseMerchantId(merchantId), request, false)));
    }

    @PutMapping("/{pointId}")
    @Operation(summary = "Replace a seller's collection point")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Saved",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = EXAMPLE_ONE_200))),
            @ApiResponse(responseCode = "404", description = "Not one of this seller's points",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = EXAMPLE_NOT_FOUND_404)))
    })
    public ResponseEntity<ApiResult<CollectionPointResponse>> update(
            @PathVariable("merchantId") String merchantId,
            @PathVariable("pointId") String pointId,
            @Valid @RequestBody CollectionPointRequest request) {
        return ResponseEntity.ok(ApiResult.ok("Collection point saved",
                service.update(CurrentUser.get(), parseMerchantId(merchantId),
                        parsePointId(pointId), request, false)));
    }

    @PutMapping("/{pointId}/default")
    @Operation(summary = "Make a point the seller's default")
    @ApiResponses(@ApiResponse(responseCode = "200", description = "It is now the default",
            content = @Content(mediaType = "application/json",
                    examples = @ExampleObject(value = EXAMPLE_ONE_200))))
    public ResponseEntity<ApiResult<CollectionPointResponse>> makeDefault(
            @PathVariable("merchantId") String merchantId,
            @PathVariable("pointId") String pointId) {
        return ResponseEntity.ok(ApiResult.ok("Default collection point changed",
                service.makeDefault(CurrentUser.get(), parseMerchantId(merchantId),
                        parsePointId(pointId), false)));
    }

    @DeleteMapping("/{pointId}")
    @Operation(summary = "Remove a seller's collection point")
    @ApiResponses(@ApiResponse(responseCode = "200", description = "Removed",
            content = @Content(mediaType = "application/json",
                    examples = @ExampleObject(value = """
                            {"code":"OK","message":"Collection point removed","data":null}"""))))
    public ResponseEntity<ApiResult<Void>> delete(
            @PathVariable("merchantId") String merchantId,
            @PathVariable("pointId") String pointId) {
        service.delete(CurrentUser.get(), parseMerchantId(merchantId), parsePointId(pointId), false);
        return ResponseEntity.ok(ApiResult.ok("Collection point removed", null));
    }

    private static UUID parseMerchantId(String raw) {
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException ex) {
            throw ApiException.badRequest("invalid_merchant_id", "merchantId must be a UUID");
        }
    }
}
