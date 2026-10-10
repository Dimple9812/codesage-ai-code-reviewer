package com.codesage.analysis;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.codesage.config.AppProperties;
import com.codesage.findings.FindingCategory;
import com.codesage.findings.Severity;

import net.sourceforge.pmd.PMDConfiguration;
import net.sourceforge.pmd.PmdAnalysis;
import net.sourceforge.pmd.lang.document.FileId;
import net.sourceforge.pmd.lang.rule.RulePriority;
import net.sourceforge.pmd.reporting.Report;
import net.sourceforge.pmd.reporting.RuleViolation;

/**
 * Runs PMD 7 in-process on Java sources held in memory. PMD parses the code; it never compiles or executes it.
 */
@Component
public class PmdAnalyzer implements StaticAnalyzer {

    private static final Logger log = LoggerFactory.getLogger(PmdAnalyzer.class);
    private static final String RULESET = "analysis/codesage-pmd-ruleset.xml";
    private static final Set<String> HIGH_RULES = Set.of("CloseResource", "ReturnFromFinallyBlock", "BrokenNullCheck",
            "MisplacedNullCheck", "DoubleCheckedLocking", "HardCodedCryptoKey", "InsecureCryptoIv",
            "UseEqualsToCompareStrings", "EqualsNull", "JumbledIncrementer");

    private final AppProperties props;

    public PmdAnalyzer(AppProperties props) {
        this.props = props;
    }

    @Override
    public String id() {
        return "pmd";
    }

    @Override
    public boolean isEnabledGlobally() {
        return props.analysis().pmdEnabled();
    }

    @Override
    public List<FindingCandidate> analyze(List<SourceFile> files) {
        List<SourceFile> javaFiles = files.stream().filter(f -> f.language() == Language.JAVA).toList();
        if (javaFiles.isEmpty()) {
            return List.of();
        }
        PMDConfiguration config = new PMDConfiguration();
        config.addRuleSet(RULESET);
        config.setMinimumPriority(RulePriority.LOW);
        config.setIgnoreIncrementalAnalysis(true);
        config.setThreads(0);
        List<FindingCandidate> out = new ArrayList<>();
        try (PmdAnalysis pmd = PmdAnalysis.create(config)) {
            for (SourceFile f : javaFiles) {
                pmd.files().addSourceFile(FileId.fromPathLikeString(f.path()), f.content());
            }
            Report report = pmd.performAnalysisAndCollectReport();
            for (RuleViolation v : report.getViolations()) {
                out.add(toCandidate(v));
            }
            if (!report.getProcessingErrors().isEmpty()) {
                log.info("PMD could not parse {} file(s)", report.getProcessingErrors().size());
            }
        } catch (RuntimeException e) {
            log.warn("PMD analysis failed: {}", e.getMessage());
        }
        return out;
    }

    private FindingCandidate toCandidate(RuleViolation v) {
        String ruleName = v.getRule().getName();
        FindingCategory cat = categorize(ruleName);
        Severity severity = HIGH_RULES.contains(ruleName) ? Severity.HIGH : switch (v.getRule().getPriority()) {
            case HIGH -> Severity.HIGH;
            case MEDIUM_HIGH, MEDIUM -> Severity.MEDIUM;
            case MEDIUM_LOW -> Severity.LOW;
            default -> Severity.INFO;
        };
        String path = v.getFileId().getOriginalPath();
        String ruleDescription = v.getRule().getDescription() == null ? "" : v.getRule().getDescription().trim();
        return FindingCandidate.staticFinding("pmd", cat, severity,
                humanize(ruleName), path, v.getBeginLine(), Math.max(v.getBeginLine(), v.getEndLine()),
                v.getDescription(),
                ruleDescription.length() > 600 ? ruleDescription.substring(0, 600) + "…" : ruleDescription,
                "See the PMD rule documentation for " + ruleName + " and apply the recommended pattern.",
                "PMD-" + ruleName, false);
    }

    private static final Set<String> RELIABILITY_RULES = Set.of("CloseResource", "DoubleCheckedLocking",
            "NonThreadSafeSingleton", "UnsynchronizedStaticFormatter", "AvoidCatchingThrowable", "EmptyCatchBlock");
    private static final Set<String> PERFORMANCE_RULES = Set.of("UseStringBufferForStringAppends", "BigIntegerInstantiation");
    private static final Set<String> SECURITY_RULES = Set.of("HardCodedCryptoKey", "InsecureCryptoIv");
    private static final Set<String> BEST_PRACTICE_RULES = Set.of("AvoidPrintStackTrace", "PreserveStackTrace",
            "AvoidReassigningLoopVariables", "ConstructorCallsOverridableMethod");

    static FindingCategory categorize(String rule) {
        if (SECURITY_RULES.contains(rule)) {
            return FindingCategory.SECURITY;
        }
        if (RELIABILITY_RULES.contains(rule)) {
            return FindingCategory.RELIABILITY;
        }
        if (PERFORMANCE_RULES.contains(rule)) {
            return FindingCategory.PERFORMANCE;
        }
        if (BEST_PRACTICE_RULES.contains(rule)) {
            return FindingCategory.BEST_PRACTICE;
        }
        if (rule.startsWith("Unused")) {
            return FindingCategory.CODE_QUALITY;
        }
        return FindingCategory.BUG;
    }

    static String humanize(String camel) {
        return camel.replaceAll("([a-z])([A-Z])", "$1 $2").replaceAll("([A-Z]+)([A-Z][a-z])", "$1 $2");
    }
}
