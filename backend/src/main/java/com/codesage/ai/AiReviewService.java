package com.codesage.ai;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.function.BooleanSupplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.codesage.analysis.FindingCandidate;
import com.codesage.analysis.SourceFile;
import com.codesage.common.Texts;
import com.codesage.config.AppProperties;
import com.codesage.findings.FindingCategory;
import com.codesage.findings.FindingSource;
import com.codesage.findings.Severity;
import com.codesage.findings.Verification;
import com.codesage.review.AiStatus;

/**
 * The AI half of the review pipeline: packs bounded, line-numbered code into one or more requests,
 * calls the configured LLM with retries, validates every response against the schema (retrying once
 * on malformed output), and converts findings into candidates anchored to real files and lines.
 */
@Service
public class AiReviewService {

    private static final Logger log = LoggerFactory.getLogger(AiReviewService.class);
    private static final int CONTEXT_LINES = 15;

    public record Outcome(AiStatus status, String summary, Integer modelScore, List<FindingCandidate> findings,
                          List<String> notes, String provider, String model, int requests) {

        public static Outcome skipped(AiStatus status, String note) {
            return new Outcome(status, null, null, List.of(), List.of(note), null, null, 0);
        }
    }

    private final AppProperties props;
    private final AiResponseParser parser;
    private final LlmClient client;

    @org.springframework.beans.factory.annotation.Autowired
    public AiReviewService(AppProperties props, AiResponseParser parser) {
        this(props, parser, new LlmClient(props.ai().maxRetries(), props.ai().initialBackoff(), Sleeper.SYSTEM));
    }

    AiReviewService(AppProperties props, AiResponseParser parser, LlmClient client) {
        this.props = props;
        this.parser = parser;
        this.client = client;
    }

    public Outcome review(LlmProviderFactory.ResolvedLlm llm, String repository, String reviewKind,
                          List<SourceFile> files, BooleanSupplier cancelled) {
        List<String> notes = new ArrayList<>();
        if (files.isEmpty()) {
            return new Outcome(AiStatus.COMPLETED, "No reviewable files were found.", null, List.of(), notes,
                    llm.provider().id(), llm.model(), 0);
        }
        List<List<ReviewPrompt.FileChunk>> batches = pack(files, notes);
        List<FindingCandidate> findings = new ArrayList<>();
        List<String> summaries = new ArrayList<>();
        List<Integer> scores = new ArrayList<>();
        int succeeded = 0;
        int failed = 0;
        String lastError = null;

        for (List<ReviewPrompt.FileChunk> batch : batches) {
            if (cancelled.getAsBoolean()) {
                notes.add("AI review stopped because the review was cancelled.");
                break;
            }
            try {
                AiReviewPayload payload = callWithSchemaRetry(llm, repository, reviewKind, batch);
                succeeded++;
                if (payload.summary() != null && !payload.summary().isBlank()) {
                    summaries.add(payload.summary().trim());
                }
                if (payload.overallScore() != null) {
                    scores.add(payload.overallScore());
                }
                findings.addAll(toCandidates(payload, batch, notes));
            } catch (LlmException e) {
                failed++;
                lastError = e.getMessage();
                log.warn("AI review request failed: {} ({})", e.kind(), e.getMessage());
                if (e.kind() == LlmException.Kind.AUTHENTICATION || e.kind() == LlmException.Kind.BAD_REQUEST) {
                    break; // the same failure would repeat for every batch
                }
            }
        }
        int attempted = succeeded + failed;
        AiStatus status;
        if (succeeded == 0) {
            status = AiStatus.FAILED;
            notes.add("AI analysis failed: " + (lastError == null ? "no request completed" : lastError));
        } else if (failed > 0 || attempted < batches.size()) {
            status = AiStatus.PARTIAL;
            notes.add("AI analysis completed for " + succeeded + " of " + batches.size() + " batches"
                    + (lastError == null ? "." : "; last error: " + lastError));
        } else {
            status = AiStatus.COMPLETED;
        }
        String summary = summaries.isEmpty() ? null : Texts.truncate(String.join("\n\n", summaries), 7900);
        Integer modelScore = scores.isEmpty() ? null
                : (int) Math.round(scores.stream().mapToInt(Integer::intValue).average().orElse(0));
        return new Outcome(status, summary, modelScore, findings, notes, llm.provider().id(), llm.model(), attempted);
    }

    private AiReviewPayload callWithSchemaRetry(LlmProviderFactory.ResolvedLlm llm, String repository,
                                                String reviewKind, List<ReviewPrompt.FileChunk> batch) {
        String extra = null;
        int schemaRetries = Math.max(0, props.ai().maxSchemaRetries());
        for (int attempt = 0; ; attempt++) {
            String user = ReviewPrompt.user(repository, reviewKind, batch, extra);
            String raw = client.complete(llm.provider(), new LlmProvider.Request(llm.apiKey(), llm.baseUrl(),
                    llm.model(), ReviewPrompt.SYSTEM, user, props.ai().maxOutputTokens(), props.ai().timeout()));
            try {
                return parser.parse(raw);
            } catch (LlmException e) {
                if (attempt >= schemaRetries) {
                    throw e;
                }
                extra = "IMPORTANT: your previous response could not be used (" + Texts.truncate(e.getMessage(), 300)
                        + "). Respond with exactly one JSON object that follows the schema, with no other text.";
            }
        }
    }

