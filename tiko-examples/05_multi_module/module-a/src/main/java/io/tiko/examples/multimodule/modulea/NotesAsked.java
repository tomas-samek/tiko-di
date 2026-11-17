package io.tiko.examples.multimodule.modulea;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/** Asks for the origins of the notes of the unit of work that handles it. */
public record NotesAsked(CompletableFuture<List<String>> answer) {}
