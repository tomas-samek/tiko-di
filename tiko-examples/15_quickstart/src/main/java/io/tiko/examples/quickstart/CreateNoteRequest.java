package io.tiko.examples.quickstart;

import java.util.List;

/** The body of {@code POST /notes}; {@code tags} may be left out. */
public record CreateNoteRequest(String text, List<String> tags) {
    public CreateNoteRequest {
        tags = tags == null ? List.of() : List.copyOf(tags);
    }
}
