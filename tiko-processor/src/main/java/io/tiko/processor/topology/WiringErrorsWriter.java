package io.tiko.processor.topology;

import io.tiko.processor.model.WiringError;
import java.io.IOException;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.util.List;
import javax.annotation.processing.Filer;
import javax.tools.FileObject;
import javax.tools.StandardLocation;

/**
 * Emits {@code META-INF/tiko/wiring-errors.json} — a structured snapshot of every
 * compile-time diagnostic the annotation processor reported in this round.
 *
 * <p>Always written alongside {@code topology.json} (even on a clean build, when
 * the document is just {@code {"errors": []}}). Downstream tooling can therefore
 * rely on the file being present and avoid a separate existence probe.
 *
 * <p>Field order in each error entry: {@code kind}, {@code sourceFile}, {@code line},
 * {@code componentFqn}, {@code message}, {@code suggestedFix}. Null string fields are
 * written as JSON {@code null}; {@code line} is always an integer.
 */
public final class WiringErrorsWriter {

    private static final String PATH = "META-INF/tiko/wiring-errors.json";

    private final List<WiringError> errors;

    public WiringErrorsWriter(List<WiringError> errors) {
        this.errors = errors;
    }

    /** Writes the JSON resource via the supplied Filer. */
    public void write(Filer filer) throws IOException {
        FileObject f = filer.createResource(StandardLocation.CLASS_OUTPUT, "", PATH);
        try (Writer w = f.openWriter()) {
            renderTo(w);
        } catch (UncheckedIOException uncheckedIo) {
            throw uncheckedIo.getCause();
        }
    }

    /** Serializes to a String — used by unit tests so they don't need a Filer. */
    public String render() {
        var sw = new StringWriter();
        renderTo(sw);
        return sw.toString();
    }

    private void renderTo(Writer w) {
        try (var jw = new JsonWriter(w, true)) {
            jw.object();
            jw.field("errors").array();
            for (WiringError e : errors) {
                jw.object();
                jw.field("kind").value(e.kind());
                jw.field("sourceFile").value(e.sourceFile());
                jw.field("line").value((long) e.line());
                jw.field("componentFqn").value(e.componentFqn());
                jw.field("message").value(e.message());
                jw.field("suggestedFix").value(e.suggestedFix());
                jw.endObject();
            }
            jw.endArray();
            jw.endObject();
        }
    }
}
