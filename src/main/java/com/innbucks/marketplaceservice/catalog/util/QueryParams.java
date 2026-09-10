package com.innbucks.marketplaceservice.catalog.util;

import com.innbucks.marketplaceservice.api.ApiException;
import jakarta.servlet.http.HttpServletRequest;

import java.util.Set;
import java.util.TreeSet;

/**
 * Refuses a query parameter the endpoint does not understand.
 *
 * <p><b>Why this exists.</b> Spring silently ignores an unbound request
 * parameter, which on a FILTERED endpoint is the worst possible default: a
 * filter the client believes it applied contributes nothing, and the response
 * is a confidently wrong result set with a 200 on it. Nothing in the payload
 * says the filter was dropped, so the bug reads as "the catalogue is showing
 * the wrong things" rather than "we sent a parameter you don't support" — and
 * on a price or stock filter that is a customer seeing goods they cannot buy.
 * The app team asked for this explicitly: <i>"A 400 on an unknown param would
 * be safer for both of us."</i>
 *
 * <p>Applied to the BROWSE surface only. On an endpoint with no filters there
 * is nothing for a stray parameter to get wrong, and refusing there would
 * break callers for no benefit.
 *
 * <p><b>This is a deliberate breaking change</b> for any caller that appends
 * something we do not list (a cache-buster, a stray {@code utm_*}). That is
 * the point — such a caller is already getting results it did not ask for —
 * but it is why the refusal names both the offender and the full supported
 * set, so the fix is obvious from the response alone.
 */
public final class QueryParams {

    /** Bound on what we echo back: the name is caller-controlled, and a
     *  multi-kilobyte parameter name should not become a multi-kilobyte error. */
    private static final int MAX_ECHOED_NAME = 40;

    private QueryParams() {
    }

    /**
     * @throws ApiException 400 {@code unknown_parameter} naming the offending
     *         parameter and the full supported set. When several are unknown
     *         the alphabetically first is named, so the same request always
     *         produces the same message (servlet parameter order is not
     *         guaranteed, and a flapping error message is untestable and
     *         unreportable).
     */
    public static void rejectUnknown(HttpServletRequest request, Set<String> allowed) {
        if (request == null) {
            return;
        }
        TreeSet<String> unknown = new TreeSet<>();
        for (String name : request.getParameterMap().keySet()) {
            if (!allowed.contains(name)) {
                unknown.add(name);
            }
        }
        if (unknown.isEmpty()) {
            return;
        }
        throw ApiException.badRequest("unknown_parameter",
                "Unknown query parameter '" + truncate(unknown.first()) + "'. Supported parameters: "
                        + String.join(", ", new TreeSet<>(allowed)));
    }

    private static String truncate(String name) {
        return name.length() <= MAX_ECHOED_NAME ? name : name.substring(0, MAX_ECHOED_NAME) + "…";
    }
}
