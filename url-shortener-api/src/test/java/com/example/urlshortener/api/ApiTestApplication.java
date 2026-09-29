package com.example.urlshortener.api;

import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Context root for this module's contract tests: the application plane, without the control plane. */
@SpringBootApplication(scanBasePackages = {
        "com.example.urlshortener.api",
        "com.example.urlshortener.infrastructure"})
public class ApiTestApplication {
}
