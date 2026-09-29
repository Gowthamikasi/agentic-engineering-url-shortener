package com.example.urlshortener.orchestration.agents;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Finds the parts of the repository a requirement actually touches.
 *
 * <p>This is what makes the brownfield stage reasoning rather than assertion. A canned answer
 * ("this change affects the generator and the validator") is indistinguishable from a guess and
 * goes stale the moment someone moves a class. Reading the source instead means the impact report
 * names files that exist right now, and a reviewer can open every one of them.
 *
 * <p>The method is deliberately simple and explainable: derive significant terms from the
 * requirement, score each source file by how many <em>distinct</em> terms it mentions, and report
 * the files that clear a threshold. It is a search, not a semantic understanding of the code, and
 * the report says so.
 */
public final class CodebaseScanner {

    /**
     * One file the requirement appears to touch.
     *
     * @param via how it was found — {@code terms} for a direct vocabulary match, or
     *            {@code references:Type} for a file pulled in because it uses one of those.
     */
    public record Match(String module, String path, String type, int score, List<String> matchedTerms,
                        String via) {

        public boolean isDirect() {
            return "terms".equals(via);
        }
    }

    /** What a scan found, already grouped the way an impact report needs it. */
    public record Impact(List<Match> matches, List<String> modules, List<String> apiSurface,
                         List<String> dataFlows, List<String> terms, int filesScanned) {

        public boolean isEmpty() {
            return matches.isEmpty();
        }
    }

    /**
     * Words that appear in almost any requirement sentence. Without this list the scan matches
     * every file in the repository and reports nothing useful.
     */
    private static final Set<String> STOPWORDS = Set.of(
            "must", "should", "shall", "will", "would", "could", "have", "has", "been", "being",
            "that", "this", "these", "those", "with", "without", "from", "into", "when", "then",
            "than", "they", "them", "their", "there", "where", "which", "while", "what", "each",
            "both", "only", "also", "more", "most", "some", "such", "same", "other", "another",
            "about", "after", "before", "during", "between", "over", "under", "again", "still",
            "continue", "using", "used", "make", "makes", "made", "need", "needs", "want", "wants",
            "given", "gives", "take", "takes", "keep", "keeps", "does", "done", "ensure", "ensures",
            "support", "supports", "allow", "allows", "provide", "provides", "return", "returns",
            "client", "clients", "user", "users", "system", "service", "feature", "change", "changes",
            "existing", "newly", "visually", "characters", "character");

    private static final Pattern TOKEN = Pattern.compile("[^A-Za-z0-9]+");
    private static final int MIN_TERM_LENGTH = 4;
    private static final int MIN_SCORE = 2;
    private static final int MAX_MATCHES = 15;

    private final Path projectRoot;

    public CodebaseScanner(Path projectRoot) {
        this.projectRoot = projectRoot;
    }

    public Impact scan(String requirementText) {
        List<String> terms = significantTerms(requirementText);
        if (terms.isEmpty()) {
            return new Impact(List.of(), List.of(), List.of(), List.of(), terms, 0);
        }

        List<Path> sources = sourceFiles();

        // Read once. The second pass needs the same content, and re-reading the tree would double
        // the cost of a scan for no benefit.
        Map<String, String> contentByPath = new LinkedHashMap<>();
        for (Path file : sources) {
            try {
                contentByPath.put(relativePath(file),
                        Files.readString(file, StandardCharsets.UTF_8).toLowerCase(Locale.ROOT));
            } catch (IOException | RuntimeException e) {
                // An unreadable file is skipped rather than failing the whole analysis.
            }
        }

        // Pass 1 — files that speak the requirement's vocabulary.
        List<Match> matches = new ArrayList<>();
        Set<String> claimed = new LinkedHashSet<>();
        for (Map.Entry<String, String> entry : contentByPath.entrySet()) {
            String relative = entry.getKey();
            String haystack = entry.getValue() + " " + relative.toLowerCase(Locale.ROOT);

            List<String> hit = terms.stream().filter(haystack::contains).toList();
            if (hit.size() >= MIN_SCORE) {
                matches.add(new Match(moduleOf(relative), relative, classify(relative, entry.getValue()),
                        hit.size(), hit, "terms"));
                claimed.add(relative);
            }
        }

        // Pass 2 — files that use the types the first pass found. A class can be squarely in scope
        // while mentioning only one of the requirement's words: ShortCodeGenerator is impacted by an
        // alphabet change because it *uses* Alphabet, not because of how it is worded. Following the
        // reference is what a human reviewer would do, so the scan does it too, and labels the
        // difference rather than presenting both as the same kind of finding.
        for (String directPath : List.copyOf(claimed)) {
            String typeName = javaTypeName(directPath);
            if (typeName == null) {
                continue;
            }
            String needle = typeName.toLowerCase(Locale.ROOT);
            for (Map.Entry<String, String> entry : contentByPath.entrySet()) {
                String relative = entry.getKey();
                if (claimed.contains(relative) || !relative.endsWith(".java")
                        || !entry.getValue().contains(needle)) {
                    continue;
                }
                matches.add(new Match(moduleOf(relative), relative, classify(relative, entry.getValue()),
                        1, List.of(), "references:" + typeName));
                claimed.add(relative);
            }
        }

        // Direct matches first, then the strongest: a reviewer should read the vocabulary hits
        // before the files that were pulled in by reference.
        matches.sort(Comparator.comparing(Match::isDirect).reversed()
                .thenComparing(Comparator.comparingInt(Match::score).reversed())
                .thenComparing(Match::path));
        List<Match> top = matches.stream().limit(MAX_MATCHES).toList();

        return new Impact(top,
                distinct(top.stream().map(Match::module)),
                distinct(top.stream().filter(m -> "api".equals(m.type())).map(Match::path)),
                distinct(top.stream().filter(m -> "data".equals(m.type())).map(Match::path)),
                terms, sources.size());
    }