    /** Greedily packs files into requests bounded by characters, respecting the per-review request cap. */
    List<List<ReviewPrompt.FileChunk>> pack(List<SourceFile> files, List<String> notes) {
        int budget = Math.max(2000, props.ai().maxCharsPerRequest());
        int maxRequests = Math.max(1, props.ai().maxRequestsPerReview());
        List<List<ReviewPrompt.FileChunk>> batches = new ArrayList<>();
        List<ReviewPrompt.FileChunk> current = new ArrayList<>();
        int used = 0;
        int dropped = 0;
        for (SourceFile file : files) {
            ReviewPrompt.FileChunk chunk = render(file, budget);
            int size = chunk.numberedContent().length() + 120;
            if (used + size > budget && !current.isEmpty()) {
                batches.add(current);
                current = new ArrayList<>();
                used = 0;
            }
            if (batches.size() >= maxRequests) {
                dropped++;
                continue;
            }
            current.add(chunk);
            used += size;
        }
        if (!current.isEmpty() && batches.size() < maxRequests) {
            batches.add(current);
        } else if (!current.isEmpty()) {
            dropped += current.size();
        }
        if (dropped > 0) {
            notes.add(dropped + " file(s) were not sent to the AI model because of the per-review request limit "
                    + "(static analysis still covered them).");
        }
        return batches;
    }

    /** Renders a file with line numbers; PR files mark changed lines with '>' and may be windowed. */
    ReviewPrompt.FileChunk render(SourceFile file, int budget) {
        List<String> lines = file.lines();
        int perFileBudget = budget - 500;
        String full = number(lines, file, 1, lines.size());
        if (full.length() <= perFileBudget) {
            return new ReviewPrompt.FileChunk(file, full, false);
        }
        StringBuilder sb = new StringBuilder();
        if (file.isPullRequestFile() && !file.changedLines().isEmpty()) {
            TreeSet<Integer> include = new TreeSet<>();
            for (int changed : file.changedLines()) {
                for (int n = Math.max(1, changed - CONTEXT_LINES); n <= Math.min(lines.size(), changed + CONTEXT_LINES); n++) {
                    include.add(n);
                }
            }
            int prev = 0;
            for (int n : include) {
                if (prev != 0 && n != prev + 1) {
                    sb.append("     ...\n");
                }
                sb.append(numberLine(lines, file, n));
                prev = n;
                if (sb.length() > perFileBudget) {
                    sb.append("     ... (truncated)\n");
                    break;
                }
            }
        } else {
            for (int n = 1; n <= lines.size(); n++) {
                String line = numberLine(lines, file, n);
                if (sb.length() + line.length() > perFileBudget) {
                    sb.append("     ... (file truncated at line ").append(n - 1).append(")\n");
                    break;
                }
                sb.append(line);
            }
        }
        return new ReviewPrompt.FileChunk(file, sb.toString(), true);
    }

    private static String number(List<String> lines, SourceFile file, int from, int to) {
        StringBuilder sb = new StringBuilder();
        for (int n = from; n <= to; n++) {
            sb.append(numberLine(lines, file, n));
        }
        return sb.toString();
    }

    private static String numberLine(List<String> lines, SourceFile file, int n) {
        String marker = file.changedLines().contains(n) ? ">" : " ";
        String text = lines.get(n - 1);
        if (text.length() > 400) {
            text = text.substring(0, 400) + " …";
        }
        return String.format("%s%5d| %s%n", marker, n, text);
    }

    private List<FindingCandidate> toCandidates(AiReviewPayload payload, List<ReviewPrompt.FileChunk> batch,
                                                List<String> notes) {
        Map<String, SourceFile> byPath = new HashMap<>();
        for (ReviewPrompt.FileChunk c : batch) {
            byPath.put(c.file().path(), c.file());
        }
        List<FindingCandidate> out = new ArrayList<>();
        int unknownPaths = 0;
        for (AiReviewPayload.Finding f : payload.findings()) {
            String path = f.filePath().trim().replaceFirst("^\\./", "").replaceFirst("^/", "");
            SourceFile file = byPath.get(path);
            if (file == null) {
                unknownPaths++;
                continue; // never persist findings for files the model was not shown
            }
            int lineCount = Math.max(1, file.lineCount());
            Integer start = f.startLine();
            Integer end = f.endLine();
            if (start != null && start > lineCount) {
                start = null;
                end = null;
            }
            if (start != null) {
                end = end == null || end < start ? start : Math.min(end, lineCount);
            } else {
                end = null;
            }
            out.add(new FindingCandidate(FindingSource.AI, "llm", Verification.UNVERIFIED,
                    FindingCategory.valueOf(f.category()), Severity.valueOf(f.severity()), f.confidence(),
                    Texts.truncate(f.title().trim(), 300), file.path(), start, end,
                    Texts.truncate(f.description(), 7900), Texts.truncate(f.impact(), 3900),
                    Texts.truncate(f.recommendation(), 3900), Texts.truncate(f.suggestedCode(), 7900),
                    f.ruleId() == null ? null : Texts.truncate(f.ruleId().trim(), 100), false));
        }
        if (unknownPaths > 0) {
            notes.add(unknownPaths + " AI finding(s) referenced files that were not part of the review and were discarded.");
        }
        return out;
    }
}
