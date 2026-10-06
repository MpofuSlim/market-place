package com.innbucks.marketplaceservice.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Sizing and defaults for the ONE pooled outbound HTTP client this service
 * owns ({@link OutboundHttp}). Every {@code RestClient} in the service draws its
 * connections from that pool; see the "Outbound HTTP clients are pooled"
 * section of CLAUDE.md.
 *
 * <p>The defaults are sized for a small cell: one pod talks to a handful of
 * hosts (user-service, the InnBucks notification API, the WhatsApp gateway),
 * so 20 connections to any one of them and 50 in all is ample headroom. A client that sets its own connect/response
 * timeout keeps it; the timeouts here apply only to a client that sets none.
 */
@ConfigurationProperties(prefix = "outbound-http")
public class OutboundHttpProperties {

    /** Upper bound on pooled connections across every route. */
    private int maxTotal = 50;

    /** Upper bound on pooled connections to one host:port. */
    private int maxPerRoute = 20;

    /** TCP connect timeout for a client that sets none of its own. */
    private Duration connectTimeout = Duration.ofSeconds(2);

    /** Time to wait for response data, for a client that sets none of its own. */
    private Duration responseTimeout = Duration.ofSeconds(10);

    /**
     * How long a caller waits for a free pooled connection when the route or
     * the pool is at its limit. Past it the call fails as an I/O error (the
     * same "unreachable" path a refused connect takes), never a queue that
     * grows without bound. A client whose own connect timeout is shorter waits
     * no longer than that.
     */
    private Duration connectionRequestTimeout = Duration.ofSeconds(2);

    /** A pooled connection idle longer than this is closed by the evictor. */
    private Duration idleEviction = Duration.ofSeconds(30);

    /** Hard cap on how long one connection is reused, whatever its activity. */
    private Duration timeToLive = Duration.ofMinutes(5);

    /**
     * A connection idle longer than this is checked for staleness before it is
     * leased, so a peer's silent close is caught before a request is written.
     */
    private Duration validateAfterInactivity = Duration.ofSeconds(2);

    public int getMaxTotal() { return maxTotal; }
    public void setMaxTotal(int maxTotal) { this.maxTotal = maxTotal; }

    public int getMaxPerRoute() { return maxPerRoute; }
    public void setMaxPerRoute(int maxPerRoute) { this.maxPerRoute = maxPerRoute; }

    public Duration getConnectTimeout() { return connectTimeout; }
    public void setConnectTimeout(Duration connectTimeout) { this.connectTimeout = connectTimeout; }

    public Duration getResponseTimeout() { return responseTimeout; }
    public void setResponseTimeout(Duration responseTimeout) { this.responseTimeout = responseTimeout; }

    public Duration getConnectionRequestTimeout() { return connectionRequestTimeout; }
    public void setConnectionRequestTimeout(Duration connectionRequestTimeout) {
        this.connectionRequestTimeout = connectionRequestTimeout;
    }

    public Duration getIdleEviction() { return idleEviction; }
    public void setIdleEviction(Duration idleEviction) { this.idleEviction = idleEviction; }

    public Duration getTimeToLive() { return timeToLive; }
    public void setTimeToLive(Duration timeToLive) { this.timeToLive = timeToLive; }

    public Duration getValidateAfterInactivity() { return validateAfterInactivity; }
    public void setValidateAfterInactivity(Duration validateAfterInactivity) {
        this.validateAfterInactivity = validateAfterInactivity;
    }
}
