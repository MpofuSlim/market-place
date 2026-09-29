package com.innbucks.marketplaceservice.seller;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a read that renders seller names from the organization registry, and
 * is therefore deliberately NOT {@code @Transactional}.
 *
 * <p>A seller nobody here has named is named by user-service, over HTTP
 * ({@link UserServiceOrganizationNameResolver}). Inside a transaction that call
 * would hold a pooled connection for its whole latency — which is how a
 * user-service stall became pool exhaustion for the entire service — so the
 * resolver refuses to call out while one is active and answers from its cache
 * alone. A read that wants FRESH names must therefore run its queries
 * outside a surrounding transaction: each repository call commits on its own
 * and releases its connection, and the name lookup that follows holds none.
 * (Under Postgres' READ COMMITTED each statement took its own snapshot even
 * inside the old read-only transaction, so no consistency was given up.)
 *
 * <p>This only works with {@code spring.jpa.open-in-view: false}: with it on,
 * the request-bound EntityManager keeps the connection of its first query
 * until the response is written, transaction or not.
 *
 * <p>Purely a marker — it changes nothing at runtime. It exists so the reason
 * is written down once rather than beside every method, and so
 * {@code MerchantNameResolutionTest} can fail the build if one of these reads
 * is made {@code @Transactional} again (which would not break anything
 * visibly: names would just silently stop refreshing). A write that answers
 * with names can carry it too, when it commits its mutation in its own
 * transaction and renders afterwards (the cart's add / set / remove). Other
 * write paths that render names stay transactional and get cached names — see
 * the resolver.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface NameResolvingRead {
}
