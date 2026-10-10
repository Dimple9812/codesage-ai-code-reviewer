package com.codesage.analysis;

import java.util.Locale;
import java.util.Optional;

public enum Language {
    JAVA("java", "java"),
    JAVASCRIPT("javascript", "javascript"),
    TYPESCRIPT("typescript", "typescript"),
    PYTHON("python", "python"),
    C("c", "c"),
    CPP("cpp", "cpp"),
    GO("go", "go"),
    SQL("sql", "sql"),
    JSON("json", "json"),
    YAML("yaml", "yaml"),
    CONFIG("config", "ini");

    private final String id;
    private final String editorLanguage;

    Language(String id, String editorLanguage) {
        this.id = id;
        this.editorLanguage = editorLanguage;
    }

    public String id() {
        return id;
    }

    /** Monaco editor language id. */
    public String editorLanguage() {
        return editorLanguage;
    }

    public static Optional<Language> fromId(String id) {
        for (Language l : values()) {
            if (l.id.equalsIgnoreCase(id)) {
                return Optional.of(l);
            }
        }
        return Optional.empty();
    }

    public static Optional<Language> detect(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        String file = lower.substring(lower.lastIndexOf('/') + 1);
        if (file.equals("dockerfile") || file.endsWith(".properties") || file.endsWith(".toml")
                || file.endsWith(".ini") || file.endsWith(".env.example") || file.equals("pom.xml")) {
            return Optional.of(CONFIG);
        }
        int dot = file.lastIndexOf('.');
        if (dot < 0) {
            return Optional.empty();
        }
        return switch (file.substring(dot + 1)) {
            case "java" -> Optional.of(JAVA);
            case "js", "jsx", "mjs", "cjs" -> Optional.of(JAVASCRIPT);
            case "ts", "tsx", "mts", "cts" -> Optional.of(TYPESCRIPT);
            case "py" -> Optional.of(PYTHON);
            case "c", "h" -> Optional.of(C);
            case "cc", "cpp", "cxx", "hpp", "hh", "hxx" -> Optional.of(CPP);
            case "go" -> Optional.of(GO);
            case "sql" -> Optional.of(SQL);
            case "json" -> Optional.of(JSON);
            case "yml", "yaml" -> Optional.of(YAML);
            default -> Optional.empty();
        };
    }
}
