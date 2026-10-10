package com.codesage.analysis;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.MarkedYAMLException;

import com.codesage.config.AppProperties;
import com.codesage.findings.FindingCategory;
import com.codesage.findings.Severity;
import com.fasterxml.jackson.core.JsonLocation;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * CodeSage's built-in, language-aware pattern rules: hard-coded secrets, injection-prone APIs, weak
 * crypto, disabled TLS verification, unsafe deserialisation, dangerous C functions, plus structural
 * validation of JSON and YAML files. Line-based and deterministic; high-signal patterns only.
 */
@Component
public class PatternRulesAnalyzer implements StaticAnalyzer {

    private static final Set<Language> CODE = EnumSet.of(Language.JAVA, Language.JAVASCRIPT, Language.TYPESCRIPT,
            Language.PYTHON, Language.C, Language.CPP, Language.GO);
    private static final Set<Language> ALL = EnumSet.allOf(Language.class);
    private static final Pattern PLACEHOLDER = Pattern.compile(
            "(?i)(\\$\\{|<[a-z_-]+>|changeme|change_me|example|placeholder|your[_-]|xxx|\\*\\*\\*|dummy|test|sample|redacted)");

    record Rule(String id, Set<Language> languages, Pattern pattern, FindingCategory category, Severity severity,
                String title, String description, String impact, String recommendation, boolean secret) {

        /** Rules whose pattern intentionally inspects string literals (SQL text, config values). */
        boolean matchesStrings() {
            return id.startsWith("SEC-SQLI") || id.startsWith("SQL-") || id.startsWith("CFG-");
        }
    }

    /** True if position {@code pos} lies inside a "..." or '...' literal (escape-aware, single line). */
    static boolean insideStringLiteral(String line, int pos) {
        char quote = 0;
        for (int i = 0; i < pos && i < line.length(); i++) {
            char c = line.charAt(i);
            if (quote != 0) {
                if (c == '\\') {
                    i++;
                } else if (c == quote) {
                    quote = 0;
                }
            } else if (c == '"' || c == '\'' || c == '`') {
                quote = c;
            }
        }
        return quote != 0;
    }

