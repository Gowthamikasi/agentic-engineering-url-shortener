package com.example.urlshortener.orchestration.agents;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.XMLConstants;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import java.util.stream.Stream;

/** Reads the Surefire XML the build produced. */
public final class SurefireReportReader {

    /** Totals across the reports that matched. */
    public record TestSummary(int tests, int failures, int errors, int skipped, List<String> suites) {

        public boolean isEmpty() {
            return suites.isEmpty();
        }

        public int passed() {
            return tests - failures - errors - skipped;
        }

        public boolean allPassed() {
            return failures == 0 && errors == 0;
        }
    }

    private final Path projectRoot;

    public SurefireReportReader(Path projectRoot) {
        this.projectRoot = projectRoot;
    }

    /** @param suiteFilter applied to the suite name, to pick out one kind of test */
    public TestSummary read(Predicate<String> suiteFilter) {
        int tests = 0;
        int failures = 0;
        int errors = 0;
        int skipped = 0;
        List<String> suites = new ArrayList<>();

        for (Path report : findReportFiles()) {
            try {
                DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
                // Build output, but XML external entities are never worth enabling.
                factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
                factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
                factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
                DocumentBuilder builder = factory.newDocumentBuilder();

                org.w3c.dom.Element suite = builder.parse(report.toFile()).getDocumentElement();
                String name = suite.getAttribute("name");
                if (name == null || name.isBlank() || !suiteFilter.test(name)) {
                    continue;
                }
                tests += attr(suite, "tests");
                failures += attr(suite, "failures");
                errors += attr(suite, "errors");
                skipped += attr(suite, "skipped");
                suites.add(name);
            } catch (Exception e) {
                // An unreadable report must not be counted as a pass.
                suites.add("UNREADABLE:" + report.getFileName());
                errors++;
            }
        }
        return new TestSummary(tests, failures, errors, skipped, List.copyOf(suites));
    }

    private List<Path> findReportFiles() {
        if (!Files.isDirectory(projectRoot)) {
            return List.of();
        }
        try (Stream<Path> paths = Files.walk(projectRoot, 6)) {
            return paths
                    .filter(Files::isRegularFile)
                    // Surefire only; see the class comment.
                    .filter(p -> {
                        String parent = p.getParent() == null ? "" : p.getParent().getFileName().toString();
                        return parent.equals("surefire-reports");
                    })
                    .filter(p -> p.getFileName().toString().startsWith("TEST-"))
                    .filter(p -> p.getFileName().toString().endsWith(".xml"))
                    .toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    private static int attr(org.w3c.dom.Element element, String name) {
        String value = element.getAttribute(name);
        try {
            return value == null || value.isBlank() ? 0 : Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
