package com.innbucks.marketplaceservice.notify;

import java.util.List;
import java.util.UUID;

/**
 * Resolves a merchant's ADMIN USERS (their stable {@code user_uuid}s) so the
 * merchant-order notifier can address them through
 * {@link UserNotifyGateway#notify}.
 *
 * <p>Identity is user-service's domain and the marketplace stores no
 * user↔merchant link — {@code Listing.merchantId} and
 * {@code MarketOrderItem.merchantId} are loyalty merchant ids copied off a JWT
 * claim — so the only implementation is an S2S lookup:
 * {@link UserServiceMerchantAdminResolver}.
 *
 * <p>The interface survives the arrival of that implementation because it is
 * the seam this service tests against: {@code MerchantOrderNotifier}'s
 * grouping, composition and fan-out are unit-tested over a mock of this, with
 * no HTTP anywhere near them.
 */
public interface MerchantAdminResolver {

    /**
     * The {@code user_uuid}s of the given merchant's admin users, empty when
     * none are resolvable. Implementations must be best-effort: return empty
     * on lookup failure, never throw (this runs inside the never-throws
     * notification listeners).
     */
    List<UUID> adminUserUuids(UUID merchantId);
}
