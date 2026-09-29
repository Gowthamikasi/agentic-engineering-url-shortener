package com.example.urlshortener.orchestration.agents;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The brownfield reasoning, tested against a real directory tree.
 *
 * <p>These use a temporary repository rather than this one, so the assertions stay true when files
 * here are renamed. The point being checked is the behaviour — that the scan names files that
 * actually exist and mention the requirement's vocabulary, and that it says so honestly when
 * nothing matches.
 */
class CodebaseScannerTest {

    private static void write(Path root, String relative, String content) throws IOException {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    /** A miniature repository shaped like the real one. */
    private static void buildRepository(Path root) throws IOException {
        write(root, "url-shortener-domain/src/main/java/Alphabet.java",
                "public enum Alphabet { BASE62, BASE58; String characters() { return \"\"; } }");
        write(root, "url-shortener-domain/src/main/java/ShortCodeGenerator.java",
                "class ShortCodeGenerator { Alphabet alphabet; String next() { return null; } }");
        write(root, "url-shortener-domain/src/main/java/ShortCodeValidator.java",
                "class ShortCodeValidator { boolean isWellFormed(String code) { return true; } }");
        write(root, "url-shortener-api/src/main/java/LinksController.java",
                "@RestController class LinksController { void create() {} }");
        write(root, "url-shortener-api/src/main/java/ExpiryHandler.java",
                "class ExpiryHandler { void expire() {} }");
        write(root, "url-shortener-infrastructure/src/main/resources/db/migration/V1__links.sql",
                "CREATE TABLE links (code VARCHAR(16) PRIMARY KEY, expires_at TIMESTAMP);");
        // Noise that must not be reported.
        write(root, "url-shortener-domain/src/test/java/AlphabetTest.java",
                "class AlphabetTest { void base58_excludes_ambiguous_glyphs() {} }");
        write(root, "url-shortener-domain/target/classes/Alphabet.class", "compiled base58 alphabet");
    }

    @Test
    void it_finds_the_files_that_actually_mention_the_requirement(@TempDir Path root) throws IOException {
        buildRepository(root);

        CodebaseScanner.Impact impact = new CodebaseScanner(root).scan(
                "New codes must exclude ambiguous glyphs by moving to a base58 alphabet. "
                        + "Existing base62 codes must continue to resolve. The validator must accept "
                        + "both alphabets for lookup.");

        assertThat(impact.isEmpty()).isFalse();
        assertThat(impact.matches()).extracting(CodebaseScanner.Match::path)
                .anyMatch(p -> p.endsWith("Alphabet.java"))
                .anyMatch(p -> p.endsWith("ShortCodeGenerator.java"));
        assertThat(impact.modules()).contains("url-shortener-domain");
    }

    @Test
    void it_never_reports_test_sources_or_build_output_as_impacted() throws IOException {
        Path root = Files.createTempDirectory("scan");
        buildRepository(root);

        CodebaseScanner.Impact impact = new CodebaseScanner(root).scan(
                "Move the short code alphabet from base62 to base58 so ambiguous glyphs are excluded "
                        + "from newly minted codes.");

        assertThat(impact.matches()).extracting(CodebaseScanner.Match::path)
                .noneMatch(p -> p.contains("/src/test/"))
                .noneMatch(p -> p.contains("/target/"));
    }

    @Test
    void a_schema_file_is_classified_as_a_data_flow_and_a_controller_as_api(@TempDir Path root) throws IOException {
        buildRepository(root);

        CodebaseScanner.Impact impact = new CodebaseScanner(root).scan(
                "Links table rows must record an expires_at column so an expired links code returns gone.");

        assertThat(impact.dataFlows()).anyMatch(p -> p.endsWith(".sql"));
    }

    /**
     * The honest-empty case. Returning a plausible-looking guess here would be worse than useless:
     * it would read exactly like a real finding at the approval gate.
     */
    @Test
    void an_unrelated_requirement_reports_nothing_rather_than_guessing(@TempDir Path root) throws IOException {
        buildRepository(root);

        CodebaseScanner.Impact impact = new CodebaseScanner(root).scan(
                "Introduce quarterly invoicing with proration across billing accounts.");

        assertThat(impact.isEmpty()).isTrue();
        assertThat(impact.modules()).isEmpty();
        assertThat(impact.filesScanned()).isGreaterThan(0);
    }

    @Test
    void common_requirement_words_are_not_treated_as_search_terms() {
        List<String> terms = CodebaseScanner.significantTerms(
                "The system must ensure that users should be able to continue using this existing feature.");

        assertThat(terms).doesNotContain("must", "should", "ensure", "continue", "existing", "system",
                "users", "feature", "this", "that");
    }

    @Test
    void short_tokens_and_duplicates_are_dropped() {
        List<String> terms = CodebaseScanner.significantTerms("a an the code code CODE alphabet");

        assertThat(terms).containsExactly("code", "alphabet");
    }

    @Test
    void an_empty_or_missing_requirement_yields_no_terms_and_no_matches(@TempDir Path root) {
        assertThat(CodebaseScanner.significantTerms(null)).isEmpty();
        assertThat(CodebaseScanner.significantTerms("   ")).isEmpty();
        assertThat(new CodebaseScanner(root).scan("").isEmpty()).isTrue();
    }

    @Test
    void a_directory_that_does_not_exist_is_reported_as_zero_files_rather_than_throwing() {
        CodebaseScanner.Impact impact =
                new CodebaseScanner(Path.of("no", "such", "directory")).scan("alphabet base58 codes");

        assertThat(impact.filesScanned()).isZero();
        assertThat(impact.isEmpty()).isTrue();
    }
}
