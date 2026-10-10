package com.codesage.analysis;

import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parses GitHub unified diff patches into new-side line numbers. */
public final class DiffParser {

    private static final Pattern HUNK = Pattern.compile("^@@ -\\d+(?:,\\d+)? \\+(\\d+)(?:,(\\d+))? @@.*");

    public record DiffLines(Set<Integer> added, Set<Integer> visible) {
    }

    private DiffParser() {
    }

    public static DiffLines parse(String patch) {
        Set<Integer> added = new TreeSet<>();
        Set<Integer> visible = new TreeSet<>();
        if (patch == null) {
            return new DiffLines(added, visible);
        }
        int newLine = -1;
        for (String line : patch.split("\n", -1)) {
            Matcher m = HUNK.matcher(line);
            if (m.matches()) {
                newLine = Integer.parseInt(m.group(1));
                continue;
            }
            if (newLine < 0 || line.startsWith("\\")) {
                continue;
            }
            if (line.startsWith("+")) {
                added.add(newLine);
                visible.add(newLine);
                newLine++;
            } else if (line.startsWith("-")) {
                // removed line: exists only on the old side
            } else {
                visible.add(newLine);
                newLine++;
            }
        }
        return new DiffLines(added, visible);
    }

    /** Compact representation such as "3-7,12" for storage/display. */
    public static String toRanges(Set<Integer> lines, int maxLength) {
        StringBuilder sb = new StringBuilder();
        Integer start = null;
        Integer prev = null;
        for (Integer n : new TreeSet<>(lines)) {
            if (start == null) {
                start = n;
            } else if (n != prev + 1) {
                append(sb, start, prev);
                start = n;
            }
            prev = n;
        }
        if (start != null) {
            append(sb, start, prev);
        }
        String result = sb.toString();
        return result.length() > maxLength ? result.substring(0, result.lastIndexOf(',', maxLength)) : result;
    }

    private static void append(StringBuilder sb, int start, int end) {
        if (!sb.isEmpty()) {
            sb.append(',');
        }
        sb.append(start);
        if (end != start) {
            sb.append('-').append(end);
        }
    }
}
