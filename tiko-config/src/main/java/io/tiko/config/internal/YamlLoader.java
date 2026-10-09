package io.tiko.config.internal;

import io.tiko.ConfigIssue;
import io.tiko.ConfigIssueCode;
import io.tiko.SourceLocation;
import io.tiko.config.ConfigValidationException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.Mark;
import org.yaml.snakeyaml.error.MarkedYAMLException;
import org.yaml.snakeyaml.nodes.MappingNode;
import org.yaml.snakeyaml.nodes.Node;
import org.yaml.snakeyaml.nodes.NodeTuple;
import org.yaml.snakeyaml.nodes.ScalarNode;
import org.yaml.snakeyaml.nodes.SequenceNode;

/**
 * SnakeYAML-backed loader that produces a {@link LoadedYaml} carrier with
 * both the data tree and a parallel dot-path → {@link SourceLocation}
 * index. The location index drives anchored error messages in
 * {@code ConfigurationFailure} / {@code ConfigValidationException}.
 */
public final class YamlLoader {

    private YamlLoader() {}

    /**
     * Loaded YAML plus the parallel location index. Both maps use
     * {@link LinkedHashMap} so iteration preserves YAML order.
     */
    public record LoadedYaml(Map<String, Object> data, Map<String, SourceLocation> locations) {}

    public static LoadedYaml load(InputStream input, String sourceLabel) {
        var opts = new LoaderOptions();
        opts.setAllowDuplicateKeys(false);
        // SafeConstructor pinned explicitly: we only ever load data (Map/List/scalar), never
        // instantiate arbitrary Java types. SnakeYAML 2.x already defaults to safe behavior,
        // but spelling it out makes the security property a code-level invariant.
        var yaml = new Yaml(new SafeConstructor(opts));

        Node root;
        try {
            root = yaml.compose(new java.io.InputStreamReader(input, java.nio.charset.StandardCharsets.UTF_8));
        } catch (MarkedYAMLException e) {
            // A YAML syntax error (unclosed bracket, bad indentation) would otherwise surface as a
            // raw SnakeYAML exception. Re-shape it as a Tiko config error anchored at the source's
            // file:line:col so a malformed file reads like every other config failure at boot.
            throw malformedYaml(sourceLabel, e);
        }
        if (root == null) {
            return new LoadedYaml(new LinkedHashMap<>(), new LinkedHashMap<>());
        }
        if (!(root instanceof MappingNode rootMapping)) {
            throw new IllegalArgumentException(
                    "YAML root must be a mapping; got " + root.getClass().getSimpleName());
        }

        Map<String, Object> data = new LinkedHashMap<>();
        Map<String, SourceLocation> locations = new LinkedHashMap<>();
        new Walker(sourceLabel).walkMapping(rootMapping, "", data, locations);
        return new LoadedYaml(data, locations);
    }

    /**
     * Upper bound on the nodes a configuration document may expand to. Aliases are copied, not
     * shared, so a few nested aliases can expand a short file exponentially (#498). Real
     * configuration stays orders of magnitude below this.
     */
    static final int MAX_EXPANDED_NODES = 100_000;

    /**
     * Turns the composed node graph into plain maps and lists. Aliases make that graph a DAG or,
     * when an alias points at its own ancestor, cyclic, so the walk tracks the collections on the
     * current path and counts every node it copies (#498).
     */
    private static final class Walker {
        private final String sourceLabel;
        private final Set<Node> onPath = Collections.newSetFromMap(new IdentityHashMap<>());
        private int expanded;

        Walker(String sourceLabel) {
            this.sourceLabel = sourceLabel;
        }

        void walkMapping(
                MappingNode mapping,
                String pathPrefix,
                Map<String, Object> outData,
                Map<String, SourceLocation> outLocations) {
            enter(mapping);
            Set<String> seen = new HashSet<>();
            for (NodeTuple t : mapping.getValue()) {
                if (!(t.getKeyNode() instanceof ScalarNode keyNode)) {
                    continue; // skip non-string keys (defensive — SafeConstructor on a Map already enforces)
                }
                String key = keyNode.getValue();
                String fullPath = pathPrefix.isEmpty() ? key : pathPrefix + "." + key;
                if (!seen.add(key)) {
                    // compose() never runs the constructor's duplicate-key check, so a repeated
                    // key would otherwise silently keep the last value.
                    throw invalid(keyNode, "duplicate key '" + fullPath + "'");
                }

                // Anchor on the key node — section headers point to "section:" (the header line),
                // and leaf scalars typically share a line with their key, so "db.url" still resolves
                // to the "url:" line. Using the value node would push section anchors down to the
                // first nested key's line.
                outLocations.put(fullPath, locationOf(keyNode));
                outData.put(key, value(t.getValueNode(), fullPath, outLocations));
            }
            onPath.remove(mapping);
        }

