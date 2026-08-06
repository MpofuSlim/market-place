package com.innbucks.marketplaceservice.catalog;

import com.innbucks.marketplaceservice.api.ApiResult;
import com.innbucks.marketplaceservice.catalog.dto.CategoryNode;
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
 * Public category taxonomy read. Lives at {@code /marketplace/categories} —
 * OUTSIDE the {@code /marketplace/catalog/**} permitAll prefix, so
 * {@code SecurityConfig} carries a dedicated GET matcher for this exact path
 * (pinned by SecuritySurfaceIT). The taxonomy is migration-seeded and
 * read-only at runtime; the response is safely cacheable for an hour.
 */
@Tag(name = "Public Catalog")
@RestController
@RequestMapping("/marketplace/categories")
@RequiredArgsConstructor
public class CategoryController {

    private final CatalogService catalogService;

    private static final String EXAMPLE_TREE_200 = """
            {
              "code": "OK",
              "message": "Success",
              "data": [
                {
                  "code": "electronics",
                  "name": "Electronics",
                  "children": [
                    { "code": "appliances", "name": "Appliances", "children": [] },
                    { "code": "computers", "name": "Computers", "children": [] },
                    { "code": "phones-tablets", "name": "Phones & Tablets", "children": [] },
                    { "code": "tv-audio", "name": "TV & Audio", "children": [] }
                  ]
                },
                {
                  "code": "fashion",
                  "name": "Fashion",
                  "children": [
                    { "code": "bags-accessories", "name": "Bags & Accessories", "children": [] },
                    { "code": "shoes", "name": "Shoes", "children": [] }
                  ]
                },
                {
                  "code": "other",
                  "name": "Other",
                  "children": []
                }
              ]
            }""";

    @Operation(summary = "Get the category tree",
            description = "The full curated two-level taxonomy, display-name ordered. Listing writes "
                    + "accept any code in this tree (parent or child) as categoryCode; the catalog "
                    + "?category= filter expands a parent code to its children. The tree only changes "
                    + "with a deployment (it is migration-seeded), hence the 1h public cache header.")
    @SecurityRequirements({})
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The category tree (truncated example — "
                    + "the real response carries all ten top-level categories)",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(name = "tree", value = EXAMPLE_TREE_200)))
    })
    @GetMapping
    public ResponseEntity<ApiResult<List<CategoryNode>>> categories() {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(Duration.ofHours(1)).cachePublic())
                .body(ApiResult.ok(catalogService.categoryTree()));
    }
}
