package io.tiko.config.internal;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;

/** SnakeYAML-backed loader that produces a {@code Map<String, Object>} tree. */
public final class YamlLoader {

    private YamlLoader() {}

    public static Map<String, Object> load(InputStream input) {
        LoaderOptions opts = new LoaderOptions();
        opts.setAllowDuplicateKeys(false);
        Yaml yaml = new Yaml(opts);
        Object loaded = yaml.load(input);
        if (loaded == null) return new LinkedHashMap<>();
        if (loaded instanceof Map<?, ?> m) {
            @SuppressWarnings("unchecked")
            Map<String, Object> stringKeyed = stringKeyed((Map<Object, Object>) m);
            return stringKeyed;
        }
        throw new IllegalArgumentException("YAML root must be a mapping; got " + loaded.getClass().getSimpleName());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> stringKeyed(Map<Object, Object> in) {
        Map<String, Object> out = new LinkedHashMap<>(in.size());
        for (Map.Entry<Object, Object> e : in.entrySet()) {
            String k = e.getKey().toString();
            Object v = e.getValue();
            if (v instanceof Map<?, ?> nested) v = stringKeyed((Map<Object, Object>) nested);
            out.put(k, v);
        }
        return out;
    }
}
