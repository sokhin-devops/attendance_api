package com.attendance.api.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class OpenApiConfig {

    private static final String SECURITY_SCHEME = "bearerAuth";

    @Bean
    public OpenAPI attendanceOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Attendance API")
                        .version("v1")
                        .description("""
                                Multi-tenant employee attendance platform.

                                **Authentication** — call `POST /api/v1/auth/login`, then send
                                `Authorization: Bearer <accessToken>` on every other endpoint.

                                **Roles** — `SUPER_ADMIN` is platform-scoped (no organization);
                                `ORG_ADMIN`, `MANAGER` and `EMPLOYEE` are scoped to one organization
                                and can only ever read or write data inside it. The required role is
                                noted in each operation's description.
                                """)
                        .contact(new Contact().name("Attendance Platform"))
                        .license(new License().name("Proprietary")))
                .servers(List.of(new Server().url("http://localhost:8081").description("Local")))
                .components(new Components().addSecuritySchemes(SECURITY_SCHEME,
                        new SecurityScheme()
                                .name(SECURITY_SCHEME)
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .description("Access token from the login or refresh endpoint")))
                .addSecurityItem(new SecurityRequirement().addList(SECURITY_SCHEME));
    }
}
