package io.tiko.processor.topology;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.StringWriter;
import org.junit.jupiter.api.Test;

class JsonWriterTest {

    @Test
    void writesNull() {
        var sw = new StringWriter();
        try (var jw = new JsonWriter(sw)) {
            jw.nullValue();
        }
        assertThat(sw.toString()).isEqualTo("null");
    }

    @Test
    void writesString() {
        var sw = new StringWriter();
        try (var jw = new JsonWriter(sw)) {
            jw.value("hello");
        }
        assertThat(sw.toString()).isEqualTo("\"hello\"");
    }

    @Test
    void escapesQuotesAndBackslashesAndControls() {
        var sw = new StringWriter();
        try (var jw = new JsonWriter(sw)) {
            jw.value("a\"b\\c\nd\te");
        }
        assertThat(sw.toString()).isEqualTo("\"a\\\"b\\\\c\\nd\\te\"");
    }

    @Test
    void writesIntAndBoolean() {
        var sw = new StringWriter();
        try (var jw = new JsonWriter(sw)) {
            jw.array();
            jw.value(42);
            jw.value(true);
            jw.value(false);
            jw.endArray();
        }
        assertThat(sw.toString()).isEqualTo("[42,true,false]");
    }

    @Test
    void writesObjectWithFields() {
        var sw = new StringWriter();
        try (var jw = new JsonWriter(sw)) {
            jw.object();
            jw.field("name").value("Tiko");
            jw.field("version").value(1);
            jw.endObject();
        }
        assertThat(sw.toString()).isEqualTo("{\"name\":\"Tiko\",\"version\":1}");
    }

    @Test
    void writesNestedObjectsAndArrays() {
        var sw = new StringWriter();
        try (var jw = new JsonWriter(sw)) {
            jw.object();
            jw.field("items").array();
            jw.object().field("k").value("v").endObject();
            jw.endArray();
            jw.endObject();
        }
        assertThat(sw.toString()).isEqualTo("{\"items\":[{\"k\":\"v\"}]}");
    }

    @Test
    void emptyObjectAndArray() {
        var sw = new StringWriter();
        try (var jw = new JsonWriter(sw)) {
            jw.array();
            jw.object().endObject();
            jw.endArray();
        }
        assertThat(sw.toString()).isEqualTo("[{}]");
    }

    @Test
    void prettyPrintIndentsTwoSpaces() {
        var sw = new StringWriter();
        try (var jw = new JsonWriter(sw, true)) {
            jw.object().field("a").value(1).field("b").value(2).endObject();
        }
        assertThat(sw.toString()).isEqualTo("{\n  \"a\": 1,\n  \"b\": 2\n}");
    }
}
