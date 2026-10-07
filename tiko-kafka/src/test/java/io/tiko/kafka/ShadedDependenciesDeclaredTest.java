package io.tiko.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

/**
 * Every Jackson artifact that {@code tiko-kafka} shades into its jar is declared directly in
 * {@code tiko-kafka/pom.xml}, at the exact version that gets shaded, through the root
 * {@code dependencyManagement} (SEC-9, #476). GitHub's dependency graph reads declared
 * dependencies only; a module that is shaded but reached transitively gets no Dependabot alert
 * and no Dependency Review check.
 */
class ShadedDependenciesDeclaredTest {

    /** Relative to the {@code tiko-kafka} module directory. */
    private static final Path MODULE_POM = Path.of("pom.xml");

    private static final Path ROOT_POM = Path.of("..", "pom.xml");

    /** The groups the shade plugin's {@code <include>} bundles: {@code com.fasterxml.jackson.{core,datatype}}. */
    private static final Pattern SHADED_JAR = Pattern.compile(
            ".*[/\\\\]com[/\\\\]fasterxml[/\\\\]jackson[/\\\\](core|datatype)[/\\\\]([^/\\\\]+)[/\\\\]([^/\\\\]+)[/\\\\]\\2-\\3\\.jar$");

    private static final Pattern PROPERTY = Pattern.compile("\\$\\{([^}]+)}");

    static Stream<Arguments> shadedArtifacts() {
        return Stream.of(System.getProperty("java.class.path").split(File.pathSeparator))
                .map(SHADED_JAR::matcher)
                .filter(Matcher::matches)
                .map(m -> Arguments.of("com.fasterxml.jackson." + m.group(1) + ":" + m.group(2), m.group(3)))
                .distinct();
    }

    @ParameterizedTest(name = "{0}:{1}")
    @MethodSource("shadedArtifacts")
    void shadedArtifactIsDeclaredAtItsShadedVersion(String coordinate, String shadedVersion) throws Exception {
        var module = parse(MODULE_POM);
        var root = parse(ROOT_POM);

        assertThat(declaredIn(module, "dependencies"))
                .as(
                        "%s is shaded into tiko-kafka but not declared in tiko-kafka/pom.xml, so GitHub's"
                                + " dependency graph (and Dependabot) can't see it",
                        coordinate)
                .containsKey(coordinate);
        assertThat(resolve(declaredIn(root, "dependencyManagement").get(coordinate), properties(root)))
                .as("root pom dependencyManagement must pin %s at the version that gets shaded", coordinate)
                .isEqualTo(shadedVersion);
    }

    /**
     * Since Jackson 2.20, {@code jackson-annotations} is versioned by major.minor only ({@code 2.22},
     * not {@code 2.22.3}). Pinning it overrides what {@code jackson-databind} asks for, so a
     * {@code jackson.version} bump must move it to the same minor.
     */
    @Test
    void annotationsVersionTracksTheJacksonMinor() throws Exception {
        var props = properties(parse(ROOT_POM));
        var jackson = props.get("jackson.version");

        assertThat(props)
                .as("jackson-annotations.version must be jackson.version's major.minor (%s)", jackson)
                .containsEntry("jackson-annotations.version", jackson.substring(0, jackson.lastIndexOf('.')));
    }

    private static Element parse(Path pom) throws Exception {
        var factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        return factory.newDocumentBuilder().parse(pom.toFile()).getDocumentElement();
    }

    /** {@code groupId:artifactId → version} (version may be absent) under the given top-level section. */
    private static Map<String, String> declaredIn(Element project, String section) {
        var out = new HashMap<String, String>();
        var sections = project.getElementsByTagName(section);
        for (int s = 0; s < sections.getLength(); s++) {
            var sectionNode = sections.item(s);
            if (sectionNode.getParentNode() != project) continue;
            var deps = ((Element) sectionNode).getElementsByTagName("dependency");
            for (int i = 0; i < deps.getLength(); i++) {
                var dep = (Element) deps.item(i);
                out.put(child(dep, "groupId") + ":" + child(dep, "artifactId"), child(dep, "version"));
            }
        }
        return out;
    }

    private static Map<String, String> properties(Element project) {
        var out = new HashMap<String, String>();
        var props = project.getElementsByTagName("properties");
        for (int p = 0; p < props.getLength(); p++) {
            if (props.item(p).getParentNode() != project) continue;
            var children = props.item(p).getChildNodes();
            for (int i = 0; i < children.getLength(); i++) {
                if (children.item(i) instanceof Element e)
                    out.put(e.getTagName(), e.getTextContent().trim());
            }
        }
        return out;
    }

    private static String resolve(String version, Map<String, String> properties) {
        if (version == null) return null;
        var m = PROPERTY.matcher(version);
        return m.matches() ? properties.get(m.group(1)) : version;
    }

    private static String child(Element parent, String tag) {
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element e && e.getTagName().equals(tag))
                return e.getTextContent().trim();
        }
        return null;
    }
}
