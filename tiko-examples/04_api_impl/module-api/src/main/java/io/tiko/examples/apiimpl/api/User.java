package io.tiko.examples.apiimpl.api;

/**
 * Plain DTO shared between api and impl. Lives in the api jar so app code can pass it
 * across the public surface without ever needing the impl jar at compile time.
 */
public record User(Long id, String name, String email) {}
