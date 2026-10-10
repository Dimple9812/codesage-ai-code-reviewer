package com.codesage.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.codesage.findings.FindingCategory;
import com.codesage.findings.FindingSource;
import com.codesage.findings.Severity;
import com.codesage.support.TestProps;

class StaticAnalyzersTest {

    private static SourceFile file(String path, Language language, String content) {
        return new SourceFile(path, language, content, Set.of(), Set.of());
    }

    @Test
    void pmdReportsRealJavaDefects() {
        String java = """
                package demo;

                import java.io.FileInputStream;

                public class Demo {
                    public boolean same(String a, String b) {
                        return a == b;
                    }

                    public int read(String path) throws Exception {
                        FileInputStream in = new FileInputStream(path);
                        return in.read();
                    }

                    public void swallow() {
                        try {
                            Integer.parseInt("x");
                        } catch (NumberFormatException e) {
                        }
                    }
                }
                """;
        List<FindingCandidate> findings = new PmdAnalyzer(TestProps.defaults())
                .analyze(List.of(file("src/main/java/demo/Demo.java", Language.JAVA, java)));
        assertThat(findings).extracting(FindingCandidate::ruleId)
                .contains("PMD-UseEqualsToCompareStrings", "PMD-CloseResource", "PMD-EmptyCatchBlock");
        assertThat(findings).allMatch(f -> f.source() == FindingSource.STATIC_ANALYSIS);
        FindingCandidate eq = findings.stream().filter(f -> f.ruleId().equals("PMD-UseEqualsToCompareStrings")).findFirst().orElseThrow();
        assertThat(eq.startLine()).isEqualTo(7);
        assertThat(eq.severity()).isEqualTo(Severity.HIGH);
    }

    @Test
    void pmdIgnoresNonJavaFiles() {
        assertThat(new PmdAnalyzer(TestProps.defaults())
                .analyze(List.of(file("a.py", Language.PYTHON, "print(1)")))).isEmpty();
    }

    @Test
    void patternRulesDetectSecretsInjectionAndUnsafeApis() {
        PatternRulesAnalyzer rules = new PatternRulesAnalyzer(TestProps.defaults());
        List<FindingCandidate> findings = rules.analyze(List.of(
                file("config.py", Language.PYTHON, """
                        AWS_KEY = "AKIAABCDEFGHIJKLMNOP"
                        password = "SuperSecret123!"
                        cursor.execute(f"SELECT * FROM users WHERE id = {user_id}")
                        data = pickle.loads(blob)
                        requests.get(url, verify=False)
                        try:
                            run()
                        except:
                            pass
                        """),
                file("src/Repo.java", Language.JAVA, """
                        class Repo {
                          void find(String id) throws Exception {
                            stmt.executeQuery("SELECT * FROM t WHERE id = '" + id + "'");
                            MessageDigest.getInstance("MD5");
                          }
                        }
                        """),
                file("main.c", Language.C, "int main(){ char b[8]; gets(b); }")));
        assertThat(findings).extracting(FindingCandidate::ruleId).contains(
                "SEC-SECRET-001", "SEC-SECRET-005", "SEC-SQLI-002", "SEC-DESER-001", "SEC-TLS-001", "REL-PY-001",
                "SEC-SQLI-001", "SEC-CRYPTO-001", "SEC-C-001");
        assertThat(findings.stream().filter(f -> f.ruleId().equals("SEC-SECRET-001")).findFirst().orElseThrow().sensitive())
                .isTrue();
    }

    @Test
    void placeholderCredentialsAreNotReported() {
        PatternRulesAnalyzer rules = new PatternRulesAnalyzer(TestProps.defaults());
        List<FindingCandidate> findings = rules.analyze(List.of(file("app.yml", Language.YAML,
                "password: \"${DB_PASSWORD}\"\napi_key: \"your-api-key-here\"\n")));
        assertThat(findings).isEmpty();
    }

    @Test
    void invalidJsonAndYamlAreReportedWithLineNumbers() {
        PatternRulesAnalyzer rules = new PatternRulesAnalyzer(TestProps.defaults());
        List<FindingCandidate> findings = rules.analyze(List.of(
                file("data.json", Language.JSON, "{\n  \"a\": 1,\n  \"b\": \n}"),
                file("ci.yml", Language.YAML, "name: build\nname: duplicate\n")));
        assertThat(findings).extracting(FindingCandidate::ruleId).containsExactlyInAnyOrder("CFG-JSON-PARSE", "CFG-YAML-PARSE");
        assertThat(findings).allMatch(f -> f.category() == FindingCategory.BUG);
    }

    @Test
    void matchesInsideStringLiteralsAreNotTreatedAsCalls() {
        PatternRulesAnalyzer rules = new PatternRulesAnalyzer(TestProps.defaults());
        assertThat(rules.analyze(List.of(file("Rules.java", Language.JAVA,
                "Pattern p = Pattern.compile(\"verify\\\\s*=\\\\s*False|TrustAllCerts\");\n"
                        + "String doc = \"never call Runtime.getRuntime().exec(cmd + x)\";\n")))).isEmpty();
        // ...while the real call is still reported
        assertThat(rules.analyze(List.of(file("client.py", Language.PYTHON, "requests.get(url, verify=False)\n"))))
                .extracting(FindingCandidate::ruleId).containsExactly("SEC-TLS-001");
        assertThat(PatternRulesAnalyzer.insideStringLiteral("x = \"a \\\" b\" + eval(", 20)).isFalse();
    }

    @Test
    void commentsDoNotTriggerCodeRules() {
        PatternRulesAnalyzer rules = new PatternRulesAnalyzer(TestProps.defaults());
        assertThat(rules.analyze(List.of(file("a.js", Language.JAVASCRIPT, "// never use eval(userInput)\nconst x = 1;"))))
                .isEmpty();
    }
}
