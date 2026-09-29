package com.example.urlshortener.app.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import com.example.urlshortener.app.Application;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.DefaultResponseErrorHandler;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The application plane over real HTTP on a real port.
 *
 * <p>MockMvc stops short of the container: no real redirect responses, no real filter chain,
 * and no way to show the click queue actually lands rows. These go over a socket.
 */
@Tag("e2e")
@SpringBootTest(classes = Application.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ApplicationPlaneE2ETest {

    private static final String OPERATOR = "demo-operator-key";
    private static final String REVIEWER = "demo-reviewer-key";

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate rest;

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    private HttpEntity<String> request(String body, String apiKey) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (apiKey != null) {
            headers.set("X-Api-Key", apiKey);
        }
        return new HttpEntity<>(body, headers);
    }

    private JsonNode createLink(String targetUrl, String expiresAt) {
        String body = expiresAt == null
                ? "{\"url\":\"" + targetUrl + "\"}"
                : "{\"url\":\"" + targetUrl + "\",\"expiresAt\":\"" + expiresAt + "\"}";

        ResponseEntity<JsonNode> response =
                rest.postForEntity(url("/api/v1/links"), request(body, OPERATOR), JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody();
    }

    /**
     * A client that neither follows redirects nor throws on an error status.
     *
     * <p>Following the redirect would turn the 302 into whatever example.org answers, and the
     * default error handler would turn an expected 410 into a failure.
     */
    private ResponseEntity<String> follow(String code) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory() {
            @Override
            protected void prepareConnection(HttpURLConnection connection, String method) throws IOException {
                super.prepareConnection(connection, method);
                connection.setInstanceFollowRedirects(false);
            }
        };
        RestTemplate client = new RestTemplate(factory);
        client.setErrorHandler(new DefaultResponseErrorHandler() {
            @Override
            public boolean hasError(ClientHttpResponse response) {
                return false;
            }
        });
        return client.getForEntity(url("/" + code), String.class);
    }

    @Test
    void a_link_can_be_created_followed_and_counted_over_real_http() {
        JsonNode created = createLink("https://example.org/e2e/basic", null);
        String code = created.get("code").asText();

        assertThat(code).hasSize(7);
        assertThat(created.get("shortUrl").asText()).endsWith("/" + code);

        ResponseEntity<String> redirect = follow(code);
        assertThat(redirect.getStatusCode()).isEqualTo(HttpStatus.FOUND);
        assertThat(redirect.getHeaders().getLocation()).hasToString("https://example.org/e2e/basic");
        assertThat(redirect.getHeaders().getFirst("Referrer-Policy")).isEqualTo("no-referrer");

        // Clicks are written off the redirect path, so the count lands shortly after. Polling is
        // the point here: it checks the lag is real and bounded.
        JsonNode stats = awaitClicks(code, 1);
        assertThat(stats.get("totalClicks").asLong()).isEqualTo(1);
        assertThat(stats.get("consistency").asText()).isEqualTo("eventual (<=1s)");
    }

    private JsonNode awaitClicks(String code, long expected) {
        long deadline = System.currentTimeMillis() + 5_000;
        JsonNode stats = null;
        while (System.currentTimeMillis() < deadline) {
            stats = rest.exchange(url("/api/v1/links/" + code + "/stats"), HttpMethod.GET,
                    request(null, REVIEWER), JsonNode.class).getBody();
            if (stats != null && stats.get("totalClicks").asLong() >= expected) {
                return stats;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return stats;
    }

    @Test
    void an_expired_link_stops_redirecting_but_its_statistics_remain_readable() throws Exception {
        String code = createLink("https://example.org/e2e/expiring",
                Instant.now().plusSeconds(2).toString()).get("code").asText();

        assertThat(follow(code).getStatusCode()).isEqualTo(HttpStatus.FOUND);
        awaitClicks(code, 1);

        Thread.sleep(2_200);

        ResponseEntity<String> afterExpiry = follow(code);
        assertThat(afterExpiry.getStatusCode()).isEqualTo(HttpStatus.GONE);

        // Gone, but not forgotten.
        JsonNode stats = rest.exchange(url("/api/v1/links/" + code + "/stats"), HttpMethod.GET,
                request(null, REVIEWER), JsonNode.class).getBody();
        assertThat(stats).isNotNull();
        assertThat(stats.get("totalClicks").asLong()).isEqualTo(1);
    }

    @Test
    void an_unknown_code_is_not_found_and_a_deleted_one_stops_resolving() {
        ResponseEntity<String> unknown = follow("zzzzzz9");
        assertThat(unknown.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        String code = createLink("https://example.org/e2e/deleted", null).get("code").asText();
        ResponseEntity<Void> deleted = rest.exchange(url("/api/v1/links/" + code), HttpMethod.DELETE,
                request(null, OPERATOR), Void.class);

        assertThat(deleted.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(follow(code).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void the_full_filter_chain_enforces_keys_and_scopes_over_real_http() {
        ResponseEntity<String> noKey = rest.postForEntity(url("/api/v1/links"),
                request("{\"url\":\"https://example.org/x\"}", null), String.class);
        assertThat(noKey.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        ResponseEntity<String> readOnlyKey = rest.postForEntity(url("/api/v1/links"),
                request("{\"url\":\"https://example.org/x\"}", REVIEWER), String.class);
        assertThat(readOnlyKey.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        // The redirect has to stay open to anonymous traffic.
        String code = createLink("https://example.org/e2e/public", null).get("code").asText();
        assertThat(follow(code).getStatusCode()).isEqualTo(HttpStatus.FOUND);
    }

    @Test
    void security_headers_are_present_on_a_served_response() {
        String code = createLink("https://example.org/e2e/headers", null).get("code").asText();

        HttpHeaders headers = follow(code).getHeaders();

        assertThat(headers.getFirst("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(headers.getFirst("X-Frame-Options")).isEqualTo("DENY");
        assertThat(headers.getFirst("Referrer-Policy")).isEqualTo("no-referrer");
    }

    @Test
    void health_probes_and_the_published_contract_are_reachable_without_a_key() {
        assertThat(rest.getForEntity(url("/health/live"), String.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);

        ResponseEntity<JsonNode> ready = rest.getForEntity(url("/health/ready"), JsonNode.class);
        assertThat(ready.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(ready.getBody().get("database").asText()).isEqualTo("UP");
        assertThat(ready.getBody().get("analyticsQueue").asText()).isEqualTo("UP");

        assertThat(rest.getForEntity(url("/v3/api-docs"), String.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }
}
