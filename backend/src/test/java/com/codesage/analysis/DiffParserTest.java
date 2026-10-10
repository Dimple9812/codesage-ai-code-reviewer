package com.codesage.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;

import org.junit.jupiter.api.Test;

class DiffParserTest {

    @Test
    void mapsAddedAndContextLinesToNewSideNumbers() {
        String patch = """
                @@ -10,4 +10,5 @@ class A {
                   int a = 1;
                -  int b = 2;
                +  int b = 3;
                +  int c = 4;
                   return a;
                @@ -40,2 +41,3 @@
                   x();
                +  y();
                \\ No newline at end of file""";
        DiffParser.DiffLines lines = DiffParser.parse(patch);
        assertThat(lines.added()).containsExactly(11, 12, 42);
        assertThat(lines.visible()).containsExactly(10, 11, 12, 13, 41, 42);
    }

    @Test
    void nullPatchYieldsEmptySets() {
        assertThat(DiffParser.parse(null).added()).isEmpty();
    }

    @Test
    void compactsRanges() {
        assertThat(DiffParser.toRanges(Set.of(1, 2, 3, 7, 9, 10), 100)).isEqualTo("1-3,7,9-10");
    }
}
