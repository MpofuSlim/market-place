package com.innbucks.marketplaceservice.config;

import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.DefaultRedirectStrategy;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.http.HttpRequest;
import org.apache.hc.core5.http.HttpResponse;
import org.apache.hc.core5.http.ProtocolException;
import org.apache.hc.core5.http.protocol.HttpContext;
import org.apache.hc.core5.util.TimeValue;
import org.apache.hc.core5.util.Timeout;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;

import java.time.Duration;

/**
 * The ONE pooled outbound HTTP client of this service: a single Apache
 * httpclient5 {@link PoolingHttpClientConnectionManager} and the
 * {@link CloseableHttpClient} over it. Every {@code RestClient} gets its
 * request factory from {@link #requestFactory(Duration, Duration)}, which
 * shares the pool and carries that client's own timeouts.
 *
 * <p>What it deliberately keeps from the {@code SimpleClientHttpRequestFactory}
 * it replaces, so moving onto it changes nothing on the wire but connection
 * reuse:
 * <ul>
 *   <li><b>HTTP/1.1 only.</b> The classic httpclient5 transport never speaks
 *       HTTP/2. (The JDK {@code HttpClient} negotiates HTTP/2, which once broke
 *       the fleet's WireMock contract tests with {@code RST_STREAM}.)</li>
 *   <li><b>No automatic retries</b> ({@code disableAutomaticRetries()}).
 *       httpclient5's default strategy re-sends an idempotent request after an
 *       I/O error and ANY request after a 429/503 — a silent second delivery of
 *       an SMS, or a second login. A caller that wants a retry writes it.</li>
 *   <li><b>Redirects followed for GET only</b>, as {@code HttpURLConnection}
 *       did; a POST is never re-sent to another location.</li>
 *   <li><b>No cookie store and no transparent compression.</b> One client is
 *       shared by every integration, so a cookie one upstream set must never
 *       ride along to another; and the request headers stay what they were.</li>
 *   <li><b>System proxy / TLS properties are honoured</b>
 *       ({@code useSystemProperties()}), as {@code HttpURLConnection} did.</li>
 * </ul>
 *
 * <p>Owned by the Spring context ({@link OutboundHttpConfig} closes it); a
 * request factory handed out here never closes the shared client.
 */
public final class OutboundHttp implements AutoCloseable {

    private final OutboundHttpProperties properties;
    private final PoolingHttpClientConnectionManager connectionManager;
    private final CloseableHttpClient httpClient;

    public OutboundHttp(OutboundHttpProperties properties) {
        validate(properties);
        this.properties = properties;
        this.connectionManager = PoolingHttpClientConnectionManagerBuilder.create()
                .useSystemProperties()
                .setMaxConnTotal(properties.getMaxTotal())
                .setMaxConnPerRoute(properties.getMaxPerRoute())
                .setDefaultConnectionConfig(ConnectionConfig.custom()
                        .setConnectTimeout(Timeout.of(properties.getConnectTimeout()))
                        .setTimeToLive(TimeValue.of(properties.getTimeToLive()))
                        .setValidateAfterInactivity(TimeValue.of(properties.getValidateAfterInactivity()))
                        .build())
                .build();
        this.httpClient = HttpClients.custom()
                .useSystemProperties()
                .setConnectionManager(connectionManager)
                .setDefaultRequestConfig(RequestConfig.custom()
                        .setConnectionRequestTimeout(Timeout.of(properties.getConnectionRequestTimeout()))
                        .setResponseTimeout(Timeout.of(properties.getResponseTimeout()))
                        .build())
                .disableAutomaticRetries()
                .disableCookieManagement()
                .disableContentCompression()
                .setRedirectStrategy(GetOnlyRedirectStrategy.INSTANCE)
                .evictExpiredConnections()
                .evictIdleConnections(TimeValue.of(properties.getIdleEviction()))
                .build();
    }

    /** A pool on the default sizing and timeouts — for tests and tools outside Spring. */
    public static OutboundHttp withDefaults() {
        return new OutboundHttp(new OutboundHttpProperties());
    }

    /** A request factory on the shared pool with the default timeouts. */
    public PooledRequestFactory requestFactory() {
        return requestFactory(properties.getConnectTimeout(), properties.getResponseTimeout());
    }

    /**
     * A request factory on the shared pool with this client's own timeouts. A
     * null or non-positive value falls back to the shared default — never to
     * "wait forever", which is what a zero meant to the factory this replaced.
     *
     * <p>The wait for a free pooled connection is capped at the client's own
     * connect timeout: waiting for a connection stands in for opening one, so a
     * client that chose a tight budget (a 500 ms lookup on a public page) never
     * waits longer for the pool than it would have for a connect.
     */
    public PooledRequestFactory requestFactory(Duration connectTimeout, Duration responseTimeout) {
        Duration connect = positiveOr(connectTimeout, properties.getConnectTimeout());
        Duration poolWait = properties.getConnectionRequestTimeout();
        return new PooledRequestFactory(httpClient,
                connect,
                positiveOr(responseTimeout, properties.getResponseTimeout()),
                connect.compareTo(poolWait) < 0 ? connect : poolWait);
    }