    static final List<Rule> RULES = List.of(
            new Rule("SEC-SECRET-001", ALL, Pattern.compile("\\b(AKIA|ASIA)[0-9A-Z]{16}\\b"),
                    FindingCategory.SECURITY, Severity.CRITICAL, "Hard-coded AWS access key id",
                    "A string matching the format of an AWS access key id is committed to the repository.",
                    "Anyone with read access to the repository (or its history) can use the credential.",
                    "Revoke and rotate the key, remove it from history, and load credentials from the environment or a secret manager.", true),
            new Rule("SEC-SECRET-002", ALL, Pattern.compile("-----BEGIN (RSA |EC |DSA |OPENSSH |PGP )?PRIVATE KEY( BLOCK)?-----"),
                    FindingCategory.SECURITY, Severity.CRITICAL, "Private key committed to source",
                    "A PEM private key block is present in the file.",
                    "Leaked private keys allow impersonation, decryption of traffic, or signing as the key owner.",
                    "Remove the key, rotate it, purge it from Git history, and store it in a secret manager.", true),
            new Rule("SEC-SECRET-003", ALL, Pattern.compile("\\b(gh[pousr]_[A-Za-z0-9]{36,}|github_pat_[A-Za-z0-9_]{40,})\\b"),
                    FindingCategory.SECURITY, Severity.CRITICAL, "Hard-coded GitHub token",
                    "A string matching the format of a GitHub access token is committed to the repository.",
                    "The token grants API access to the owner's GitHub account or organisation.",
                    "Revoke the token on GitHub immediately and inject it via environment configuration instead.", true),
            new Rule("SEC-SECRET-004", ALL, Pattern.compile("\\b(sk-(ant-|proj-)?[A-Za-z0-9_-]{32,}|xox[baprs]-[A-Za-z0-9-]{20,}|AIza[0-9A-Za-z_-]{35})\\b"),
                    FindingCategory.SECURITY, Severity.CRITICAL, "Hard-coded API key",
                    "A string matching a well-known API key format (LLM provider, Slack or Google) is committed.",
                    "Leaked API keys can be abused for billing fraud or data access.",
                    "Rotate the key and load it from a secret manager or environment variable.", true),
            new Rule("SEC-SECRET-005", ALL, Pattern.compile("(?i)\\b(password|passwd|pwd|secret|api[_-]?key|access[_-]?token|client[_-]?secret)\\b\\s*[:=]\\s*[\"'][^\"'\\s]{8,}[\"']"),
                    FindingCategory.SECURITY, Severity.HIGH, "Possible hard-coded credential",
                    "A credential-like variable is assigned a literal string value.",
                    "Credentials in source code are exposed to everyone with repository access and are hard to rotate.",
                    "Read the value from configuration (environment variable or secret manager) instead of hard-coding it.", true),
            new Rule("SEC-SQLI-001", EnumSet.of(Language.JAVA, Language.JAVASCRIPT, Language.TYPESCRIPT, Language.PYTHON, Language.GO),
                    Pattern.compile("(?i)(executeQuery|executeUpdate|prepareStatement|createQuery|createNativeQuery|\\.execute|\\.query|\\.raw|Exec|Query)\\s*\\(\\s*(\"[^\"]*\\b(select|insert|update|delete)\\b[^\"]*\"|'[^']*\\b(select|insert|update|delete)\\b[^']*'|`[^`]*\\b(select|insert|update|delete)\\b[^`]*`)\\s*\\+"),
                    FindingCategory.SECURITY, Severity.HIGH, "SQL built by string concatenation",
                    "A SQL statement is assembled by concatenating strings before being executed.",
                    "If any concatenated value is user-controlled this enables SQL injection.",
                    "Use parameterised queries / bind variables instead of concatenating values into SQL.", false),
            new Rule("SEC-SQLI-002", EnumSet.of(Language.PYTHON),
                    Pattern.compile("(?i)\\.execute\\(\\s*f[\"'][^\"']*\\b(select|insert|update|delete)\\b"),
                    FindingCategory.SECURITY, Severity.HIGH, "SQL built with an f-string",
                    "A SQL statement is formatted with an f-string before execution.",
                    "Interpolated values can lead to SQL injection.",
                    "Pass parameters separately, e.g. cursor.execute(\"... WHERE id = %s\", (user_id,)).", false),
            new Rule("SEC-EVAL-001", EnumSet.of(Language.JAVASCRIPT, Language.TYPESCRIPT, Language.PYTHON),
                    Pattern.compile("(^|[^.\\w])(eval|exec)\\s*\\(|new\\s+Function\\s*\\("),
                    FindingCategory.SECURITY, Severity.HIGH, "Dynamic code evaluation",
                    "Code is evaluated dynamically from a string.",
                    "If the string can be influenced by input this results in arbitrary code execution.",
                    "Avoid eval/exec/new Function; use safe parsers or explicit dispatch tables.", false),
            new Rule("SEC-XSS-001", EnumSet.of(Language.JAVASCRIPT, Language.TYPESCRIPT),
                    Pattern.compile("\\.(innerHTML|outerHTML)\\s*=|dangerouslySetInnerHTML|document\\.write\\s*\\("),
                    FindingCategory.SECURITY, Severity.MEDIUM, "Raw HTML injection sink",
                    "HTML is written to the DOM without escaping.",
                    "Untrusted data reaching this sink results in cross-site scripting (XSS).",
                    "Use textContent / framework escaping, or sanitise with a vetted library such as DOMPurify.", false),
            new Rule("SEC-CMD-001", EnumSet.of(Language.JAVA),
                    Pattern.compile("Runtime\\.getRuntime\\(\\)\\.exec\\s*\\([^)]*\\+"),
                    FindingCategory.SECURITY, Severity.HIGH, "OS command built by concatenation",
                    "An operating-system command string is constructed by concatenation.",
                    "Attacker-controlled input could inject additional shell commands.",
                    "Use ProcessBuilder with a fixed argument list and validate inputs against an allow-list.", false),
            new Rule("SEC-CMD-002", EnumSet.of(Language.PYTHON),
                    Pattern.compile("os\\.system\\s*\\(|subprocess\\.[a-z_]+\\([^)]*shell\\s*=\\s*True"),
                    FindingCategory.SECURITY, Severity.HIGH, "Shell command execution",
                    "A command is executed through the system shell.",
                    "Shell metacharacters in interpolated values allow command injection.",
                    "Call subprocess.run([...], shell=False) with an argument list.", false),
            new Rule("SEC-CMD-003", EnumSet.of(Language.JAVASCRIPT, Language.TYPESCRIPT),
                    Pattern.compile("child_process|\\bexecSync\\s*\\(|\\bexec\\s*\\(\\s*`"),
                    FindingCategory.SECURITY, Severity.MEDIUM, "Shell command execution",
                    "A shell command is executed from JavaScript.",
                    "Interpolated input can lead to command injection.",
                    "Prefer execFile/spawn with an argument array and validate inputs.", false),
            new Rule("SEC-CRYPTO-001", EnumSet.of(Language.JAVA, Language.PYTHON, Language.JAVASCRIPT, Language.TYPESCRIPT, Language.GO),
                    Pattern.compile("(?i)MessageDigest\\.getInstance\\(\\s*\"(MD5|SHA-?1)\"|hashlib\\.(md5|sha1)\\(|createHash\\(\\s*['\"](md5|sha1)['\"]|crypto/(md5|sha1)\""),
                    FindingCategory.SECURITY, Severity.MEDIUM, "Weak hash algorithm",
                    "MD5 or SHA-1 is used.",
                    "Both algorithms are broken for collision resistance and must not protect passwords or integrity.",
                    "Use SHA-256 or stronger; for passwords use bcrypt, scrypt or Argon2.", false),
            new Rule("SEC-CRYPTO-002", EnumSet.of(Language.JAVA),
                    Pattern.compile("Cipher\\.getInstance\\(\\s*\"(DES|AES)(/ECB[^\"]*)?\"\\s*\\)|Cipher\\.getInstance\\(\\s*\"[^\"]*/ECB/"),
                    FindingCategory.SECURITY, Severity.MEDIUM, "Insecure cipher mode",
                    "A cipher is requested with ECB mode (also the default for plain \"AES\").",
                    "ECB leaks plaintext patterns and provides no integrity.",
                    "Use an authenticated mode such as AES/GCM/NoPadding with a random IV.", false),
            new Rule("SEC-TLS-001", EnumSet.of(Language.PYTHON, Language.JAVASCRIPT, Language.TYPESCRIPT, Language.GO, Language.JAVA),
                    Pattern.compile("verify\\s*=\\s*False|rejectUnauthorized\\s*:\\s*false|InsecureSkipVerify\\s*:\\s*true|NODE_TLS_REJECT_UNAUTHORIZED|TrustAllCerts|ALLOW_ALL_HOSTNAME_VERIFIER|NoopHostnameVerifier"),
                    FindingCategory.SECURITY, Severity.HIGH, "TLS certificate verification disabled",
                    "Certificate or hostname verification is turned off.",
                    "Connections become vulnerable to man-in-the-middle interception.",
                    "Keep verification enabled; trust a private CA explicitly if required.", false),
            new Rule("SEC-DESER-001", EnumSet.of(Language.PYTHON),
                    Pattern.compile("pickle\\.loads?\\(|yaml\\.load\\((?![^)]*Loader\\s*=\\s*(yaml\\.)?SafeLoader)"),
                    FindingCategory.SECURITY, Severity.HIGH, "Unsafe deserialisation",
                    "Data is deserialised with pickle or an unsafe YAML loader.",
                    "Deserialising untrusted data can execute arbitrary code.",
                    "Use json, or yaml.safe_load, for untrusted input.", false),
            new Rule("SEC-DESER-002", EnumSet.of(Language.JAVA),
                    Pattern.compile("new\\s+ObjectInputStream\\s*\\("),
                    FindingCategory.SECURITY, Severity.MEDIUM, "Java native deserialisation",
                    "ObjectInputStream is used to deserialise objects.",
                    "Deserialising untrusted streams enables gadget-chain remote code execution.",
                    "Avoid native serialisation for untrusted data or apply an ObjectInputFilter allow-list.", false),
            new Rule("SEC-C-001", EnumSet.of(Language.C, Language.CPP),
                    Pattern.compile("(^|[^\\w])gets\\s*\\("),
                    FindingCategory.SECURITY, Severity.CRITICAL, "Use of gets()",
                    "gets() reads input without any bounds check.",
                    "Any input longer than the buffer overflows it (classic stack buffer overflow).",
                    "Use fgets(buf, sizeof buf, stdin).", false),
            new Rule("SEC-C-002", EnumSet.of(Language.C, Language.CPP),
                    Pattern.compile("(^|[^\\w])(strcpy|strcat|sprintf|vsprintf)\\s*\\("),
                    FindingCategory.SECURITY, Severity.MEDIUM, "Unbounded string function",
                    "An unbounded string copy/format function is used.",
                    "If the source can exceed the destination buffer, memory is corrupted.",
                    "Use bounded variants (snprintf, strlcpy or explicit length checks).", false),
            new Rule("REL-PY-001", EnumSet.of(Language.PYTHON),
                    Pattern.compile("^\\s*except\\s*:\\s*$"),
                    FindingCategory.RELIABILITY, Severity.LOW, "Bare except clause",
                    "A bare except catches every exception, including KeyboardInterrupt and SystemExit.",
                    "Unexpected errors are silently swallowed, hiding defects.",
                    "Catch specific exception types, or at least `except Exception:` and log the error.", false),
            new Rule("REL-GO-001", EnumSet.of(Language.GO),
                    Pattern.compile("^\\s*_\\s*(,\\s*_\\s*)?=\\s*[\\w.]+\\.(Close|Write|Exec|Commit|Rollback)\\("),
                    FindingCategory.RELIABILITY, Severity.LOW, "Ignored error result",
                    "An error returned by an I/O or transactional call is explicitly discarded.",
                    "Failures (e.g. incomplete writes or commits) go unnoticed.",
                    "Handle or at least log the returned error.", false),
            new Rule("SQL-001", EnumSet.of(Language.SQL),
                    Pattern.compile("(?i)^\\s*(delete\\s+from|update)\\s+[\\w.\"]+\\s*(set\\s+[^;]*)?;\\s*$"),
                    FindingCategory.BUG, Severity.MEDIUM, "DELETE/UPDATE without WHERE clause",
                    "A DELETE or UPDATE statement has no WHERE clause.",
                    "Every row in the table is modified or removed.",
                    "Add a WHERE clause, or document clearly that a full-table change is intended.", false),
            new Rule("SQL-002", EnumSet.of(Language.SQL),
                    Pattern.compile("(?i)grant\\s+all\\s+privileges"),
                    FindingCategory.SECURITY, Severity.MEDIUM, "Overly broad GRANT",
                    "ALL PRIVILEGES is granted.",
                    "Violates least privilege; a compromised account gains full control.",
                    "Grant only the specific privileges required.", false),
            new Rule("CFG-001", EnumSet.of(Language.CONFIG),
                    Pattern.compile("(?i)^FROM\\s+[^\\s:@]+(:latest)?\\s*$"),
                    FindingCategory.RELIABILITY, Severity.LOW, "Unpinned base image",
                    "The Docker base image is not pinned to a specific version.",
                    "Builds are not reproducible and may silently pick up breaking or vulnerable changes.",
                    "Pin an explicit version tag (or digest).", false));

