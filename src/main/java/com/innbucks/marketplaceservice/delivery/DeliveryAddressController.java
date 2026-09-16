package com.innbucks.marketplaceservice.delivery;

import com.innbucks.marketplaceservice.api.ApiResult;
import com.innbucks.marketplaceservice.delivery.dto.AddressRequest;
import com.innbucks.marketplaceservice.delivery.dto.AddressResponse;
import com.innbucks.marketplaceservice.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
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
 * The buyer's delivery address book — CUSTOMER-only, and scoped to the caller
 * by shape (no path or query parameter names a user, so there is nothing to
 * point at someone else's addresses).
 */
@Tag(name = "Delivery addresses",
        description = "Saved delivery destinations for the signed-in buyer — the address half of "
                + "checkout. Exactly one entry is the default: the first one saved becomes it, and "
                + "deleting it promotes the most recent survivor, so a buyer with any addresses "
                + "always has one pre-selected at checkout. Addresses are SNAPSHOT onto an order, "
                + "so editing or deleting one never changes an order already placed.")
@RestController
@RequestMapping("/marketplace/addresses")
@RequiredArgsConstructor
@PreAuthorize("hasRole('CUSTOMER')")
public class DeliveryAddressController {

    private final DeliveryAddressService addressService;

    private static final String EXAMPLE_ADDRESS = """
            {
              "id": "6f1c9d20-4a7e-4b83-9c5d-2e1f8a7b6c45",
              "label": "Home",
              "recipientName": "Tariro Moyo",
              "recipientMsisdn": "+263771234567",
              "line1": "14 Samora Machel Ave",
              "line2": "Flat 3B",
              "city": "Harare",
              "area": "Avondale",
              "landmark": "Opposite the clinic, blue gate",
              "defaultAddress": true,
              "createdAt": "2026-09-12T08:10:22Z",
              "updatedAt": "2026-09-12T08:10:22Z"
            }""";

    private static final String EXAMPLE_LIST_200 = """
            {
              "code": "OK",
              "message": "Success",
              "data": [
                {
                  "id": "6f1c9d20-4a7e-4b83-9c5d-2e1f8a7b6c45",
                  "label": "Home",
                  "recipientName": "Tariro Moyo",
                  "recipientMsisdn": "+263771234567",
                  "line1": "14 Samora Machel Ave",
                  "line2": "Flat 3B",
                  "city": "Harare",
                  "area": "Avondale",
                  "landmark": "Opposite the clinic, blue gate",
                  "defaultAddress": true,
                  "createdAt": "2026-09-12T08:10:22Z",
                  "updatedAt": "2026-09-12T08:10:22Z"
                },
                {
                  "id": "b81f3c64-9d05-4a72-8e13-7c4a2b9d6e08",
                  "label": "Work",
                  "recipientName": "Tariro Moyo",
                  "recipientMsisdn": "+263771234567",
                  "line1": "8 Kwame Nkrumah Ave",
                  "city": "Harare",
                  "area": "CBD",
                  "defaultAddress": false,
                  "createdAt": "2026-09-10T14:02:10Z",
                  "updatedAt": "2026-09-10T14:02:10Z"
                }
              ]
            }""";

    private static final String EXAMPLE_CREATED_201 = """
            {
              "code": "CREATED",
              "message": "Created",
              "data": """ + EXAMPLE_ADDRESS + """
            }""";

    private static final String EXAMPLE_OK_200 = """
            {
              "code": "OK",
              "message": "Success",
              "data": """ + EXAMPLE_ADDRESS + """
            }""";

    private static final String EXAMPLE_INVALID_MSISDN_400 = """
            {
              "code": "invalid_msisdn",
              "message": "recipientMsisdn is not a valid phone number"
            }""";

    private static final String EXAMPLE_NOT_FOUND_404 = """
            {
              "code": "address_not_found",
              "message": "Address not found"
            }""";

    private static final String EXAMPLE_LIMIT_409 = """
            {
              "code": "address_limit_reached",
              "message": "You can save up to 25 addresses. Delete one you no longer use."
            }""";

    private static final String EXAMPLE_FORBIDDEN_403 = """
            {
              "code": "FORBIDDEN",
              "message": "Forbidden - insufficient role"
            }""";

    @GetMapping
    @Operation(summary = "List my saved addresses",
            description = "Default first, then newest. An empty list is a normal answer for a "
                    + "shopper who has not saved one yet — the checkout then asks for a destination.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The caller's address book",
                    content = @Content(examples = @ExampleObject(value = EXAMPLE_LIST_200))),
            @ApiResponse(responseCode = "403", description = "Not a CUSTOMER",
                    content = @Content(examples = @ExampleObject(value = EXAMPLE_FORBIDDEN_403)))
    })
    public ResponseEntity<ApiResult<List<AddressResponse>>> list() {
        return ResponseEntity.ok(ApiResult.ok(addressService.listMine(CurrentUser.get())));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Read one of my saved addresses",
            description = "Another buyer's address is the same 404 as a nonexistent one.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The address",
                    content = @Content(examples = @ExampleObject(value = EXAMPLE_OK_200))),
            @ApiResponse(responseCode = "404", description = "No such address for this buyer",
                    content = @Content(examples = @ExampleObject(value = EXAMPLE_NOT_FOUND_404)))
    })
    public ResponseEntity<ApiResult<AddressResponse>> get(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResult.ok(addressService.getMine(CurrentUser.get(), id)));
    }

    @PostMapping
    @Operation(summary = "Save a new address",
            description = "The FIRST address a buyer saves becomes their default whatever "
                    + "`makeDefault` says. Free text is sanitized and the msisdn is normalised to "
                    + "E.164 server-side — send what the shopper typed.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Saved",
                    content = @Content(examples = @ExampleObject(value = EXAMPLE_CREATED_201))),
            @ApiResponse(responseCode = "400", description = "A required field is missing, or the "
                    + "recipient number is not dialable",
                    content = @Content(examples = @ExampleObject(value = EXAMPLE_INVALID_MSISDN_400))),
            @ApiResponse(responseCode = "409", description = "The per-buyer address cap is reached",
                    content = @Content(examples = @ExampleObject(value = EXAMPLE_LIMIT_409)))
    })
    public ResponseEntity<ApiResult<AddressResponse>> create(
            @Valid @RequestBody AddressRequest request) {
        AddressResponse saved = addressService.create(CurrentUser.get(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResult.created(saved));
    }

    @PutMapping("/{id}")
    @Operation(summary = "Replace one of my saved addresses",
            description = "A full replacement of the entry's contents. `makeDefault: true` promotes "
                    + "it; there is deliberately no way to CLEAR the default here, because a buyer "
                    + "with addresses and no default would be asked a question at every checkout. "
                    + "Orders already placed keep the destination they were given.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Updated",
                    content = @Content(examples = @ExampleObject(value = EXAMPLE_OK_200))),
            @ApiResponse(responseCode = "400", description = "A required field is missing, or the "
                    + "recipient number is not dialable",
                    content = @Content(examples = @ExampleObject(value = EXAMPLE_INVALID_MSISDN_400))),
            @ApiResponse(responseCode = "404", description = "No such address for this buyer",
                    content = @Content(examples = @ExampleObject(value = EXAMPLE_NOT_FOUND_404)))
    })
    public ResponseEntity<ApiResult<AddressResponse>> update(
            @PathVariable UUID id, @Valid @RequestBody AddressRequest request) {
        return ResponseEntity.ok(ApiResult.ok(addressService.update(CurrentUser.get(), id, request)));
    }

    @PutMapping("/{id}/default")
    @Operation(summary = "Make this my default address",
            description = "Idempotent — promoting the entry that is already default is a 200 no-op. "
                    + "The incumbent is demoted in the same transaction.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "This address is now the default",
                    content = @Content(examples = @ExampleObject(value = EXAMPLE_OK_200))),
            @ApiResponse(responseCode = "404", description = "No such address for this buyer",
                    content = @Content(examples = @ExampleObject(value = EXAMPLE_NOT_FOUND_404)))
    })
    public ResponseEntity<ApiResult<AddressResponse>> makeDefault(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResult.ok(addressService.makeDefault(CurrentUser.get(), id)));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete one of my saved addresses",
            description = "Deleting the default promotes the most recent survivor. Orders already "
                    + "placed are untouched — they snapshot the destination rather than reading it "
                    + "back from here.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Deleted",
                    content = @Content(examples = @ExampleObject(value = """
                            {
                              "code": "OK",
                              "message": "Address deleted"
                            }"""))),
            @ApiResponse(responseCode = "404", description = "No such address for this buyer",
                    content = @Content(examples = @ExampleObject(value = EXAMPLE_NOT_FOUND_404)))
    })
    public ResponseEntity<ApiResult<Void>> delete(@PathVariable UUID id) {
        addressService.delete(CurrentUser.get(), id);
        return ResponseEntity.ok(ApiResult.ok("Address deleted", null));
    }
}
