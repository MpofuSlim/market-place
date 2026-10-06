package com.innbucks.marketplaceservice.config;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.propagation.Propagator;
import org.springframework.http.HttpRequest;

import java.net.URI;
import java.util.List;
import java.util.regex.Pattern;

/**
 * A {@link Propagator} that writes trace headers ({@code traceparent},
 * {@code tracestate}, {@code baggage}) ONLY onto a request addressed to a fleet
 * service by its discovery name ({@code http://user-service/...}), and never onto
 * anything else.
 *
 * <p>Partners must never receive {@code traceparent}: the InnBucks platform /
 * notification API and the WhatsApp gateway sit behind WAFs that have refused
 * requests carrying headers they did not expect, and a trace id is our
 * internal detail besides. Every partner client here is built WITHOUT an
 * observation registry today, so none of them would send one — but that is a
 * property of how each builder happens to be constructed, which the next
 * refactor of the HTTP client factories can change without anyone thinking about
 * tracing. This makes it a property of the DESTINATION instead: a request to a
 * host with a dot in it (every partner) or to {@code localhost} gets nothing,
 * however its client was built. It fails closed — a carrier that is not an HTTP
 * request is not written to at all.
 *
 * <p>The URI checked is the one the client was given, before Spring Cloud
 * LoadBalancer rewrites {@code user-service} to its address (the LoadBalancer
 * interceptor runs when the request EXECUTES; injection happens when the
 * observation starts), so the discovery name is what is seen. Extraction
 * (incoming requests) is untouched.
 */
public final class FleetOnlyPropagator implements Propagator {

    /** A discovery name: what the static discovery map is keyed by. Never a dotted host. */
    private static final Pattern FLEET_HOST = Pattern.compile("[a-z][a-z0-9-]*-service");

    private final Propagator delegate;

    public FleetOnlyPropagator(Propagator delegate) {
        this.delegate = delegate;
    }

    @Override
    public List<String> fields() {
        return delegate.fields();
    }

    @Override
    public <C> void inject(TraceContext context, C carrier, Setter<C> setter) {
        if (isFleetDestination(carrier)) {
            delegate.inject(context, carrier, setter);
        }
    }

    @Override
    public <C> Span.Builder extract(C carrier, Getter<C> getter) {
        return delegate.extract(carrier, getter);
    }

    static boolean isFleetDestination(Object carrier) {
        if (!(carrier instanceof HttpRequest request)) {
            return false;
        }
        URI uri = request.getURI();
        String host = uri == null ? null : uri.getHost();
        return host != null && FLEET_HOST.matcher(host).matches();
    }
}
