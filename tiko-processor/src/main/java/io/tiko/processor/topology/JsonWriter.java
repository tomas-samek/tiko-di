package io.tiko.processor.topology;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Minimal streaming JSON writer. No third-party deps — the processor pulls in
 * JavaPoet and AutoService already; pulling Jackson would noticeably inflate the
 * annotation-processor jar that every downstream build inherits.
 *
 * <p>Usage: {@code try (var jw = new JsonWriter(writer)) { ... }}.
 * Methods return {@code this} for chaining. Compact by default; pass
 * {@code pretty = true} for two-space-indented output.
 *
 * <p>The caller owns the lifecycle of the supplied {@link Writer}. {@link #close()} flushes
 * but does not close it — typically the writer is wrapped in an outer try-with-resources
 * that handles its close.
 */
public final class JsonWriter implements AutoCloseable {

    private final Writer out;
    private final boolean pretty;
    private final Deque<Frame> stack = new ArrayDeque<>();
    private boolean expectingValue = false;

    public JsonWriter(Writer out) {
        this(out, false);
    }

    public JsonWriter(Writer out, boolean pretty) {
        this.out = out;
        this.pretty = pretty;
    }

    public JsonWriter object() {
        writeSeparatorIfNeeded();
        write("{");
        stack.push(new Frame(true, true));
        return this;
    }

    public JsonWriter endObject() {
        Frame f = stack.pop();
        if (pretty && !f.first) {
            write("\n");
            writeIndent();
        }
        write("}");

        return this;
    }

    public JsonWriter array() {
        writeSeparatorIfNeeded();
        write("[");
        stack.push(new Frame(false, true));
        return this;
    }

    public JsonWriter endArray() {
        Frame f = stack.pop();
        if (pretty && !f.first) {
            write("\n");
            writeIndent();
        }
        write("]");

        return this;
    }

    public JsonWriter field(String name) {
        if (stack.isEmpty() || !stack.peek().isObject) {
            throw new IllegalStateException("field() requires an open object");
        }
        Frame f = stack.peek();
        if (!f.first) write(",");
        if (pretty) {
            write("\n");
            writeIndent();
        }
        f.first = false;
        write("\"");
        writeEscaped(name);
        write("\"");
        write(pretty ? ": " : ":");
        expectingValue = true;
        return this;
    }

    public JsonWriter value(String s) {
        writeSeparatorIfNeeded();
        if (s == null) {
            write("null");
        } else {
            write("\"");
            writeEscaped(s);
            write("\"");
        }

        return this;
    }

    public JsonWriter value(long v) {
        writeSeparatorIfNeeded();
        write(Long.toString(v));

        return this;
    }

    public JsonWriter value(boolean v) {
        writeSeparatorIfNeeded();
        write(v ? "true" : "false");

        return this;
    }

    public JsonWriter value(double v) {
        writeSeparatorIfNeeded();
        write(Double.toString(v));

        return this;
    }

    public JsonWriter nullValue() {
        writeSeparatorIfNeeded();
        write("null");

        return this;
    }

    /** Write a raw JSON fragment — used to splice in pre-serialized JSON Schema sub-trees. */
    public JsonWriter raw(String json) {
        writeSeparatorIfNeeded();
        write(json);

        return this;
    }

    @Override
    public void close() {
        if (!stack.isEmpty()) {
            throw new IllegalStateException("Unclosed JSON containers: " + stack.size());
        }
        try {
            out.flush();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // ----- internal -----

    private void writeSeparatorIfNeeded() {
        if (expectingValue) {
            // already wrote " : " via field()
            expectingValue = false;
            return;
        }
        if (stack.isEmpty()) return;
        Frame f = stack.peek();
        if (f.isObject) return; // object values are always preceded by field()
        // array element
        if (!f.first) write(",");
        if (pretty) {
            write("\n");
            writeIndent();
        }
        f.first = false;
    }

    private void writeIndent() {
        for (int i = 0; i < stack.size(); i++) write("  ");
    }

    private void write(String s) {
        try {
            out.write(s);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void writeEscaped(String s) {
        var sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        write(sb.toString());
    }

    private static final class Frame {
        final boolean isObject;
        boolean first;

        Frame(boolean isObject, boolean first) {
            this.isObject = isObject;
            this.first = first;
        }
    }
}
