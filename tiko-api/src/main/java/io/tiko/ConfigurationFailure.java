package io.tiko;

import java.util.List;

/**
 * Error context raised once when {@code @Configuration} binding fails during
 * {@code Tiko.create(...)}. The {@code issues} list contains every individual problem
 * detected in this binding pass (missing keys, coercion failures, unknown sections, etc.) —
 * config errors are intentionally accumulated and reported together so users see the whole
 * picture rather than one-at-a-time.
 *
 * <p>The framework calls {@link ErrorHandler#onError(ErrorContext)} before re-throwing the
 * underlying {@code ConfigValidationException}, so observability code sees the failure
 * even though startup still hard-fails (parallel to {@link PostConstructFailure}).
 *
 * @param issues the per-issue list — non-empty by construction; each entry is a
 *               {@link ConfigIssue} with a stable {@link ConfigIssueCode} and a formatted
 *               description
 * @param cause  the underlying {@code ConfigValidationException} that's about to propagate
 */
public record ConfigurationFailure(List<ConfigIssue> issues, Throwable cause) implements ErrorContext {

    public ConfigurationFailure {
        issues = List.copyOf(issues);
    }
}