    private final AppProperties props;
    private final ObjectMapper json = new ObjectMapper();

    public PatternRulesAnalyzer(AppProperties props) {
        this.props = props;
    }

    @Override
    public String id() {
        return "rules";
    }

    @Override
    public boolean isEnabledGlobally() {
        return props.analysis().rulesEnabled();
    }

    @Override
    public List<FindingCandidate> analyze(List<SourceFile> files) {
        List<FindingCandidate> out = new ArrayList<>();
        for (SourceFile file : files) {
            scanLines(file, out);
            if (file.language() == Language.JSON) {
                validateJson(file, out);
            } else if (file.language() == Language.YAML) {
                validateYaml(file, out);
            }
        }
        return out;
    }

    private void scanLines(SourceFile file, List<FindingCandidate> out) {
        List<String> lines = file.lines();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.length() > 2000) {
                continue; // minified / data lines
            }
            String trimmed = line.trim();
            boolean comment = CODE.contains(file.language())
                    && (trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*")
                    || (file.language() == Language.PYTHON && trimmed.startsWith("#")));
            for (Rule rule : RULES) {
                if (!rule.languages().contains(file.language())) {
                    continue;
                }
                if (comment && !rule.secret()) {
                    continue;
                }
                var matcher = rule.pattern().matcher(line);
                if (!matcher.find()) {
                    continue;
                }
                if ("SEC-SECRET-005".equals(rule.id()) && PLACEHOLDER.matcher(matcher.group()).find()) {
                    continue;
                }
                // API-usage rules describe code, not text: a match that starts inside a string literal
                // (documentation, test fixtures, rule definitions) is not a call and is skipped.
                if (!rule.secret() && !rule.matchesStrings() && insideStringLiteral(line, matcher.start())) {
                    continue;
                }
                out.add(FindingCandidate.staticFinding("codesage-rules", rule.category(), rule.severity(), rule.title(),
                        file.path(), i + 1, i + 1, rule.description(), rule.impact(), rule.recommendation(), rule.id(),
                        rule.secret()));
            }
        }
    }

    private void validateJson(SourceFile file, List<FindingCandidate> out) {
        String lower = file.path().toLowerCase();
        // tsconfig/jsconfig/.eslintrc and VS Code settings allow comments (JSONC), so they are skipped.
        if (lower.endsWith("tsconfig.json") || lower.endsWith("jsconfig.json") || lower.contains(".vscode/")
                || lower.contains("eslintrc")) {
            return;
        }
        try {
            json.readTree(file.content());
        } catch (JsonProcessingException e) {
            JsonLocation loc = e.getLocation();
            int line = loc == null ? 1 : Math.max(1, loc.getLineNr());
            out.add(FindingCandidate.staticFinding("codesage-rules", FindingCategory.BUG, Severity.HIGH,
                    "Invalid JSON", file.path(), line, line,
                    "The file is not valid JSON: " + e.getOriginalMessage(),
                    "Tools or services that load this file will fail at runtime.",
                    "Fix the syntax error at the reported line.", "CFG-JSON-PARSE", false));
        }
    }

    private void validateYaml(SourceFile file, List<FindingCandidate> out) {
        try {
            LoaderOptions options = new LoaderOptions();
            options.setAllowDuplicateKeys(false);
            options.setMaxAliasesForCollections(50);
            Yaml yaml = new Yaml(new SafeConstructor(options));
            for (Object ignored : yaml.loadAll(file.content())) {
                // iterate to force parsing of every document
            }
        } catch (org.yaml.snakeyaml.constructor.DuplicateKeyException e) {
            reportYaml(file, e, out);
        } catch (org.yaml.snakeyaml.constructor.ConstructorException e) {
            // Custom tags (e.g. CloudFormation !Ref) are valid YAML for their own tools - not reported.
        } catch (MarkedYAMLException e) {
            reportYaml(file, e, out);
        } catch (RuntimeException e) {
            // Unexpected parser failure: do not report a finding we cannot locate precisely.
        }
    }

    private static void reportYaml(SourceFile file, MarkedYAMLException e, List<FindingCandidate> out) {
        {
            int line = e.getProblemMark() == null ? 1 : e.getProblemMark().getLine() + 1;
            out.add(FindingCandidate.staticFinding("codesage-rules", FindingCategory.BUG, Severity.HIGH,
                    "Invalid YAML", file.path(), line, line,
                    "The file is not valid YAML: " + e.getProblem(),
                    "Configuration loaders (CI, Kubernetes, Spring, Compose) will reject this file.",
                    "Fix the indentation/syntax error, or remove the duplicate key at the reported line.",
                    "CFG-YAML-PARSE", false));
        }
    }
}
