package com.innbucks.marketplaceservice.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class OpenApiConfig {

    /**
     * Path prefix the public edge mounts the fleet under. Swagger UI resolves
     * this server URL in the BROWSER against the public origin, so it must
     * carry the prefix even though nginx strips it before the request reaches
     * any service. Blank (the default) falls back to "/" — the domain-root
     * behavior for local dev.
     */
    @Value("${PUBLIC_API_PREFIX:}")
    private String publicApiPrefix;

    @Bean
    public OpenAPI openAPI() {
        final String securitySchemeName = "bearerAuth";

        Server server = new Server();
        server.setUrl(publicApiPrefix.isBlank() ? "/" : publicApiPrefix);
        server.setDescription("Gateway relative server");

        return new OpenAPI()
                .servers(List.of(server))
                .info(new Info()
                        .title("InnBucks Marketplace API")
                        .version("1.0.0")
                        .description("""
                                InnBucks Marketplace — merchant product listings, a public catalog, \
                                and buyer orders.

                                **Identity boundary:** marketplace-service does NOT mint tokens. It \
                                verifies fleet JWTs issued by user-service; merchant scope comes from \
                                the token's `merchantId`/`shopId` claims, never from a request body.

                                **Money:** all amounts are minor units (cents). **Time:** all \
                                timestamps are UTC instants with the `Z` designator.

                                **Errors:** every response uses the fleet `ApiResult` envelope \
                                `{ "code": ..., "message": ..., "data": ... }`.

                                Internal S2S endpoints (`/marketplace/internal/**`) are excluded from \
                                this spec and edge-denied at the fleet gateway.
                                """))
                .addSecurityItem(new SecurityRequirement().addList(securitySchemeName))
                .components(new Components()
                        .addSecuritySchemes(securitySchemeName,
                                new SecurityScheme()
                                        .name(securitySchemeName)
                                        .type(SecurityScheme.Type.HTTP)
                                        .scheme("bearer")
                                        .bearerFormat("JWT")));
    }
}
