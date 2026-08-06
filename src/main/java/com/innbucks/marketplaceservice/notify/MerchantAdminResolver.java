package com.innbucks.marketplaceservice.notify;

import java.util.List;
import java.util.UUID;

/**
 * Resolves a merchant's ADMIN USERS (their stable {@code user_uuid}s) so the
 * merchant-order notifier can address them through
 * {@link UserNotifyGateway#notify}. Identity is user-service's domain — the
 * marketplace never stores user↔merchant links — so the only real
 * implementation is an S2S lookup against user-service.
 *
 * <p><b>TODO (cross-repo, verified 2026-08-06):</b> user-service currently has
 * NO internal endpoint that resolves users by {@code merchantId} — its
 * {@code /users/internal} surface offers merchant-ids-by-role
 * ({@code /merchants/assigned}), contact/notify by {@code user_uuid}, team-member
 * checks and tenant lookup only, although {@code UserRepository}
 * already carries {@code findByLoyaltyMerchantId}. When user-service ships a
 * small internal lookup (users/uuids by merchantId, {@code X-Internal-Token}
 * + gateway deny route per the fleet three-files rule), implement this
 * interface with a WireMock-contract-tested client and flip
 * {@code marketplace.notifications.merchant-orders.enabled} on. Until then
 * {@link Unavailable} keeps the trigger wired but inert.
 */
public interface MerchantAdminResolver {

    /**
     * The {@code user_uuid}s of the given merchant's admin users, empty when
     * none are resolvable. Implementations must be best-effort: return empty
     * on lookup failure, never throw (this runs inside the never-throws
     * notification listeners).
     */
    List<UUID> adminUserUuids(UUID merchantId);

    /**
     * The shipped default: no lookup exists in user-service yet, so nothing
     * resolves. Registered as the fallback bean; replaced the day a real
     * client lands.
     */
    final class Unavailable implements MerchantAdminResolver {
        @Override
        public List<UUID> adminUserUuids(UUID merchantId) {
            return List.of();
        }
    }
}
