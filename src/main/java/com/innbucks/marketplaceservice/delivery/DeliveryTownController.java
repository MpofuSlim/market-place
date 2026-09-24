package com.innbucks.marketplaceservice.delivery;

import com.innbucks.marketplaceservice.api.ApiResult;
import com.innbucks.marketplaceservice.delivery.dto.DeliveryTownResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.List;

/**
 * Public list of the towns this cell delivers across — the picker behind a
 * seller's "I deliver to…" and a buyer's delivery address. Lives OUTSIDE the
 * {@code /marketplace/catalog/**} permitAll prefix, so {@code SecurityConfig}
 * carries a dedicated GET matcher for this exact path, as it does for
 * {@code /marketplace/categories}. Migration-seeded, so an hour's public cache
 * is safe.
 */
@Tag(name = "Public Catalog")
@RestController
@RequestMapping("/marketplace/delivery-towns")
@RequiredArgsConstructor
public class DeliveryTownController {

    private final DeliveryTownCatalog towns;

    @Operation(summary = "List the towns sellers can deliver to",
            description = "Every town in this market, in display order. A listing's `deliveryTowns` "
                    + "and a delivery address's `townCode` take a `code` from this list. The list "
                    + "only changes with a deployment, hence the 1h public cache.")
    @SecurityRequirements({})
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The towns (truncated example)",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    {
                                      "code": "OK",
                                      "message": "Success",
                                      "data": [
                                        { "code": "harare", "name": "Harare", "province": "Harare" },
                                        { "code": "chitungwiza", "name": "Chitungwiza", "province": "Harare" },
                                        { "code": "bulawayo", "name": "Bulawayo", "province": "Bulawayo" },
                                        { "code": "mutare", "name": "Mutare", "province": "Manicaland" }
                                      ]
                                    }""")))
    })
    @GetMapping
    public ResponseEntity<ApiResult<List<DeliveryTownResponse>>> towns() {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(Duration.ofHours(1)).cachePublic())
                .body(ApiResult.ok(towns.all().stream().map(DeliveryTownResponse::from).toList()));
    }
}
