package io.tiko.processor.core.javac;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Stream;
import javax.annotation.processing.Filer;
import javax.lang.model.element.Element;
import javax.tools.FileObject;
import javax.tools.JavaFileManager;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Resources are UTF-8 whatever the compiler's {@code -encoding}. A {@code FileObject}'s
 * {@code openWriter()} encodes with that setting, so the stub below encodes it as ISO-8859-1 and
 * only its output stream is plain: text written through the writer would show up as the wrong bytes.
 */
class JavacCodeOutputTest {

    @ParameterizedTest(name = "{0}")
    @MethodSource("contents")
    void writeResourceEncodesAsUtf8NotWithTheCompilersEncoding(String name, String content) throws IOException {
        var filer = new StubFiler();

        new JavacCodeOutput(filer).writeResource("META-INF/tiko/out.txt", content, List.of());

        assertThat(filer.resource.bytes()).isEqualTo(content.getBytes(StandardCharsets.UTF_8));
        assertThat(filer.resource.writerOpened).as("openWriter() used").isFalse();
    }

    static Stream<Arguments> contents() {
        return Stream.of(
                Arguments.of("ascii", "plain\n"),
                Arguments.of("latin1Accent", "héllo\n"),
                Arguments.of("outsideLatin1", "日本語 ✓\n"),
                Arguments.of("supplementary", "😀\n"));
    }

    private static final class StubResource extends SimpleJavaFileObject {

        private final ByteArrayOutputStream stream = new ByteArrayOutputStream();
        private boolean writerOpened;

        StubResource() {
            super(URI.create("mem:///out.txt"), Kind.OTHER);
        }

        byte[] bytes() {
            return stream.toByteArray();
        }

        @Override
        public OutputStream openOutputStream() {
            return stream;
        }

        @Override
        public Writer openWriter() {
            writerOpened = true;
            return new OutputStreamWriter(stream, StandardCharsets.ISO_8859_1);
        }
    }

    private static final class StubFiler implements Filer {

        private final StubResource resource = new StubResource();

        @Override
        public FileObject createResource(
                JavaFileManager.Location location, CharSequence pkg, CharSequence relativeName, Element... elements) {
            return resource;
        }

        @Override
        public JavaFileObject createSourceFile(CharSequence name, Element... elements) {
            throw new UnsupportedOperationException();
        }

        @Override
        public JavaFileObject createClassFile(CharSequence name, Element... elements) {
            throw new UnsupportedOperationException();
        }

        @Override
        public FileObject getResource(JavaFileManager.Location location, CharSequence pkg, CharSequence relativeName) {
            throw new UnsupportedOperationException();
        }
    }
}