        private List<Object> walkSequence(SequenceNode seq) {
            enter(seq);
            List<Object> out = new ArrayList<>(seq.getValue().size());
            // value() updates the walk's path set and node budget; CLAUDE.md keeps loops with side effects as loops.
            for (Node item : seq.getValue()) { // NOSONAR java:S9391
                // list elements aren't location-indexed in v1
                out.add(value(item, "", new LinkedHashMap<>()));
            }
            onPath.remove(seq);
            return out;
        }

        private Object value(Node node, String path, Map<String, SourceLocation> outLocations) {
            if (node instanceof MappingNode nestedMapping) {
                Map<String, Object> nested = new LinkedHashMap<>();
                walkMapping(nestedMapping, path, nested, outLocations);
                return nested;
            }
            if (node instanceof SequenceNode seq) return walkSequence(seq);
            count(node);
            return node instanceof ScalarNode scalar ? parseScalar(scalar) : null;
        }

        private void enter(Node collection) {
            if (!onPath.add(collection)) {
                throw invalid(collection, "recursive alias: a value refers to a collection that contains it");
            }
            count(collection);
        }

        private void count(Node node) {
            if (++expanded > MAX_EXPANDED_NODES) {
                throw invalid(
                        node,
                        "the document expands to more than " + MAX_EXPANDED_NODES
                                + " values through YAML aliases; reduce the aliasing");
            }
        }

        private SourceLocation locationOf(Node node) {
            Mark m = node.getStartMark();
            if (m == null) return new SourceLocation(sourceLabel, 0, 0);
            return new SourceLocation(sourceLabel, m.getLine() + 1, m.getColumn() + 1);
        }

        /**
         * Returns the scalar's literal text — binding is schema-aware (#343): the record
         * component declares the target type and every coercer parses from text (the same
         * path {@code @Default} string values already take), so YAML 1.1 implicit typing
         * only ever loses information. The previous re-parse through {@code yaml.load}
         * turned {@code NO} into {@code Boolean.FALSE}, {@code 0644} into octal 420 and
         * {@code 1:30} into sexagesimal 90 — and, because {@link ScalarNode#getValue()}
         * strips quote style, corrupted explicitly quoted strings too.
         *
         * <p>Only plain (unquoted) {@code null} / {@code Null} / {@code NULL} / {@code ~} /
         * empty scalars keep YAML's null semantics; a quoted "null" is the literal string.
         */
        private static Object parseScalar(ScalarNode scalar) {
            String text = scalar.getValue();
            if (scalar.getScalarStyle() == DumperOptions.ScalarStyle.PLAIN && isYamlNull(text)) {
                return null;
            }
            return text;
        }

        private static boolean isYamlNull(String text) {
            return text.isEmpty()
                    || text.equals("~")
                    || text.equals("null")
                    || text.equals("Null")
                    || text.equals("NULL");
        }

        private ConfigValidationException invalid(Node at, String problem) {
            SourceLocation loc = locationOf(at);
            String anchor = loc.line() > 0 ? sourceLabel + ":" + loc.line() + ":" + loc.column() : sourceLabel;
            return new ConfigValidationException(
                    sourceLabel, List.of(new ConfigIssue(ConfigIssueCode.INVALID_VALUE, anchor + ": " + problem)));
        }
    }

    private static ConfigValidationException malformedYaml(String sourceLabel, MarkedYAMLException e) {
        Mark mark = e.getProblemMark();
        String anchor =
                mark != null ? sourceLabel + ":" + (mark.getLine() + 1) + ":" + (mark.getColumn() + 1) : sourceLabel;
        String problem = e.getProblem() != null ? withoutEchoedName(e.getProblem()) : "malformed YAML";
        return new ConfigValidationException(
                sourceLabel, List.of(new ConfigIssue(ConfigIssueCode.INVALID_VALUE, anchor + ": " + problem)));
    }

    /**
     * SnakeYAML problems that repeat an alias or anchor name from the input. An unquoted value
     * starting with {@code *} (a password, say) parses as an alias, so the name can be a secret
     * (SEC-2, #493).
     */
    private static final Pattern ECHOED_NAME = Pattern.compile("^(found (?:undefined alias|duplicate anchor))\\b.*");

    private static String withoutEchoedName(String problem) {
        Matcher m = ECHOED_NAME.matcher(problem);
        return m.matches() ? m.group(1) : problem;
    }
}
