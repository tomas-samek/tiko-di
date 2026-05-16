// tiko-config/src/main/java/io/tiko/config/internal/Interpolator.java
package io.tiko.config.internal;

import io.tiko.ConfigIssueCode;
import io.tiko.config.BindContext;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Substitutes {@code ${VAR}} and {@code ${VAR:default}} in YAML scalar
 * <em>values</em> (not keys). Missing variables without defaults are reported
 * to the supplied {@link BindContext}.
 */
public final class Interpolator {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([A-Za-z_][A-Za-z0-9_]*)(?::([^}]*))?\\}");

    private Interpolator() {}

    public static Object interpolate(Object node, Function<String, String> env, BindContext ctx) {
        return interpolate(node, "", env, ctx);
    }

    private static Object interpolate(Object node, String path, Function<String, String> env, BindContext ctx) {
        if (node instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>(m.size());
            for (Map.Entry<?, ?> e : m.entrySet()) {
                String key = e.getKey().toString();
                String childPath = path.isEmpty() ? key : path + "." + key;
                out.put(key, interpolate(e.getValue(), childPath, env, ctx));
            }
            return out;
        }
        if (node instanceof List<?> l) {
            List<Object> out = new ArrayList<>(l.size());
            for (Object e : l) out.add(interpolate(e, path, env, ctx));
            return out;
        }
        if (node instanceof String s) {
            return interpolateScalar(s, path, env, ctx);
        }
        return node;
    }

    private static String interpolateScalar(String s, String path, Function<String, String> env, BindContext ctx) {
        Matcher m = PLACEHOLDER.matcher(s);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String name = m.group(1);
            String def = m.group(2);
            String value = env.apply(name);
            if (value == null) {
                if (def != null) {
                    value = def;
                } else {
                    ctx.reportAtPath(
                            ConfigIssueCode.INTERPOLATION_UNRESOLVED,
                            path,
                            "${" + name + "} is not set and has no default");
                    value = "";
                }
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(value));
        }
        m.appendTail(sb);
        return sb.toString();
    }
}
