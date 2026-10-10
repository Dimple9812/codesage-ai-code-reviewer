package com.codesage.analysis;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.codesage.config.AppProperties;
import com.codesage.findings.FindingCategory;
import com.codesage.findings.Severity;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Optional Semgrep integration (disabled by default). Writes the in-memory files to a private temp
 * directory, runs the semgrep CLI with a timeout and parses its JSON output. Semgrep only parses
 * code; nothing from the repository is executed.
 */
@Component
public class SemgrepAnalyzer implements StaticAnalyzer {

    private static final Logger log = LoggerFactory.getLogger(SemgrepAnalyzer.class);

    private final AppProperties props;
    private final ObjectMapper mapper = new ObjectMapper();

    public SemgrepAnalyzer(AppProperties props) {
        this.props = props;
    }

    @Override
    public String id() {
        return "semgrep";
    }

    @Override
    public boolean isEnabledGlobally() {
        return props.analysis().semgrepEnabled();
    }

    @Override
    public List<FindingCandidate> analyze(List<SourceFile> files) {
        if (files.isEmpty()) {
            return List.of();
        }
        Path dir = null;
        try {
            dir = Files.createTempDirectory("codesage-semgrep-");
            for (SourceFile f : files) {
                Path target = dir.resolve(f.path()).normalize();
                if (!target.startsWith(dir)) {
                    continue; // path traversal guard
                }
                Files.createDirectories(target.getParent());
                Files.writeString(target, f.content(), StandardCharsets.UTF_8);
            }
            ProcessBuilder pb = new ProcessBuilder(props.analysis().semgrepPath(), "scan", "--json", "--quiet",
                    "--metrics=off", "--disable-version-check", "--timeout", "30",
                    "--config", props.analysis().semgrepConfig(), ".")
                    .directory(dir.toFile())
                    .redirectError(ProcessBuilder.Redirect.DISCARD);
            pb.environment().keySet().removeIf(k -> k.toUpperCase(Locale.ROOT).matches(
                    ".*(TOKEN|SECRET|PASSWORD|KEY|DATABASE).*"));
            Process process = pb.start();
            byte[] output;
            try (var in = process.getInputStream()) {
                output = in.readAllBytes();
            }
            if (!process.waitFor(props.analysis().semgrepTimeout().toSeconds(), TimeUnit.SECONDS)) {
                process.destroyForcibly();
                log.warn("Semgrep timed out");
                return List.of();
            }
            return parse(output);
        } catch (IOException e) {
            log.warn("Semgrep unavailable: {}", e.getMessage());
            return List.of();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return List.of();
        } finally {
            deleteQuietly(dir);
        }
    }

    List<FindingCandidate> parse(byte[] output) throws IOException {
        List<FindingCandidate> out = new ArrayList<>();
        JsonNode root = mapper.readTree(output);
        for (JsonNode r : root.path("results")) {
            String path = r.path("path").asText().replaceFirst("^\\./", "");
            int start = r.path("start").path("line").asInt(1);
            int end = r.path("end").path("line").asInt(start);
            JsonNode extra = r.path("extra");
            String message = extra.path("message").asText("Semgrep finding");
            String checkId = r.path("check_id").asText("semgrep");
            Severity severity = switch (extra.path("severity").asText("").toUpperCase(Locale.ROOT)) {
                case "ERROR" -> Severity.HIGH;
                case "WARNING" -> Severity.MEDIUM;
                default -> Severity.LOW;
            };
            String category = extra.path("metadata").path("category").asText("").toLowerCase(Locale.ROOT);
            FindingCategory cat = switch (category) {
                case "security" -> FindingCategory.SECURITY;
                case "performance" -> FindingCategory.PERFORMANCE;
                case "correctness" -> FindingCategory.BUG;
                case "best-practice" -> FindingCategory.BEST_PRACTICE;
                default -> FindingCategory.CODE_QUALITY;
            };
            String shortId = checkId.substring(checkId.lastIndexOf('.') + 1);
            out.add(FindingCandidate.staticFinding("semgrep", cat, severity, PmdAnalyzer.humanize(shortId.replace('-', ' ')),
                    path, start, end, message, null, "Review the Semgrep rule " + checkId + " for remediation guidance.",
                    "SEMGREP-" + shortId, false));
        }
        return out;
    }

    private static void deleteQuietly(Path dir) {
        if (dir == null) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        } catch (IOException ignored) {
            // best effort cleanup of temporary copies
        }
    }
}
