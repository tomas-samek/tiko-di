package io.tiko.examples.http;

import io.tiko.annotations.Configuration;

/** The {@code http} section of {@code config.yaml}; port {@code 0} lets the OS pick a free one. */
@Configuration(prefix = "http")
public record HttpSettings(int port) {}
