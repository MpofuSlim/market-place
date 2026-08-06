package com.innbucks.marketplaceservice.security;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Stateless JWT security. CSRF is off (no cookies/sessions — a Bearer-token
 * API behind the fleet gateway) and CORS lives exclusively on that gateway;
 * a per-service CORS config here would emit a second, colliding header set.
 */
@Configuration
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtFilter jwtFilter;
    private final MetricsScrapeAuthFilter metricsScrapeAuthFilter;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // Public catalog — browse without an account. Writes on
                        // /marketplace/catalog/** stay authenticated (GET only).
                        .requestMatchers(HttpMethod.GET, "/marketplace/catalog/**").permitAll()
                        // Public category taxonomy (GET only). Lives OUTSIDE the
                        // catalog prefix, so it needs its own exact matcher —
                        // pinned by SecuritySurfaceIT.anonymousCategoryTreeIsPublic.
                        .requestMatchers(HttpMethod.GET, "/marketplace/categories").permitAll()
                        // S2S surface: gated by X-Internal-Token in the
                        // controllers (InternalTokenAuthorizer), not the user
                        // JWT; the fleet gateway edge-denies the path — "three
                        // files must agree" (docs/fleet-wiring.md).
                        .requestMatchers("/marketplace/internal/**").permitAll()
                        .requestMatchers(
                                // Only health + info are anonymous. Prometheus
                                // is permitAll ONLY because MetricsScrapeAuthFilter
                                // fail-closes it on X-Metrics-Token before the
                                // authorization layer runs. Everything else under
                                // /actuator stays authenticated — loosen per
                                // endpoint, never via /actuator/**.
                                "/actuator/health/**",
                                "/actuator/info",
                                "/actuator/prometheus",
                                "/v3/api-docs/**",
                                "/swagger-ui/**",
                                "/swagger-ui.html",
                                "/error"
                        ).permitAll()
                        // Everything else needs the fleet JWT; method-level
                        // @PreAuthorize on controllers restricts roles further.
                        .anyRequest().authenticated()
                )
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint((req, res, e) -> {
                            res.setStatus(401);
                            res.setContentType("application/json");
                            res.getWriter().write(
                                    "{\"code\":\"UNAUTHORIZED\",\"message\":\"Invalid or missing token\",\"data\":null}");
                        })
                        .accessDeniedHandler((req, res, e) -> {
                            res.setStatus(403);
                            res.setContentType("application/json");
                            res.getWriter().write(
                                    "{\"code\":\"FORBIDDEN\",\"message\":\"Forbidden - insufficient role\",\"data\":null}");
                        })
                )
                // MetricsScrapeAuthFilter first: it terminates /actuator/prometheus
                // itself (fail-closed gate); JwtFilter skips /actuator entirely.
                .addFilterBefore(metricsScrapeAuthFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
