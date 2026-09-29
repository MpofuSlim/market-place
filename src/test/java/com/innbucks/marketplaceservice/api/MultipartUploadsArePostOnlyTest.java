package com.innbucks.marketplaceservice.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.http.MediaType;
import org.springframework.util.ClassUtils;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every multipart upload in the service is POST, and only POST. The edge WAF
 * refuses PUT + multipart/form-data before the request reaches the service,
 * and the browser then reports a bare network error with nothing in any
 * server log, so a multipart PUT is an endpoint that cannot be called from
 * the portal at all. A request mapping counts as multipart when it consumes
 * multipart/form-data or takes a file / request part.
 */
class MultipartUploadsArePostOnlyTest {

    @Test
    @DisplayName("Every multipart endpoint is mapped to POST only")
    void everyMultipartEndpointIsPostOnly() throws Exception {
        Map<String, Set<RequestMethod>> multipart = multipartEndpoints();

        assertThat(multipart).as("the scan found the listing uploads").containsKeys(
                "ListingController#createWithImages",
                "ListingController#uploadImage",
                "ListingController#addImage");
        multipart.forEach((endpoint, methods) ->
                assertThat(methods).as("HTTP methods of multipart endpoint %s", endpoint)
                        .containsExactly(RequestMethod.POST));
    }

    private static Map<String, Set<RequestMethod>> multipartEndpoints() throws ClassNotFoundException {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
        Map<String, Set<RequestMethod>> found = new TreeMap<>();
        for (BeanDefinition candidate : scanner.findCandidateComponents("com.innbucks.marketplaceservice")) {
            Class<?> type = ClassUtils.forName(candidate.getBeanClassName(),
                    MultipartUploadsArePostOnlyTest.class.getClassLoader());
            if (isTestClass(type)) {
                continue;
            }
            for (Method method : type.getDeclaredMethods()) {
                RequestMapping mapping = AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class);
                if (mapping != null && isMultipart(method, mapping)) {
                    found.put(type.getSimpleName() + "#" + method.getName(), Set.of(mapping.method()));
                }
            }
        }
        return found;
    }

    private static boolean isMultipart(Method method, RequestMapping mapping) {
        boolean consumesMultipart = Arrays.stream(mapping.consumes())
                .anyMatch(type -> MediaType.parseMediaType(type).isCompatibleWith(MediaType.MULTIPART_FORM_DATA));
        boolean takesPart = Arrays.stream(method.getParameters()).anyMatch(MultipartUploadsArePostOnlyTest::isPart);
        return consumesMultipart || takesPart;
    }

    private static boolean isPart(Parameter parameter) {
        return parameter.isAnnotationPresent(RequestPart.class)
                || MultipartFile.class.isAssignableFrom(parameter.getType());
    }

    /** Stub controllers in test sources are not the service's API. */
    private static boolean isTestClass(Class<?> type) {
        String location = type.getProtectionDomain().getCodeSource().getLocation().toString();
        return location.contains("test-classes");
    }
}
