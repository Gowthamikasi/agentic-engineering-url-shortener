package com.example.urlshortener.app;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * The single process that hosts both planes.
 *
 * <p>Both planes live in one JVM and one database, but they share nothing above that line: no
 * module of the application plane depends on the control plane or the reverse, and an ArchUnit
 * test fails the build if that ever changes. A modular monolith is the honest shape for a
 * prototype that has to be runnable from a clean clone — the boundaries are real and enforced,
 * without inventing distributed-systems problems the assignment did not ask for.
 */
@SpringBootApplication(scanBasePackages = {
        "com.example.urlshortener.app",
        "com.example.urlshortener.api",
        "com.example.urlshortener.infrastructure",
        "com.example.urlshortener.orchestration.api",
        "com.example.urlshortener.orchestration.infrastructure"})
@EnableScheduling
@OpenAPIDefinition(info = @Info(
        title = "Agentic Software Engineering System — URL Shortener",
        version = "1.1.0",
        description = "Application plane (URL shortener) and control plane (governed SDLC orchestration)."))
public class Application {

    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}