    /** Millisecond overload for the clients whose timeouts are configured as {@code *-ms} ints. */
    public PooledRequestFactory requestFactory(long connectTimeoutMs, long responseTimeoutMs) {
        return requestFactory(Duration.ofMillis(connectTimeoutMs), Duration.ofMillis(responseTimeoutMs));
    }

    public PoolingHttpClientConnectionManager connectionManager() {
        return connectionManager;
    }

    public CloseableHttpClient httpClient() {
        return httpClient;
    }

    public OutboundHttpProperties properties() {
        return properties;
    }

    @Override
    public void close() throws java.io.IOException {
        httpClient.close(); // closes the connection manager and stops the evictor
    }

    private static Duration positiveOr(Duration value, Duration fallback) {
        return value == null || value.isZero() || value.isNegative() ? fallback : value;
    }

    private static void validate(OutboundHttpProperties p) {
        if (p.getMaxTotal() < 1 || p.getMaxPerRoute() < 1) {
            throw new IllegalStateException("outbound-http.max-total and max-per-route must be at least 1");
        }
        if (p.getMaxPerRoute() > p.getMaxTotal()) {
            throw new IllegalStateException("outbound-http.max-per-route (" + p.getMaxPerRoute()
                    + ") cannot exceed outbound-http.max-total (" + p.getMaxTotal() + ")");
        }
        requirePositive("connect-timeout", p.getConnectTimeout());
        requirePositive("response-timeout", p.getResponseTimeout());
        requirePositive("connection-request-timeout", p.getConnectionRequestTimeout());
        requirePositive("idle-eviction", p.getIdleEviction());
        requirePositive("time-to-live", p.getTimeToLive());
        requirePositive("validate-after-inactivity", p.getValidateAfterInactivity());
    }

    private static void requirePositive(String name, Duration d) {
        if (d == null || d.isZero() || d.isNegative()) {
            throw new IllegalStateException("outbound-http." + name + " must be a positive duration");
        }
    }

    /**
     * A request factory on the shared client. It carries its client's connect
     * timeout per request (Spring 7's factory no longer has a connect-timeout
     * setter; httpclient5 still honours one on the request config, ahead of the
     * pool's default), and it never closes the shared client.
     */
    public static final class PooledRequestFactory extends HttpComponentsClientHttpRequestFactory {

        private final Duration connectTimeout;
        private final Duration responseTimeout;
        private final Duration connectionRequestTimeout;

        PooledRequestFactory(CloseableHttpClient httpClient, Duration connectTimeout,
                             Duration responseTimeout, Duration connectionRequestTimeout) {
            super(httpClient);
            this.connectTimeout = connectTimeout;
            this.responseTimeout = responseTimeout;
            this.connectionRequestTimeout = connectionRequestTimeout;
            setReadTimeout(responseTimeout);
            setConnectionRequestTimeout(connectionRequestTimeout);
        }

        public Duration connectTimeout() { return connectTimeout; }
        public Duration responseTimeout() { return responseTimeout; }
        public Duration connectionRequestTimeout() { return connectionRequestTimeout; }

        /** The request config every request through this factory carries. */
        public RequestConfig effectiveRequestConfig() {
            return createRequestConfig(getHttpClient());
        }

        @Override
        @SuppressWarnings("deprecation") // per-request connect timeout: still honoured by 5.x, see class doc
        protected RequestConfig mergeRequestConfig(RequestConfig clientConfig) {
            return RequestConfig.copy(super.mergeRequestConfig(clientConfig))
                    .setConnectTimeout(Timeout.of(connectTimeout))
                    .build();
        }

        /**
         * The client is shared by every factory in the service and owned by the
         * Spring context. Spring calls this on a factory that is registered as a
         * bean; closing the shared client there would take every integration down.
         */
        @Override
        public void destroy() {
            // intentionally a no-op
        }
    }

    /** {@code HttpURLConnection}'s rule: follow a redirect for a GET, never for anything else. */
    static final class GetOnlyRedirectStrategy extends DefaultRedirectStrategy {

        static final GetOnlyRedirectStrategy INSTANCE = new GetOnlyRedirectStrategy();

        @Override
        public boolean isRedirected(HttpRequest request, HttpResponse response, HttpContext context)
                throws ProtocolException {
            return "GET".equalsIgnoreCase(request.getMethod()) && super.isRedirected(request, response, context);
        }
    }
}
