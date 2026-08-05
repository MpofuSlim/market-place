package com.innbucks.marketplaceservice.security;

import com.innbucks.marketplaceservice.api.ApiException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Optional;

/**
 * Static accessor for the request's {@link AuthenticatedUser} principal set by
 * {@link JwtFilter}. Centralises the {@code SecurityContextHolder} plumbing so
 * controllers/services never touch the raw {@link Authentication}.
 */
public final class CurrentUser {

    private CurrentUser() {
    }

    /**
     * The authenticated caller. Practically unreachable behind
     * {@link SecurityConfig}'s {@code .anyRequest().authenticated()} — thrown
     * only if a permitAll path calls this without a token.
     */
    public static AuthenticatedUser get() {
        return find().orElseThrow(() -> ApiException.forbidden(
                "not_authenticated", "No authenticated user on this request"));
    }

    /** The authenticated caller, or empty when the request is anonymous (a
     *  permitAll path) or authenticated by a non-JWT principal (the metrics
     *  scraper). */
    public static Optional<AuthenticatedUser> find() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof AuthenticatedUser user)) {
            return Optional.empty();
        }
        return Optional.of(user);
    }
}
