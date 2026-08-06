package com.innbucks.marketplaceservice.config;

import org.slf4j.MDC;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

import java.io.IOException;

/**
 * Propagates the inbound {@code X-Correlation-ID} (mirrored to MDC by
 * {@link CorrelationIdFilter}) onto every outbound RestClient call so a single
 * traceId follows a request across service boundaries. Without this the
 * receiving service mints a fresh ID and the two halves of the trace can't
 * be joined. Fleet copy (InnRewards / booking-service).
 *
 * <p>If the caller already set {@code X-Correlation-ID} on the request (rare,
 * but possible for one-off tooling) we don't overwrite it. If there's nothing
 * in MDC (e.g. an async notification task with no inbound request) we just
 * pass through — the downstream service will generate its own ID.
 */
public class CorrelationIdPropagatingInterceptor implements ClientHttpRequestInterceptor {

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
            throws IOException {
        if (request.getHeaders().getFirst(CorrelationIdFilter.HEADER) == null) {
            String traceId = MDC.get(CorrelationIdFilter.MDC_KEY);
            if (traceId != null && !traceId.isBlank()) {
                request.getHeaders().add(CorrelationIdFilter.HEADER, traceId);
            }
        }
        return execution.execute(request, body);
    }
}
