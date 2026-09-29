package com.example.urlshortener.app;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeIn;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/** The single process hosting both planes. */
@SpringBootApplication(scanBasePackages = {
        "com.example.urlshortener.app",
        "com.example.urlshortener.api",
        "com.example.urlshortener.infrastructure",
        "com.example.urlshortener.orchestration.api",
        "com.example.urlshortener.orchestration.infrastructure"})
@EnableScheduling
@OpenAPIDefinition(
        info = @Info(
                title = "Agentic Software Engineering System — URL Shortener",
                version = "1.1.0",
                description = "Application plane (URL shortener) and control plane (governed SDLC orchestration)."),
        security = @SecurityRequirement(name = "ApiKey"))
// Declared so Swagger UI shows an Authorize button; without it the protected endpoints
// cannot be tried from the browser.
@SecurityScheme(
        name = "ApiKey",
        type = SecuritySchemeType.APIKEY,
        in = SecuritySchemeIn.HEADER,
        paramName = "X-Api-Key",
        description = "Use demo-operator-key (read, write, control) or demo-reviewer-key (read only).")
public class Application {

    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}
