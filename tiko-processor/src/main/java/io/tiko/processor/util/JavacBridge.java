package io.tiko.processor.util;

import io.tiko.processor.core.ProcessingEnv;
import io.tiko.processor.core.javac.JavacProcessingEnv;
import io.tiko.processor.core.model.Location;
import javax.annotation.processing.Filer;
import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.element.Element;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;

/**
 * javac access for code whose models still hold javac elements and types.
 *
 * <p>Interim bridge for the tiko-processor port; removed in K1 Task 7.
 */
public final class JavacBridge {

    private JavacBridge() {}

    /**
     * The round's javac {@link Elements}.
     *
     * <p>Interim bridge for the tiko-processor port; removed in K1 Task 7.
     */
    public static Elements elements(ProcessingEnv env) {
        return javac(env).getElementUtils();
    }

    /**
     * The round's javac {@link Types}.
     *
     * <p>Interim bridge for the tiko-processor port; removed in K1 Task 7.
     */
    public static Types types(ProcessingEnv env) {
        return javac(env).getTypeUtils();
    }

    /**
     * The round's javac {@link Filer}, for the container descriptors {@code ContainerGenerator}
     * still writes itself.
     *
     * <p>Interim bridge for the tiko-processor port; removed in K1 Task 7.
     */
    public static Filer filer(ProcessingEnv env) {
        return javac(env).getFiler();
    }

    /**
     * The {@link Location} of a javac element, for reporting through {@link ErrorReporter};
     * {@code null} for a {@code null} element, which reports without a source position as
     * the {@code Messager} did.
     *
     * <p>Interim bridge for the tiko-processor port; removed in K1 Task 7.
     */
    public static Location location(Element element) {
        return element == null ? null : JavacProcessingEnv.location(element);
    }

    private static ProcessingEnvironment javac(ProcessingEnv env) {
        if (env instanceof JavacProcessingEnv javac) {
            return javac.processingEnvironment();
        }
        throw new IllegalArgumentException("Not a javac processing environment: " + env);
    }
}