    /** Lower-cased, de-duplicated, stopword-filtered tokens from the requirement. */
    static List<String> significantTerms(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        Set<String> terms = new LinkedHashSet<>();
        for (String raw : TOKEN.split(text.toLowerCase(Locale.ROOT))) {
            if (raw.length() >= MIN_TERM_LENGTH && !STOPWORDS.contains(raw)) {
                terms.add(raw);
            }
        }
        return List.copyOf(terms);
    }

    private List<Path> sourceFiles() {
        if (!Files.isDirectory(projectRoot)) {
            return List.of();
        }
        try (Stream<Path> paths = Files.walk(projectRoot, 12)) {
            return paths
                    .filter(Files::isRegularFile)
                    .filter(CodebaseScanner::isSource)
                    .toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    /** Main sources only: matching the tests would report the tests as the impact. */
    private static boolean isSource(Path path) {
        String p = path.toString().replace('\\', '/');
        if (p.contains("/target/") || p.contains("/.git/") || p.contains("/src/test/")) {
            return false;
        }
        return p.endsWith(".java") || p.endsWith(".sql") || p.endsWith(".yml") || p.endsWith(".yaml");
    }

    private String relativePath(Path file) {
        try {
            return projectRoot.toAbsolutePath().relativize(file.toAbsolutePath()).toString().replace('\\', '/');
        } catch (IllegalArgumentException e) {
            return file.toString().replace('\\', '/');
        }
    }

    /** The Java type a file declares, taken from its name; null for anything that is not Java. */
    static String javaTypeName(String relativePath) {
        if (!relativePath.endsWith(".java")) {
            return null;
        }
        int slash = relativePath.lastIndexOf('/');
        String fileName = slash < 0 ? relativePath : relativePath.substring(slash + 1);
        String typeName = fileName.substring(0, fileName.length() - ".java".length());
        // Very short names would match half the repository as substrings.
        return typeName.length() < 4 ? null : typeName;
    }

    private static String moduleOf(String relativePath) {
        int slash = relativePath.indexOf('/');
        return slash < 0 ? "(root)" : relativePath.substring(0, slash);
    }

    /** Coarse classification, enough to separate an API change from a data change. */
    private static String classify(String relativePath, String lowerContent) {
        if (relativePath.endsWith(".sql") || relativePath.contains("/entity/")) {
            return "data";
        }
        if (relativePath.contains("/web/") || lowerContent.contains("@restcontroller")
                || lowerContent.contains("@requestmapping")) {
            return "api";
        }
        if (relativePath.contains("/domain/")) {
            return "domain";
        }
        if (relativePath.endsWith(".yml") || relativePath.endsWith(".yaml")
                || relativePath.contains("config") || relativePath.contains("Properties")) {
            return "config";
        }
        return "internal";
    }

    private static List<String> distinct(Stream<String> values) {
        return values.distinct().sorted().toList();
    }
}
