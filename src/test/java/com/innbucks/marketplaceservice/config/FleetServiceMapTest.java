package com.innbucks.marketplaceservice.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.discovery.simple.SimpleDiscoveryClient;
import org.springframework.cloud.client.discovery.simple.SimpleDiscoveryProperties;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins this service's copy of the fleet service-discovery map — the
 * {@code simple.instances} block at the end of application.yaml that replaced
 * Eureka.
 *
 * <p>The map is the only thing turning {@code http://user-service} into an
 * address, and every way it can go wrong is silent until the call is made in
 * the cell: a missing entry is a "No servers available" 503, a wrong port a
 * connection refused. The same map lives in every ticketing-system service,
 * where its own {@code FleetServiceMapTest} checks each port against the k8s
 * Services; {@link #FLEET} is that map, so a change made in one repo and not
 * the other fails here.
 *
 * <p>Pure JUnit, no Spring context and no Docker.
 */
class FleetServiceMapTest {

    /** The fleet map. Keep identical to every copy in ticketing-system and InnRewards. */
    private static final Map<String, Integer> FLEET = new TreeMap<>(Map.of(
            "user-service", 8081,
            "event-service", 8082,
            "seat-service", 8083,
            "booking-service", 8084,
            "payment-service", 8085,
            "loyalty-service", 8086,
            "marketplace-service", 8087));

    private static final String PREFIX = "spring.cloud.discovery.client.simple.instances";
    private static final Pattern ENTRY = Pattern.compile(
            Pattern.quote(PREFIX) + "\\.([a-z-]+)\\[0]\\.uri");
    private static final Pattern SIBLING_ADDRESS = Pattern.compile("http://([a-z]+-service)\\b");

    @Test
    void theMapIsTheFleetMap_eachNameOnItsOwnServiceAndPort() throws IOException {
        Map<String, String> map = map(null);
        assertThat(map.keySet()).isEqualTo(FLEET.keySet());
        for (Map.Entry<String, String> e : map.entrySet()) {
            URI uri = URI.create(e.getValue());
            assertThat(uri.getScheme()).as(e.getKey()).isEqualTo("http");
            assertThat(uri.getHost()).as(e.getKey() + " host must be its k8s Service name")
                    .isEqualTo(e.getKey());
            assertThat(uri.getPort()).as(e.getKey()).isEqualTo(FLEET.get(e.getKey()));
        }
    }

    @Test
    void theLocalProfileIsTheSameNamesAndPortsOnLocalhost() throws IOException {
        Map<String, String> local = map("local");
        assertThat(local.keySet()).isEqualTo(FLEET.keySet());
        for (Map.Entry<String, String> e : local.entrySet()) {
            URI uri = URI.create(e.getValue());
            assertThat(uri.getHost()).as(e.getKey()).isEqualTo("localhost");
            assertThat(uri.getPort()).as(e.getKey()).isEqualTo(FLEET.get(e.getKey()));
        }
    }

    @Test
    void everySiblingAddressInMainCodeHasAnEntry() throws IOException {
        Set<String> called = new TreeSet<>();
        try (Stream<Path> files = Files.walk(Path.of("src/main"))) {
            for (Path f : files.filter(p -> p.toString().endsWith(".java")
                    || p.toString().endsWith(".yaml") || p.toString().endsWith(".yml")).toList()) {
                Matcher m = SIBLING_ADDRESS.matcher(Files.readString(f));
                while (m.find()) called.add(m.group(1));
            }
        }
        assertThat(called).as("this service addresses siblings by name").contains("user-service");
        assertThat(map(null).keySet()).as("http://<name> addresses with no discovery entry")
                .containsAll(called);
    }

    @Test
    void eurekaIsNotConfiguredAnyMore() throws IOException {
        for (PropertySource<?> doc : documents()) {
            if (doc instanceof MapPropertySource m) {
                assertThat(m.getSource().keySet()).noneMatch(k -> k.startsWith("eureka."));
            }
        }
    }

    @Test
    void theMapBindsIntoSpringsDiscoveryClientAndResolvesEachName() throws IOException {
        SimpleDiscoveryProperties properties = new Binder(ConfigurationPropertySources.from(document(null)))
                .bind("spring.cloud.discovery.client.simple", Bindable.of(SimpleDiscoveryProperties.class))
                .orElseThrow(() -> new AssertionError("simple discovery properties did not bind"));
        properties.afterPropertiesSet();
        SimpleDiscoveryClient client = new SimpleDiscoveryClient(properties);

        for (Map.Entry<String, Integer> e : FLEET.entrySet()) {
            List<ServiceInstance> instances = client.getInstances(e.getKey());
            assertThat(instances).as(e.getKey()).hasSize(1);
            assertThat(instances.get(0).getHost()).as(e.getKey()).isEqualTo(e.getKey());
            assertThat(instances.get(0).getPort()).as(e.getKey()).isEqualTo(e.getValue());
        }
    }

    // ---- helpers -------------------------------------------------------------

    private static List<PropertySource<?>> documents() throws IOException {
        return new YamlPropertySourceLoader().load("application",
                new ClassPathResource("application.yaml"));
    }

    private static MapPropertySource document(String profile) throws IOException {
        List<MapPropertySource> found = new ArrayList<>();
        for (PropertySource<?> doc : documents()) {
            if (!(doc instanceof MapPropertySource m)) continue;
            Object activation = m.getProperty("spring.config.activate.on-profile");
            boolean hasMap = m.getSource().keySet().stream().anyMatch(k -> k.startsWith(PREFIX + "."));
            boolean profileMatches = profile == null
                    ? activation == null
                    : profile.equals(String.valueOf(activation));
            if (hasMap && profileMatches) found.add(m);
        }
        assertThat(found).as("documents carrying the "
                + (profile == null ? "default" : profile) + " discovery map").hasSize(1);
        return found.get(0);
    }

    private static Map<String, String> map(String profile) throws IOException {
        Map<String, String> out = new TreeMap<>();
        for (Map.Entry<String, Object> e : document(profile).getSource().entrySet()) {
            Matcher m = ENTRY.matcher(e.getKey());
            if (m.matches()) out.put(m.group(1), String.valueOf(e.getValue()));
        }
        return out;
    }
}
