package com.edwardmagongo.ledgerapi.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    private static final String BEARER_SCHEME = "bearerAuth";

    @Bean
    OpenAPI ledgerOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("LedgerAPI")
                        .version("0.0.1")
                        .description("""
                                A banking ledger API with atomic, concurrency-safe transfers.

                                Register, then log in to obtain a JWT. Click **Authorize** and paste
                                the raw token to call the protected endpoints below."""))
                .addSecurityItem(new SecurityRequirement().addList(BEARER_SCHEME))
                .components(new Components().addSecuritySchemes(BEARER_SCHEME,
                        new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")));
    }
}
