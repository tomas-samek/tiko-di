package io.tiko;

import java.util.Objects;

/**
 * Configuration knobs for {@link Tiko#create(TikoOptions)}.
 *
 * <p>Use {@link #builder()} to construct an instance. The result is immutable.
 *
 * <pre>{@code
 * TikoOptions opts = TikoOptions.builder()
 *     .configSource(ConfigSources.classpath("config.yaml"))
 *     .errorHandler(ctx -> myMetrics.recordErrorContext(ctx))
 *     .build();
 * try (Container container = Tiko.create(opts)) { ... }
 * }</pre>
 *
 * <p>All knobs are optional. When omitted, the framework supplies sensible defaults:
 * configuration binding is skipped (and fails fast at startup if any
 * {@code @Configuration} record is declared); the default error handler logs at WARN
 * via slf4j.
 */
public final class TikoOptions {

    private final ConfigSource configSource;
    private final ErrorHandler errorHandler;

    private TikoOptions(Builder b) {
        this.configSource = b.configSource;
        this.errorHandler = b.errorHandler;
    }

    /**
     * @return the configured {@link ConfigSource}, or {@code null} if none was set
     */
    public ConfigSource configSource() {
        return configSource;
    }

    /**
     * @return the configured {@link ErrorHandler}, or {@code null} to use the framework default
     */
    public ErrorHandler errorHandler() {
        return errorHandler;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {

        private ConfigSource configSource;
        private ErrorHandler errorHandler;

        private Builder() {}

        public Builder configSource(ConfigSource source) {
            this.configSource = Objects.requireNonNull(source, "configSource");
            return this;
        }

        public Builder errorHandler(ErrorHandler handler) {
            this.errorHandler = Objects.requireNonNull(handler, "errorHandler");
            return this;
        }

        public TikoOptions build() {
            return new TikoOptions(this);
        }
    }
}
