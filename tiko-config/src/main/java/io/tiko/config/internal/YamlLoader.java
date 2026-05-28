package io.tiko.config.internal;

import io.tiko.ConfigIssue;
import io.tiko.ConfigIssueCode;
import io.tiko.SourceLocation;
import io.tiko.config.ConfigValidationException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
        walkMapping(rootMapping, "", sourceLabel, data, locations);
        return new LoadedYaml(data, locations);
    }

    private static void walkMapping(
            MappingNode mapping,
            String pathPrefix,
            String sourceLabel,
            Map<String, Object> outData,
            Map<String, SourceLocation> outLocations) {
        for (NodeTuple t : mapping.getValue()) {
            if (!(t.getKeyNode() instanceof ScalarNode keyNode)) {
                continue; // skip non-string keys (defensive — SafeConstructor on a Map already enforces)
            }
            String key = keyNode.getValue();
            String fullPath = pathPrefix.isEmpty() ? key : pathPrefix + "." + key;
            Node valueNode = t.getValueNode();

            // Anchor on the key node — section headers point to "section:" (the header line),
            // and leaf scalars typically share a line with their key, so "db.url" still resolves
            // to the "url:" line. Using the value node would push section anchors down to the
            // first nested key's line.
            outLocations.put(fullPath, locationOf(keyNode, sourceLabel));

            if (valueNode instanceof MappingNode nestedMapping) {
                Map<String, Object> nested = new LinkedHashMap<>();
                outData.put(key, nested);
                walkMapping(nestedMapping, fullPath, sourceLabel, nested, outLocations);
            } else if (valueNode instanceof SequenceNode seq) {
                outData.put(key, walkSequence(seq, sourceLabel));
            } else if (valueNode instanceof ScalarNode scalar) {
                outData.put(key, parseScalar(scalar));
            } else {
                outData.put(key, null);
            }
        }
    }

    private static List<Object> walkSequence(SequenceNode seq, String sourceLabel) {
        List<Object> out = new ArrayList<>(seq.getValue().size());
        for (Node item : seq.getValue()) {
            if (item instanceof MappingNode m) {
                Map<String, Object> nested = new LinkedHashMap<>();
                Map<String, SourceLocation> ignored =
                        new LinkedHashMap<>(); // list elements aren't location-indexed in v1
                walkMapping(m, "", sourceLabel, nested, ignored);
                out.add(nested);
            } else if (item instanceof SequenceNode s) {
                out.add(walkSequence(s, sourceLabel));
            } else if (item instanceof ScalarNode scalar) {
                out.add(parseScalar(scalar));
            } else {
                out.add(null);
            }
        }
        return out;
    }

    /**
     * Parse a scalar through SnakeYAML's default resolver so int/bool/etc. coercion
     * happens at YAML-load time exactly as the previous {@code yaml.load(...)} path
     * delivered them to the binder. Without this, every scalar would arrive as a
     * String and downstream coercers would have to re-parse.
     */
    private static Object parseScalar(ScalarNode scalar) {
        // SnakeYAML's SafeConstructor handles scalar parsing internally when going through
        // load(), but the compose() path returns raw Nodes. Re-run a tiny Yaml.load() on
        // just the scalar's text to reuse the same coercion rules.
        var opts = new LoaderOptions();
        opts.setAllowDuplicateKeys(false);
        var yaml = new Yaml(new SafeConstructor(opts));
        return yaml.load(scalar.getValue());
    }

    private static ConfigValidationException malformedYaml(String sourceLabel, MarkedYAMLException e) {
        Mark mark = e.getProblemMark();
        String anchor =
                mark != null ? sourceLabel + ":" + (mark.getLine() + 1) + ":" + (mark.getColumn() + 1) : sourceLabel;
        String problem = e.getProblem() != null ? e.getProblem() : "malformed YAML";
        return new ConfigValidationException(
                sourceLabel, List.of(new ConfigIssue(ConfigIssueCode.INVALID_VALUE, anchor + ": " + problem)));
    }

    private static SourceLocation locationOf(Node node, String sourceLabel) {
        Mark m = node.getStartMark();
        if (m == null) return new SourceLocation(sourceLabel, 0, 0);
        return new SourceLocation(sourceLabel, m.getLine() + 1, m.getColumn() + 1);
    }
}
