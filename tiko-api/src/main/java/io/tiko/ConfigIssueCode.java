package io.tiko;

/**
 * Stable category for a single {@link ConfigIssue}. Exhaustive {@code switch} on this enum
 * is the recommended way for observability code to dispatch on config-failure kinds.
 *
 * <p>Adding a new code is intentionally a compile-time-loud breaking change for users with
 * exhaustive {@code switch} expressions — same contract as adding a new {@link ErrorContext}
 * permit. When a new failure mode is recognised by the framework, callers are told to handle
 * it.
 */
public enum ConfigIssueCode {

    /**
     * Two or more {@code @Configuration} records claim the same top-level prefix across
     * modules. Each prefix must be unique across the entire reactor.
     */
    DUPLICATE_PREFIX,

    /**
     * The YAML source contains a top-level section that is not claimed by any
     * {@code @Configuration} record.
     */
    UNKNOWN_SECTION,

    /**
     * A required top-level section (claimed by a {@code @Configuration} record) is absent
     * from the YAML source, or present but not a mapping.
     */
    MISSING_SECTION,

    /**
     * A required scalar field of an {@code @Configuration} record is absent from the YAML
     * source and has no {@code @Default}.
     */
    MISSING_KEY,

    /**
     * The YAML source contains a key under an {@code @Configuration} section that does not
     * map to any record component.
     */
    UNKNOWN_KEY,

    /**
     * A value present in the YAML source could not be coerced to the target type (e.g. a
     * string where an int is expected, or a malformed Duration). Also covers wrong-shaped
     * sections (e.g. a scalar where a mapping is expected).
     */
    INVALID_VALUE,

    /**
     * A {@code ${VAR}} interpolation references an environment variable that is not set and
     * supplies no default.
     */
    INTERPOLATION_UNRESOLVED
}
