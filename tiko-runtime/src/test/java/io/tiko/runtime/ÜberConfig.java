package io.tiko.runtime;

/** A config record whose non-ASCII name must survive the {@code configs.txt} round-trip (#582). */
record ÜberConfig(String value) {}
