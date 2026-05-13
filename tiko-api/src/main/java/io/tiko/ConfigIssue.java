package io.tiko;

/**
 * One accumulated {@code @Configuration} binding failure, exposed to observability code via
 * {@link ConfigurationFailure}. Carries a stable {@link ConfigIssueCode} for programmatic
 * dispatch plus a human-readable description (which may embed YAML source/line/column
 * anchor info as plain text).
 *
 * @param code        the stable category of this issue — pattern-match on this in handlers
 * @param description the formatted human-readable message; may include anchor info like
 *                    {@code "config.yaml:5:7"} when the underlying error has a known location
 */
public record ConfigIssue(ConfigIssueCode code, String description) {}
