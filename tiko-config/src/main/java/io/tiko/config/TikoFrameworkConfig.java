package io.tiko.config;

import io.tiko.annotations.Configuration;
import io.tiko.annotations.Default;
import java.time.Duration;

/**
 * The framework's own {@code tiko.*} settings (#114). Bound through the standard pipeline like
 * any {@code @Configuration}, so values get {@code ${VAR}} interpolation, source-anchored errors
 * and unknown-key checks. Module settings live under their own sub-prefixes ({@code tiko.kafka}).
 * {@code Tiko.create(...)} reads it; it is not injected into application components.
 *
 * @param shutdownTimeout graceful drain window for the event executor at container shutdown;
 *     {@code TikoOptions.shutdownTimeout(...)} set in code takes precedence
 */
@Configuration(prefix = "tiko")
public record TikoFrameworkConfig(@Default("PT10S") Duration shutdownTimeout) {}
