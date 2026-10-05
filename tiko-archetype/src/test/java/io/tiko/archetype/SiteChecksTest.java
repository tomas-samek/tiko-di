package io.tiko.archetype;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class SiteChecksTest {

    /** A second job after {@code build}, so a lookup that strays out of the build job reads the wrong matrix. */
    private static final String INTEGRATION_JOB = """
              integration-test:
                runs-on: ubuntu-latest
                strategy:
                  matrix:
                    java: [ '21', '26' ]
            """;

    private static String workflow(String buildMatrix) {
        return """
                name: CI
                jobs:
                  build:
                    runs-on: ubuntu-latest
                    strategy:
                      matrix:
                """ + buildMatrix + "\n" + INTEGRATION_JOB;
    }

    static Stream<Arguments> buildMatrixShapes() {
        var jdks = List.of("21", "25", "26", "27");
        return Stream.of(
                Arguments.of("inline, single-quoted", "        java: [ '21', '25', '26', '27' ]", jdks),
                Arguments.of("inline, double-quoted", "        java: [ \"21\", \"25\", \"26\", \"27\" ]", jdks),
                Arguments.of("inline, unquoted", "        java: [21, 25, 26, 27]", jdks),
                Arguments.of("block list", matrixLines("java:", "  - '21'", "  - '25'", "  - \"26\"", "  - 27"), jdks),
                Arguments.of(
                        "comment above the key",
                        matrixLines("# JDK support policy comment", "java: [ '21', '25', '26', '27' ]"),
                        jdks));
    }

    /** Lines indented to sit under the build job's {@code matrix:} key (8 spaces). */
    private static String matrixLines(String... lines) {
        return Stream.of(lines).map(line -> "        " + line).collect(Collectors.joining("\n"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("buildMatrixShapes")
    void readsTheBuildJobMatrixInAnyYamlShape(String shape, String buildMatrix, List<String> expected) {
        assertThat(SiteChecks.buildJobJdks(workflow(buildMatrix))).isEqualTo(expected);
    }

    @Test
    void buildJobWithoutAJavaMatrixIsReportedNotReadFromTheNextJob() {
        var noMatrix = workflow("        os: [ ubuntu-latest ]");

        assertThatThrownBy(() -> SiteChecks.buildJobJdks(noMatrix))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("build job")
                .hasMessageContaining("java");
    }

    static Stream<Arguments> rootRelativeReferences() {
        return Stream.of(
                Arguments.of("double-quoted href", "<link href=\"/style.css\">", ""),
                Arguments.of("single-quoted href", "<link href='/style.css'>", ""),
                Arguments.of("unquoted src", "<img src=/logo.svg>", ""),
                Arguments.of("srcset, first candidate", "<img srcset=\"/a.png 1x, b.png 2x\">", ""),
                Arguments.of("srcset, later candidate", "<img srcset=\"a.png 1x, /b.png 2x\">", ""),
                Arguments.of("css url()", "", "body { background: url(/grid.svg); }"),
                Arguments.of("css url() quoted", "", "body { background: url(\"/grid.svg\"); }"),
                Arguments.of("css @import", "", "@import '/theme.css';"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("rootRelativeReferences")
    void flagsRootRelativeReferences(String form, String html, String css) {
        assertThat(SiteChecks.rootRelativeReferences(html, css)).isNotEmpty();
    }

    static Stream<Arguments> allowedReferences() {
        return Stream.of(
                Arguments.of("relative href", "<link href=\"style.css\">", ""),
                Arguments.of("dot-relative href", "<a href=\"./\">", ""),
                Arguments.of("in-page anchor", "<a href=\"#install\">", ""),
                Arguments.of("absolute https", "<a href=\"https://github.com/x\">", ""),
                Arguments.of("protocol-relative", "<img src=\"//cdn.example/x.png\">", ""),
                Arguments.of("closing tags and text", "<p>a/b</p></div>", ""),
                Arguments.of("css relative url()", "", "body { background: url(grid.svg); }"),
                Arguments.of("css comment slash", "", "/* tokens */ :root { --a: 1; }"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("allowedReferences")
    void allowsRelativeAbsoluteAndProtocolRelativeReferences(String form, String html, String css) {
        assertThat(SiteChecks.rootRelativeReferences(html, css)).isEmpty();
    }
}
