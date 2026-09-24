package com.innbucks.marketplaceservice.delivery;

import com.innbucks.marketplaceservice.api.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The towns this cell delivers across, and the one place a town is resolved
 * from what a client sent.
 *
 * <p>Loaded ONCE, lazily, and held for the life of the process: the list is
 * migration-seeded and read-only at runtime, so it changes only with a deploy —
 * which restarts the process. A cache with no invalidation story is correct
 * here precisely because nothing can write the table.
 *
 * <p>Filtered by {@code innbucks.country}: each cell seeds its own market's
 * towns into the same table, and a Zimbabwe cell must never offer a Kenyan
 * town.
 */
@Component
public class DeliveryTownCatalog {

    private final DeliveryTownRepository repository;
    private final String country;
    private volatile Map<String, DeliveryTown> byCode;

    public DeliveryTownCatalog(DeliveryTownRepository repository,
                               @Value("${innbucks.country}") String country) {
        this.repository = repository;
        this.country = country == null ? "" : country.trim().toUpperCase(Locale.ROOT);
    }

    /** Every town this cell serves, in display order. */
    public List<DeliveryTown> all() {
        return List.copyOf(towns().values());
    }

    public Optional<DeliveryTown> find(String code) {
        if (code == null || code.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(towns().get(code.trim().toLowerCase(Locale.ROOT)));
    }

    /** Display name for a stored code, or null when the code is unknown. */
    public String nameOf(String code) {
        return find(code).map(DeliveryTown::getName).orElse(null);
    }

    /**
     * The town a request names, by code — refusing, never guessing, when the
     * code is not one of this cell's towns.
     *
     * @throws ApiException 400 {@code unknown_town}
     */
    public DeliveryTown require(String code, String field) {
        return find(code).orElseThrow(() -> ApiException.badRequest("unknown_town",
                (field == null ? "town" : field) + " '" + code
                        + "' is not one of our delivery towns - choose one from the list"));
    }

    /**
     * The town an ADDRESS names: its {@code townCode} when sent, otherwise its
     * free-text {@code city} matched to a town name ignoring case — so a client
     * that has not learned about towns yet and sends {@code "city":"Harare"}
     * keeps working. Anything that matches no town is refused rather than
     * stored as an address no seller can ever deliver to.
     *
     * @throws ApiException 400 {@code unknown_town}
     */
    public DeliveryTown resolveForAddress(String townCode, String city) {
        if (townCode != null && !townCode.isBlank()) {
            return require(townCode, "townCode");
        }
        if (city != null && !city.isBlank()) {
            String wanted = city.trim().toLowerCase(Locale.ROOT);
            for (DeliveryTown town : towns().values()) {
                if (town.getName().toLowerCase(Locale.ROOT).equals(wanted)) {
                    return town;
                }
            }
        }
        // Read by a CUSTOMER: an app that still sends free text shows this
        // message as-is, so it is plain words and never names an endpoint.
        // The typed text is not echoed back — the shopper just typed it.
        throw ApiException.badRequest("unknown_town", ADDRESS_TOWN_MESSAGE);
    }

    /** What a shopper sees when the town on an address is not one we know. */
    static final String ADDRESS_TOWN_MESSAGE =
            "Please choose your town from the list so we can show who delivers to you";

    private Map<String, DeliveryTown> towns() {
        Map<String, DeliveryTown> loaded = byCode;
        if (loaded == null) {
            synchronized (this) {
                loaded = byCode;
                if (loaded == null) {
                    Map<String, DeliveryTown> map = new LinkedHashMap<>();
                    for (DeliveryTown town : repository.findByCountryOrderBySortOrderAscNameAsc(country)) {
                        map.put(town.getCode(), town);
                    }
                    // Insertion-ordered (display order) and read-only.
                    loaded = java.util.Collections.unmodifiableMap(map);
                    byCode = loaded;
                }
            }
        }
        return loaded;
    }
}
