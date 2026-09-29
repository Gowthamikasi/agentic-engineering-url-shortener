package com.example.urlshortener.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Contract tests for the application-plane API, executed against the real wiring. */
@SpringBootTest
@AutoConfigureMockMvc
class LinksApiContractTest {

    private static final String OPERATOR_KEY = "demo-operator-key";
    private static final String REVIEWER_KEY = "demo-reviewer-key";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void reset() {
        jdbc.execute("DELETE FROM clicks");
        jdbc.execute("DELETE FROM idempotency");
        jdbc.execute("DELETE FROM links");
    }

    private String createLink(String url, String expiresAt) throws Exception {
        String body = expiresAt == null
                ? "{\"url\":\"" + url + "\"}"
                : "{\"url\":\"" + url + "\",\"expiresAt\":\"" + expiresAt + "\"}";

        MvcResult result = mvc.perform(post("/api/v1/links")
                        .header("X-Api-Key", OPERATOR_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn();

        return json.readTree(result.getResponse().getContentAsString()).get("code").asText();
    }

    // ------------------------------------------------------------------ create

    @Test
    void creating_a_link_returns_201_with_the_documented_body_and_headers() throws Exception {
        mvc.perform(post("/api/v1/links")
                        .header("X-Api-Key", OPERATOR_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"https://example.org/docs/getting-started\"}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("API-Version", "1.1.0"))
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.code").isString())
                .andExpect(jsonPath("$.shortUrl").value(org.hamcrest.Matchers.startsWith("http://localhost:8080/")))
                .andExpect(jsonPath("$.target").value("https://example.org/docs/getting-started"))
                .andExpect(jsonPath("$.createdAt").isString());
    }

    @Test
    void a_created_code_is_seven_base62_characters() throws Exception {
        String code = createLink("https://example.org/a", null);

        assertThat(code).hasSize(7).matches("[0-9A-Za-z]{7}");
    }

    @Test
    void an_expiry_is_echoed_back_unchanged() throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/links")
                        .header("X-Api-Key", OPERATOR_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"https://example.org/x\",\"expiresAt\":\"2030-12-31T23:59:59Z\"}"))
                .andExpect(status().isCreated())
                .andReturn();

        JsonNode body = json.readTree(result.getResponse().getContentAsString());
        assertThat(body.get("expiresAt").asText()).isEqualTo("2030-12-31T23:59:59Z");
    }

    @Test
    void an_expiry_in_the_past_is_rejected_with_400_and_a_problem_body() throws Exception {
        mvc.perform(post("/api/v1/links")
                        .header("X-Api-Key", OPERATOR_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"https://example.org/x\",\"expiresAt\":\"2020-01-01T00:00:00Z\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.errorCode").value("EXPIRY_IN_PAST"));
    }

    @Test
    void a_malformed_expiry_is_rejected_with_400() throws Exception {
        mvc.perform(post("/api/v1/links")
                        .header("X-Api-Key", OPERATOR_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"https://example.org/x\",\"expiresAt\":\"next tuesday\"}"))
                .andExpect(status().isBadRequest());
    }

    @ParameterizedTest
    @ValueSource(strings = {"javascript:alert(1)", "file:///etc/passwd", "ftp://example.org/x"})
    void a_disallowed_scheme_is_rejected_with_400(String url) throws Exception {
        mvc.perform(post("/api/v1/links")
                        .header("X-Api-Key", OPERATOR_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"" + url + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode")
                        .value(org.hamcrest.Matchers.in(java.util.List.of("URL_SCHEME_NOT_ALLOWED", "URL_MALFORMED"))));
    }

    @Test
    void an_internal_host_name_is_rejected_with_422_blocked_host() throws Exception {
        mvc.perform(post("/api/v1/links")
                        .header("X-Api-Key", OPERATOR_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"https://db.internal/secrets\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value("URL_HOST_BLOCKED"));
    }

    @Test
    void a_missing_url_is_rejected_with_400() throws Exception {
        mvc.perform(post("/api/v1/links")
                        .header("X-Api-Key", OPERATOR_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    // ------------------------------------------------------------------ idempotency

    @Test
    void the_same_idempotency_key_and_body_replays_the_original_link() throws Exception {
        String body = "{\"url\":\"https://example.org/idem\"}";

        MvcResult first = mvc.perform(post("/api/v1/links")
                        .header("X-Api-Key", OPERATOR_KEY).header("Idempotency-Key", "idem-1")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();

        MvcResult second = mvc.perform(post("/api/v1/links")
                        .header("X-Api-Key", OPERATOR_KEY).header("Idempotency-Key", "idem-1")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andReturn();

        String firstCode = json.readTree(first.getResponse().getContentAsString()).get("code").asText();
        String secondCode = json.readTree(second.getResponse().getContentAsString()).get("code").asText();
        assertThat(secondCode).isEqualTo(firstCode);
    }

    @Test
    void the_same_idempotency_key_with_a_different_body_is_a_409() throws Exception {
        mvc.perform(post("/api/v1/links")
                        .header("X-Api-Key", OPERATOR_KEY).header("Idempotency-Key", "idem-2")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"url\":\"https://example.org/one\"}"))
                .andExpect(status().isCreated());

        mvc.perform(post("/api/v1/links")
                        .header("X-Api-Key", OPERATOR_KEY).header("Idempotency-Key", "idem-2")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"url\":\"https://example.org/two\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("IDEMPOTENCY_CONFLICT"));
    }

    @Test
    void without_an_idempotency_key_the_same_url_mints_a_new_code() throws Exception {
        String first = createLink("https://example.org/same", null);
        String second = createLink("https://example.org/same", null);

        assertThat(second).isNotEqualTo(first);
    }

    // ------------------------------------------------------------------ redirect

    @Test
    void following_a_live_link_returns_302_to_the_stored_target() throws Exception {
        String code = createLink("https://example.org/target", null);

        mvc.perform(get("/" + code))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "https://example.org/target"))
                .andExpect(header().string("Referrer-Policy", "no-referrer"));
    }

    @Test
    void following_an_expired_link_returns_410_gone() throws Exception {
        String code = createLink("https://example.org/expiring", Instant.now().plusSeconds(1).toString());

        Thread.sleep(1200);

        mvc.perform(get("/" + code))
                .andExpect(status().isGone())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.errorCode").value("LINK_EXPIRED"));
    }

    @Test
    void following_an_unknown_code_returns_404() throws Exception {
        mvc.perform(get("/doesNot1"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("LINK_NOT_FOUND"));
    }

    @Test
    void the_redirect_endpoint_needs_no_api_key() throws Exception {
        String code = createLink("https://example.org/public", null);

        mvc.perform(get("/" + code)).andExpect(status().isFound());
    }

    // ------------------------------------------------------------------ read, stats, delete

    @Test
    void metadata_can_be_read_back_by_code() throws Exception {
        String code = createLink("https://example.org/meta", null);

        mvc.perform(get("/api/v1/links/" + code).header("X-Api-Key", REVIEWER_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.target").value("https://example.org/meta"));
    }

    @Test
    void stats_report_clicks_and_stay_readable_after_expiry() throws Exception {
        String code = createLink("https://example.org/stats", Instant.now().plusSeconds(1).toString());
        mvc.perform(get("/" + code)).andExpect(status().isFound());
        mvc.perform(get("/" + code)).andExpect(status().isFound());

        Thread.sleep(1500);
        mvc.perform(get("/" + code)).andExpect(status().isGone());

        mvc.perform(get("/api/v1/links/" + code + "/stats").header("X-Api-Key", REVIEWER_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.totalClicks").value(2))
                .andExpect(jsonPath("$.consistency").value("eventual (<=1s)"));
    }

    @Test
    void deleting_a_link_returns_204_and_then_404() throws Exception {
        String code = createLink("https://example.org/gone", null);

        mvc.perform(delete("/api/v1/links/" + code).header("X-Api-Key", OPERATOR_KEY))
                .andExpect(status().isNoContent());
        mvc.perform(delete("/api/v1/links/" + code).header("X-Api-Key", OPERATOR_KEY))
                .andExpect(status().isNotFound());
        mvc.perform(get("/" + code)).andExpect(status().isNotFound());
    }

    // ------------------------------------------------------------------ auth

    @Test
    void creating_without_a_key_is_401() throws Exception {
        mvc.perform(post("/api/v1/links")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"https://example.org/x\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("UNAUTHORIZED"));
    }

    @Test
    void creating_with_a_wrong_key_is_401() throws Exception {
        mvc.perform(post("/api/v1/links")
                        .header("X-Api-Key", "not-a-real-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"https://example.org/x\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void a_read_only_key_cannot_create_or_delete() throws Exception {
        mvc.perform(post("/api/v1/links")
                        .header("X-Api-Key", REVIEWER_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"https://example.org/x\"}"))
                .andExpect(status().isUnauthorized());

        String code = createLink("https://example.org/protected", null);
        mvc.perform(delete("/api/v1/links/" + code).header("X-Api-Key", REVIEWER_KEY))
                .andExpect(status().isUnauthorized());
    }

    // ------------------------------------------------------------------ health

    @Test
    void liveness_and_readiness_are_public_and_report_their_components() throws Exception {
        mvc.perform(get("/health/live")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
        mvc.perform(get("/health/ready")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.database").value("UP"))
                .andExpect(jsonPath("$.analyticsQueue").value("UP"));
    }
}
