package com.innbucks.marketplaceservice.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * Accepts (or mints) the fleet correlation id on every request: the inbound
 * {@code X-Correlation-ID} header is reused when present (request-header lookup
 * is case-insensitive, so the gateway's casing doesn't matter) and a fresh UUID
 * is generated otherwise. The id rides MDC key {@code correlationId} — the
 * logging pattern in application.yaml renders it on every line — and is echoed
 * on the response so a client/gateway can stitch its trace to ours. MDC is
 * cleared in {@code finally}: servlet threads are pooled, and a leaked id would
 * mislabel the next request's logs.
 */
@Configuration
public class CorrelationIdFilter {

    public static final String HEADER = "X-Correlation-ID";
    public static final String MDC_KEY = "correlationId";

    @Bean
    public FilterRegistrationBean<OncePerRequestFilter> correlationIdFilterRegistration() {
        FilterRegistrationBean<OncePerRequestFilter> reg = new FilterRegistrationBean<>();
        reg.setFilter(new OncePerRequestFilter() {
            @Override
            protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                    throws ServletException, IOException {
                String id = request.getHeader(HEADER);
                if (id == null || id.isBlank()) {
                    id = UUID.randomUUID().toString();
                }
                MDC.put(MDC_KEY, id);
                response.setHeader(HEADER, id);
                try {
                    chain.doFilter(request, response);
                } finally {
                    MDC.remove(MDC_KEY);
                }
            }
        });
        reg.setOrder(Ordered.HIGHEST_PRECEDENCE);
        reg.addUrlPatterns("/*");
        return reg;
    }
}
