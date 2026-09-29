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

/** Finds the parts of the repository a requirement touches. */
public final class CodebaseScanner {

    /**
     * One file the requirement appears to touch.
     *
     * @param via how it was found: {@code terms} for a direct word match, or
     *            {@code references:Type} for a file pulled in because it uses one
     */
    public record Match(String module, String path, String type, int score, List<String> matchedTerms,
                        String via) {

        public boolean isDirect() {
            return "terms".equals(via);
        }
    }

    /** What a scan found, grouped the way an impact report needs it. */
    public record Impact(List<Match> matches, List<String> modules, List<String> apiSurface,
                         List<String> dataFlows, List<String> terms, int filesScanned) {

        public boolean isEmpty() {
            return matches.isEmpty();
        }
    }

    /** Filler words. */
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
    /** How much a term in the file name is worth, relative to one in the body. */
    private static final int NAME_MATCH_WEIGHT = 3;

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

        // Read once; the second pass needs the same content.
        Map<String, String> contentByPath = new LinkedHashMap<>();
        for (Path file : sources) {
            try {
                contentByPath.put(relativePath(file),
                        Files.readString(file, StandardCharsets.UTF_8).toLowerCase(Locale.ROOT));
            } catch (IOException | RuntimeException e) {
                // Skip a file we cannot read rather than failing the whole scan.
            }
        }

        // Pass 1: files that use the requirement's words.
        List<Match> matches = new ArrayList<>();
        Set<String> claimed = new LinkedHashSet<>();
        for (Map.Entry<String, String> entry : contentByPath.entrySet()) {
            String relative = entry.getKey();
            String haystack = entry.getValue() + " " + relative.toLowerCase(Locale.ROOT);

            List<String> hit = terms.stream().filter(haystack::contains).toList();
            if (hit.size() >= MIN_SCORE) {
                // A term in the file's own name counts for more than one buried in its text.
                // Alphabet.java is what an alphabet change is about; a file that merely discusses
                // alphabets is not, and without this the discussion outranks the thing.
                long nameHits = terms.stream().filter(fileName(relative)::contains).count();
                int score = hit.size() + (int) (NAME_MATCH_WEIGHT * nameHits);
                matches.add(new Match(moduleOf(relative), relative, classify(relative, entry.getValue()),
                        score, hit, "terms"));
                claimed.add(relative);
            }
        }

        // Pass 2: files that use the types pass 1 found. ShortCodeGenerator is in scope for an
        // alphabet change because it uses Alphabet, not because of how the requirement is worded.
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

        // Direct word matches first, strongest first within each group.
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

    /** Lower-cased, de-duplicated tokens from the requirement, minus the filler words. */
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

    /** Main sources only. */
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

    /** The type a Java file declares, taken from its name. */
    static String javaTypeName(String relativePath) {
        if (!relativePath.endsWith(".java")) {
            return null;
        }
        int slash = relativePath.lastIndexOf('/');
        String fileName = slash < 0 ? relativePath : relativePath.substring(slash + 1);
        String typeName = fileName.substring(0, fileName.length() - ".java".length());
        // A short name would match half the repository as a substring.
        return typeName.length() < 4 ? null : typeName;
    }

    /** Lower-cased file name without the directories. */
    private static String fileName(String relativePath) {
        int slash = relativePath.lastIndexOf('/');
        return (slash < 0 ? relativePath : relativePath.substring(slash + 1)).toLowerCase(Locale.ROOT);
    }

    private static String moduleOf(String relativePath) {
        int slash = relativePath.indexOf('/');
        return slash < 0 ? "(root)" : relativePath.substring(0, slash);
    }

    /** Coarse bucket, enough to tell an API change from a data change. */
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
