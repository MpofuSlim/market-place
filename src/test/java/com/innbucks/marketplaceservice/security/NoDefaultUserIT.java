package com.innbucks.marketplaceservice.security;

import com.innbucks.marketplaceservice.support.PostgresTestContainer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.security.core.userdetails.UserDetailsService;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The service has no username/password login, so it must not carry Spring
 * Boot's default in-memory user either. Before the exclusion on
 * {@code MarketplaceServiceApplication}, Boot created one on every boot and
 * logged its random password at WARN — unusable (no HTTP Basic, no form
 * login), but a credential-shaped string in production logs.
 *
 * <p>Runs in the shared default test context, so it costs no new context.
 */
class NoDefaultUserIT extends PostgresTestContainer {

    @Autowired
    private ApplicationContext context;

    @Test
    @DisplayName("No UserDetailsService bean exists, so Boot creates no default user and logs no password")
    void noDefaultUserIsCreated() {
        assertThat(context.getBeansOfType(UserDetailsService.class)).isEmpty();
    }

    @Test
    @DisplayName("HTTP Basic credentials are never accepted: a protected endpoint stays 401")
    void basicCredentialsAreNotALogin() throws Exception {
        String basic = Base64.getEncoder()
                .encodeToString("user:password".getBytes(StandardCharsets.UTF_8));
        mockMvc.perform(get("/marketplace/orders/mine")
                        .header("Authorization", "Basic " + basic))
                .andExpect(status().isUnauthorized());
    }
}
