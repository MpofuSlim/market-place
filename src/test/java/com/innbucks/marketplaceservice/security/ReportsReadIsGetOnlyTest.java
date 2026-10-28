package com.innbucks.marketplaceservice.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code marketplace-reports:read} is READ-ONLY: walks every handler of every
 * {@code @RestController} and fails if any handler that is not a GET names the
 * permission in its effective {@code @PreAuthorize} (the method's own, else the
 * class's). It also pins the exact set of GETs granted, so a new grant, or one
 * on an excluded read (a payout destination, the payout report), is a decision
 * someone makes in this file rather than a side effect.
 */
class ReportsReadIsGetOnlyTest {

    /** Every GET a reports reader may call: Controller.method. */
    private static final Set<String> GRANTED_GETS = Set.of(
            "ListingController.get",
            "ListingController.listMine",
            "OrderController.getAll",
            "OrderController.getOrder",
            "OrderController.tracking",
            "FulfilmentController.queue",
            "FulfilmentController.stats",
            "FulfilmentController.byTrackingCode",
            "SettlementController.list",
            "SettlementController.statement",
            "SettlementController.summary",
            "SettlementController.disputes",
            "SettlementController.stale",
            "ModerationController.queue",
            "SellerAdminController.list",
            "SellerAdminController.collection",
            "AdminCollectionPointController.list");

    @Test
    @DisplayName("no handler but a GET names marketplace-reports:read")
    void noWriteNamesThePermission() throws Exception {
        List<String> offenders = new ArrayList<>();
        for (Handler handler : handlers()) {
            if (!handler.getOnly() && handler.grants()) {
                offenders.add(handler.name() + " " + handler.methods());
            }
        }
        assertThat(offenders).as("handlers that are not GET-only yet admit the reports reader")
                .isEmpty();
    }

    @Test
    @DisplayName("exactly the reviewed GETs admit the reports reader")
    void grantedGetsArePinned() throws Exception {
        Set<String> granted = new TreeSet<>();
        for (Handler handler : handlers()) {
            if (handler.grants()) {
                granted.add(handler.name());
            }
        }
        assertThat(granted).containsExactlyInAnyOrderElementsOf(GRANTED_GETS);
    }

    @Test
    @DisplayName("the walker sees a write that names the permission (it is not vacuous)")
    void walkerCatchesAWrite() {
        Handler write = handler(Fixture.class, "write");
        Handler read = handler(Fixture.class, "read");
        Handler unrestricted = handler(Fixture.class, "anyVerb");
        assertThat(write.getOnly()).isFalse();
        assertThat(write.grants()).isTrue();
        assertThat(read.getOnly()).isTrue();
        assertThat(unrestricted.getOnly()).as("a @RequestMapping with no method answers every verb")
                .isFalse();
        assertThat(unrestricted.grants()).as("class-level @PreAuthorize applies").isTrue();
    }

    // ------------------------------------------------------------------

    private record Handler(Class<?> controller, Method method, Set<RequestMethod> methods,
                           String expression) {

        String name() {
            return controller.getSimpleName() + "." + method.getName();
        }

        boolean getOnly() {
            return !methods.isEmpty() && Set.of(RequestMethod.GET, RequestMethod.HEAD).containsAll(methods);
        }

        boolean grants() {
            return expression != null && expression.contains(PlatformReader.REPORTS_READ);
        }
    }

    private static List<Handler> handlers() throws ClassNotFoundException {
        var scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
        List<Handler> handlers = new ArrayList<>();
        for (var candidate : scanner.findCandidateComponents("com.innbucks.marketplaceservice")) {
            Class<?> type = Class.forName(candidate.getBeanClassName());
            for (Method method : type.getDeclaredMethods()) {
                if (AnnotatedElementUtils.hasAnnotation(method, RequestMapping.class)) {
                    handlers.add(handler(type, method.getName()));
                }
            }
        }
        assertThat(handlers).as("the scan found the controllers").hasSizeGreaterThan(50);
        return handlers;
    }

    private static Handler handler(Class<?> type, String methodName) {
        Method method = java.util.Arrays.stream(type.getDeclaredMethods())
                .filter(m -> m.getName().equals(methodName)
                        && AnnotatedElementUtils.hasAnnotation(m, RequestMapping.class))
                .findFirst().orElseThrow();
        RequestMapping mapping = AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class);
        PreAuthorize own = AnnotatedElementUtils.findMergedAnnotation(method, PreAuthorize.class);
        PreAuthorize inherited = AnnotatedElementUtils.findMergedAnnotation(type, PreAuthorize.class);
        String expression = own != null ? own.value() : inherited != null ? inherited.value() : null;
        return new Handler(type, method, Set.of(mapping.method()), expression);
    }

    /** The regression the walker exists for, built rather than committed. */
    @PreAuthorize("hasRole('SUPER_ADMIN')" + PlatformReader.OR_CAN_READ)
    static class Fixture {

        @org.springframework.web.bind.annotation.PostMapping("/x")
        @PreAuthorize("hasRole('SUPER_ADMIN')" + PlatformReader.OR_CAN_READ)
        void write() {
        }

        @org.springframework.web.bind.annotation.GetMapping("/x")
        void read() {
        }

        @RequestMapping("/y")
        void anyVerb() {
        }
    }
}
