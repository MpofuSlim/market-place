package com.innbucks.marketplaceservice.testsupport;

import com.innbucks.marketplaceservice.config.OutboundHttp;

/**
 * One pooled outbound client shared by the pure-JUnit contract tests, so they
 * exercise exactly the transport production uses (the pooled httpclient5
 * factory) rather than Spring's classpath-detected default. Shared — not one
 * per test — because each pool runs its own idle-connection evictor thread.
 */
public final class TestOutboundHttp {

    public static final OutboundHttp POOL = OutboundHttp.withDefaults();

    private TestOutboundHttp() {
    }
}
