package com.innbucks.marketplaceservice.config;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.propagation.Propagator;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.ClientHttpRequest;
import org.springframework.mock.http.client.MockClientHttpRequest;

import java.net.URI;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The partner guard, on its own: trace headers are written only towards a fleet
 * service named by its discovery name. Everything else — every partner host,
 * localhost, an IP, a carrier that is not an HTTP request — gets nothing.
 */
class FleetOnlyPropagatorTest {

    /** Writes a fixed traceparent, so the test sees whether inject() reached it. */
    private static final Propagator WRITES_TRACEPARENT = new Propagator() {
        @Override public List<String> fields() { return List.of("traceparent"); }
        @Override public <C> void inject(TraceContext context, C carrier, Setter<C> setter) {
            setter.set(carrier, "traceparent", "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01");
        }
        @Override public <C> Span.Builder extract(C carrier, Getter<C> getter) { return null; }
    };

    private final FleetOnlyPropagator propagator = new FleetOnlyPropagator(WRITES_TRACEPARENT);

    @ParameterizedTest
    @ValueSource(strings = {
            "http://user-service/users/internal/x",
            "http://loyalty-service/loyalty/internal/merchants",
            "http://payment-service:8085/payments/internal/1",
    })
    void fleetServicesByDiscoveryName_getTheTrace(String url) {
        assertThat(injectInto(url)).isEqualTo("00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "https://staging.innbucks.co.zw/auth/third-party",
            "https://api.innbucks.co.zw/api/v1/notifications/sms",
            "https://whatsapp-gateway.innbucks.co.zw/api/messages/custom-notification",
            "http://whatsapp-disabled.invalid/api/messages/custom-notification",
            "https://user-service.example.com/whatever",
            "http://localhost:8081/auth/customer/tier",
            "http://10.0.146.246:8081/x",
            "http://user-service.ticketing.svc.cluster.local/x",
    })
    void anythingElse_getsNothing(String url) {
        assertThat(injectInto(url)).isNull();
    }

    @Test
    void aCarrierThatIsNotAnHttpRequest_getsNothing() {
        StringBuilder carrier = new StringBuilder();
        propagator.inject(null, carrier, (c, key, value) -> c.append(key));
        assertThat(carrier).isEmpty();
    }

    @Test
    void extractionAndFieldsAreUntouched() {
        assertThat(propagator.fields()).containsExactly("traceparent");
    }

    private String injectInto(String url) {
        ClientHttpRequest request = new MockClientHttpRequest(HttpMethod.GET, URI.create(url));
        propagator.inject(null, request, (r, key, value) -> r.getHeaders().set(key, value));
        return request.getHeaders().getFirst("traceparent");
    }
}
