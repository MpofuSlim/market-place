package com.innbucks.marketplaceservice.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Read-only platform access: {@value #REPORTS_READ}.
 *
 * <p>A holder reads what SUPER_ADMIN reads across every seller, listing and
 * order, through GET endpoints only, and changes nothing. It is a permission,
 * not a role: user-service's {@code PermissionCatalog} defines it
 * ({@code Scope.PLATFORM}), it arrives in the token's {@code perms} claim, and
 * {@code JwtFilter} grants it as a bare authority after
 * {@link JwtUtil#extractPermissions}'s shape check (so a {@code ROLE_*} entry
 * can never ride in on it).
 *
 * <p>Two halves, both required:
 * <ul>
 *   <li><b>The gate:</b> a granted GET adds {@link #OR_CAN_READ} to its own
 *       {@code @PreAuthorize}. No other verb may name the permission;
 *       {@code ReportsReadIsGetOnlyTest} fails the build if one does.</li>
 *   <li><b>The scope:</b> a read whose scope is decided by role ("SUPER_ADMIN
 *       sees every seller, else only the caller's own") asks
 *       {@link #readsEverySeller}, never {@code isSuperAdmin()} alone. That
 *       helper answers yes for the permission only on a GET (or HEAD), so if
 *       it is ever reused on a write path the reader still gets nothing.
 *       Write-side ownership checks keep {@code isSuperAdmin()}.</li>
 * </ul>
 *
 * <p>A reader who is not SUPER_ADMIN also sees buyer contact details redacted
 * ({@link #redactsBuyerContact}): phones masked to their last four digits,
 * street lines, landmark, gift message and the courier's live position
 * withheld. A buyer's phone and home address are support's business, which
 * reads them through the logged {@code /marketplace/support/**} surface.
 */
public final class PlatformReader {

    /** The permission code (user-service {@code PermissionCatalog}). */
    public static final String REPORTS_READ = "marketplace-reports:read";

    /** Appended to a granted GET's {@code @PreAuthorize} expression. */
    public static final String OR_CAN_READ = " or hasAuthority('" + REPORTS_READ + "')";

    private PlatformReader() {
    }

    /**
     * Whether this read answers for every seller: SUPER_ADMIN always, a holder
     * of {@value #REPORTS_READ} on a GET or HEAD request only.
     */
    public static boolean readsEverySeller(AuthenticatedUser caller) {
        if (caller == null) {
            return false;
        }
        return caller.isSuperAdmin() || (caller.hasPermission(REPORTS_READ) && isReadRequest());
    }

    /**
     * Whether the caller reads the platform through the permission alone, not
     * as SUPER_ADMIN: the caller whose buyer-level look-ups are refused and
     * whose views carry buyer contact redacted.
     */
    public static boolean isReportsReader(AuthenticatedUser caller) {
        return caller != null && !caller.isSuperAdmin() && caller.hasPermission(REPORTS_READ);
    }

    /**
     * For response assembly, where no caller is passed: true when the request
     * being served belongs to a reports reader (see {@link #isReportsReader}).
     * Outside a request (a job, a listener) it is false, which leaves those
     * paths exactly as they were; none of them renders for a reader.
     */
    public static boolean redactsBuyerContact() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getPrincipal() instanceof AuthenticatedUser user
                && isReportsReader(user);
    }

    /** {@code ****1234}, or null for null. */
    public static String maskPhone(String phone) {
        if (phone == null) {
            return null;
        }
        return phone.length() <= 4 ? "****" : "****" + phone.substring(phone.length() - 4);
    }

    private static boolean isReadRequest() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (!(attributes instanceof ServletRequestAttributes servlet)) {
            return false;
        }
        HttpServletRequest request = servlet.getRequest();
        String method = request.getMethod();
        return "GET".equals(method) || "HEAD".equals(method);
    }
}
