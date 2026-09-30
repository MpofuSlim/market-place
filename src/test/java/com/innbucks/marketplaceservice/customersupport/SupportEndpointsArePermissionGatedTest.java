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

    @Test
    @DisplayName("each handler carries exactly one support-permission check, and no role")
    void everyHandlerIsPermissionGated() {
        assertThat(SupportController.class.getAnnotation(PreAuthorize.class))
                .as("a class-level check would apply to handlers without saying so").isNull();
        List<Method> handlers = Arrays.stream(SupportController.class.getDeclaredMethods())
                .filter(SupportEndpointsArePermissionGatedTest::isHandler)
                .toList();
        assertThat(handlers).isNotEmpty();
        for (Method handler : handlers) {
            PreAuthorize check = handler.getAnnotation(PreAuthorize.class);
            assertThat(check).as(handler.getName()).isNotNull();
            assertThat(check.value()).as(handler.getName()).isIn(ALLOWED).doesNotContain("hasRole");
        }
    }

    private static boolean isHandler(Method method) {
        return method.isAnnotationPresent(GetMapping.class) || method.isAnnotationPresent(PostMapping.class)
                || method.isAnnotationPresent(PutMapping.class) || method.isAnnotationPresent(PatchMapping.class)
                || method.isAnnotationPresent(DeleteMapping.class)
                || method.isAnnotationPresent(RequestMapping.class);
    }
}
