package com.innbucks.marketplaceservice.customersupport;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every customer-support endpoint names a support PERMISSION and never a role.
 * A role check here would freeze today's role design into code: a custom role
 * an operator composes with {@code marketplace-support:read} would silently get
 * nothing, and SUPER_ADMIN reaches these through its wildcard, not its name.
 */
class SupportEndpointsArePermissionGatedTest {

    private static final Set<String> ALLOWED = Set.of(
            SupportPermissions.CAN_READ, SupportPermissions.CAN_MANAGE,
            SupportPermissions.CAN_SUPERVISE, SupportPermissions.CAN_MESSAGE);

    /** Every controller under {@code /marketplace/support}. A new one must be
     *  added here — {@link #everySupportControllerIsListed} fails until it is. */
    private static final List<Class<?>> CONTROLLERS = List.of(
            SupportController.class, SupportMessageController.class, SupportActionController.class);

    @Test
    @DisplayName("each handler carries exactly one support-permission check, and no role")
    void everyHandlerIsPermissionGated() {
        for (Class<?> controller : CONTROLLERS) {
            assertThat(controller.getAnnotation(PreAuthorize.class))
                    .as("a class-level check on %s would apply to handlers without saying so",
                            controller.getSimpleName()).isNull();
            List<Method> handlers = Arrays.stream(controller.getDeclaredMethods())
                    .filter(SupportEndpointsArePermissionGatedTest::isHandler)
                    .toList();
            assertThat(handlers).as(controller.getSimpleName()).isNotEmpty();
            for (Method handler : handlers) {
                String name = controller.getSimpleName() + "." + handler.getName();
                PreAuthorize check = handler.getAnnotation(PreAuthorize.class);
                assertThat(check).as(name).isNotNull();
                assertThat(check.value()).as(name).isIn(ALLOWED).doesNotContain("hasRole");
            }
        }
    }

    @Test
    @DisplayName("every controller mapped under /marketplace/support is checked above")
    void everySupportControllerIsListed() throws Exception {
        var scanner = new org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new org.springframework.core.type.filter.AnnotationTypeFilter(
                org.springframework.web.bind.annotation.RestController.class));
        List<Class<?>> mapped = new java.util.ArrayList<>();
        for (var candidate : scanner.findCandidateComponents("com.innbucks.marketplaceservice")) {
            Class<?> type = Class.forName(candidate.getBeanClassName());
            RequestMapping mapping = type.getAnnotation(RequestMapping.class);
            if (mapping != null && Arrays.stream(mapping.value()).anyMatch(p -> p.startsWith("/marketplace/support"))) {
                mapped.add(type);
            }
        }
        assertThat(mapped).containsExactlyInAnyOrderElementsOf(CONTROLLERS);
    }

    private static boolean isHandler(Method method) {
        return method.isAnnotationPresent(GetMapping.class) || method.isAnnotationPresent(PostMapping.class)
                || method.isAnnotationPresent(PutMapping.class) || method.isAnnotationPresent(PatchMapping.class)
                || method.isAnnotationPresent(DeleteMapping.class)
                || method.isAnnotationPresent(RequestMapping.class);
    }
}
