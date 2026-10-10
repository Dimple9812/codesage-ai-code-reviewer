package com.codesage.analysis;

import java.util.List;

/** A deterministic analyzer that inspects source text. Implementations must never execute repository code. */
public interface StaticAnalyzer {

    /** Stable id used in settings, e.g. "pmd". */
    String id();

    boolean isEnabledGlobally();

    List<FindingCandidate> analyze(List<SourceFile> files);
}
