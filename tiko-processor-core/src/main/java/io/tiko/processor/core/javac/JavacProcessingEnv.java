package io.tiko.processor.core.javac;

import io.tiko.processor.core.ClasspathProbe;
import io.tiko.processor.core.CodeOutput;
import io.tiko.processor.core.Diagnostics;
import io.tiko.processor.core.ProcessingEnv;
import io.tiko.processor.core.SourceVersionInfo;
import io.tiko.processor.core.TypeOracle;
import io.tiko.processor.core.model.ClassDecl;
import io.tiko.processor.core.model.MethodDecl;
import io.tiko.processor.core.model.PropertyAccessorDecl;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.element.Element;
import javax.lang.model.util.ElementFilter;

/** The javac {@link ProcessingEnv}: one processing round of a javac annotation processor. */
public final class JavacProcessingEnv implements ProcessingEnv {

    private final ProcessingEnvironment env;
    private final RoundEnvironment round;
    private final JavacModel model;
    private final Diagnostics diagnostics;
    private final CodeOutput output;
    private final TypeOracle types;
    private final ClasspathProbe classpath;

    private JavacProcessingEnv(ProcessingEnvironment env, RoundEnvironment round) {
        this.env = env;
        this.round = round;
        this.model = new JavacModel(env.getElementUtils(), env.getTypeUtils());
        this.diagnostics = new JavacDiagnostics(env.getMessager());
        this.output = new JavacCodeOutput(env.getFiler());
        this.types = new JavacTypeOracle(model);
        this.classpath = new JavacClasspathProbe(env.getFiler());
    }

    /** Wraps one round; create a new instance for every round. */
    public static JavacProcessingEnv of(ProcessingEnvironment env, RoundEnvironment round) {
        return new JavacProcessingEnv(env, round);
    }

    @Override
    public Diagnostics diagnostics() {
        return diagnostics;
    }

    @Override
    public CodeOutput output() {
        return output;
    }

    @Override
    public TypeOracle types() {
        return types;
    }

    @Override
    public ClasspathProbe classpath() {
        return classpath;
    }

    @Override
    public Map<String, String> options() {
        return env.getOptions();
    }

    /** {@code SourceVersion} constants are {@code RELEASE_0..RELEASE_n}, so the ordinal is the release. */
    @Override
    public SourceVersionInfo sourceVersion() {
        return new SourceVersionInfo(env.getSourceVersion().ordinal());
    }

    @Override
    public List<ClassDecl> classesAnnotatedWith(String annotationFqn) {
        return ElementFilter.typesIn(annotatedWith(annotationFqn)).stream()
                .map(model::classDecl)
                .toList();
    }

    @Override
    public List<MethodDecl> methodsAnnotatedWith(String annotationFqn) {
        return ElementFilter.methodsIn(annotatedWith(annotationFqn)).stream()
                .map(model::methodDecl)
                .toList();
    }

    /** Always empty: Java has no property accessors. */
    @Override
    public List<PropertyAccessorDecl> propertyAccessorsAnnotatedWith(String annotationFqn) {
        return List.of();
    }

    private Set<? extends Element> annotatedWith(String annotationFqn) {
        var annotation = model.elements().getTypeElement(annotationFqn);
        return annotation == null ? Set.of() : round.getElementsAnnotatedWith(annotation);
    }
}
