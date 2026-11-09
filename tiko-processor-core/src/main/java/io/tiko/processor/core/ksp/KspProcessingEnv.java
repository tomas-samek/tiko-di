package io.tiko.processor.core.ksp;

import com.google.devtools.ksp.UtilsKt;
import com.google.devtools.ksp.processing.CodeGenerator;
import com.google.devtools.ksp.processing.KSPLogger;
import com.google.devtools.ksp.processing.Resolver;
import com.google.devtools.ksp.symbol.FileLocation;
import com.google.devtools.ksp.symbol.KSClassDeclaration;
import com.google.devtools.ksp.symbol.KSFunctionDeclaration;
import com.google.devtools.ksp.symbol.KSNode;
import com.google.devtools.ksp.symbol.KSPropertyAccessor;
import com.google.devtools.ksp.symbol.KSPropertySetter;
import com.google.devtools.ksp.symbol.Origin;
import io.tiko.processor.core.ClasspathProbe;
import io.tiko.processor.core.CodeOutput;
import io.tiko.processor.core.Diagnostics;
import io.tiko.processor.core.ProcessingEnv;
import io.tiko.processor.core.SourceVersionInfo;
import io.tiko.processor.core.TypeOracle;
import io.tiko.processor.core.model.ClassDecl;
import io.tiko.processor.core.model.MethodDecl;
import io.tiko.processor.core.model.PropertyAccessorDecl;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import kotlin.sequences.SequencesKt;

/**
 * The KSP2 {@link ProcessingEnv}: one round of a {@code SymbolProcessor}.
 *
 * <p>The first round's env comes from {@link #of}, every later one from {@link #nextRound} on the
 * previous env. The rounds share a session, and that is what keeps the model usable across them:
 * KSP2 symbols die with their round (once a round has generated files, touching a symbol from
 * before it throws {@code KaInvalidLifetimeOwnerAccessException}, "PSI has changed since
 * creation"), so every model object finds its symbol again through the current round's resolver
 * the first time it is read in a later round. Model objects from different rounds stay equal when
 * they denote the same declaration, as the model contract requires.
 *
 * <p>{@link #classpath()} never finds anything: classpath probing is not supported on KSP yet.
 * {@link #sourceVersion()} is the running JDK's release, as KSP does not expose a Java one.
 */
public final class KspProcessingEnv implements ProcessingEnv {

    private final Resolver resolver;
    private final KspModel model;
    private final CodeGenerator codeGenerator;
    private final KSPLogger logger;
    private final Map<String, String> options;
    private final Diagnostics diagnostics;
    private final CodeOutput output;
    private final TypeOracle types;

    private KspProcessingEnv(
            Resolver resolver,
            KspModel model,
            CodeGenerator codeGenerator,
            KSPLogger logger,
            Map<String, String> options) {
        this.resolver = resolver;
        this.model = model;
        this.codeGenerator = codeGenerator;
        this.logger = logger;
        this.options = Map.copyOf(options);
        this.diagnostics = new KspDiagnostics(logger);
        this.output = new KspCodeOutput(codeGenerator);
        this.types = new KspTypeOracle(model);
    }

    /** Wraps the first round of a compilation; later rounds come from {@link #nextRound}. */
    public static KspProcessingEnv of(
            Resolver resolver, CodeGenerator codeGenerator, KSPLogger logger, Map<String, String> options) {
        return new KspProcessingEnv(resolver, new KspModel(new KspSession(resolver)), codeGenerator, logger, options);
    }

    /**
     * Wraps the next round of the same compilation. Model objects made through earlier rounds' envs
     * now resolve through {@code next}; do not use the earlier envs any more.
     */
    public KspProcessingEnv nextRound(Resolver next) {
        model.session().enterRound(next);
        return new KspProcessingEnv(next, model, codeGenerator, logger, options);
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
        return new ClasspathProbe() {
            @Override
            public Optional<String> readResource(String path) {
                return Optional.empty();
            }

            @Override
            public List<ClassDecl> compiledClassesIn(String packageName) {
                return KspProcessingEnv.this.compiledClassesIn(packageName);
            }
        };
    }

    /**
     * Library classes of the package, from class files: {@code getDeclarationsFromPackage} is
     * {@code @KspExperimental}, which only Kotlin callers must opt in to.
     */
    private List<ClassDecl> compiledClassesIn(String packageName) {
        return SequencesKt.toList(resolver.getDeclarationsFromPackage(packageName)).stream()
                .filter(KSClassDeclaration.class::isInstance)
                .map(KSClassDeclaration.class::cast)
                .filter(declaration ->
                        declaration.getOrigin() == Origin.KOTLIN_LIB || declaration.getOrigin() == Origin.JAVA_LIB)
                .map(model::classDecl)
                .sorted(Comparator.comparing(ClassDecl::qualifiedName))
                .toList();
    }

    @Override
    public Map<String, String> options() {
        return options;
    }

    @Override
    public SourceVersionInfo sourceVersion() {
        return new SourceVersionInfo(Runtime.version().feature());
    }

    /** Classes in this round's new files (and deferred symbols) carrying the annotation, in source order. */
    @Override
    public List<ClassDecl> classesAnnotatedWith(String annotationFqn) {
        return SequencesKt.toList(resolver.getSymbolsWithAnnotation(annotationFqn, false)).stream()
                .filter(KSClassDeclaration.class::isInstance)
                .map(KSClassDeclaration.class::cast)
                .map(model::classDecl)
                .toList();
    }

    /** Functions (not constructors) carrying the annotation, top-level functions included, in source order. */
    @Override
    public List<MethodDecl> methodsAnnotatedWith(String annotationFqn) {
        return SequencesKt.toList(resolver.getSymbolsWithAnnotation(annotationFqn, false)).stream()
                .filter(KSFunctionDeclaration.class::isInstance)
                .map(KSFunctionDeclaration.class::cast)
                .filter(function -> !UtilsKt.isConstructor(function))
                .map(model::methodDecl)
                .toList();
    }

    /** Getters and setters carrying the annotation through {@code @get:} / {@code @set:}, in source order. */
    @Override
    public List<PropertyAccessorDecl> propertyAccessorsAnnotatedWith(String annotationFqn) {
        return SequencesKt.toList(resolver.getSymbolsWithAnnotation(annotationFqn, false)).stream()
                .filter(KSPropertyAccessor.class::isInstance)
                .map(KSPropertyAccessor.class::cast)
                .map(this::accessorDecl)
                .toList();
    }

    /**
     * The accessor at its own position, or at its property's when it is not written out (a default
     * setter has no source position). DI reports on it in the round that finds it, so its location
     * is not found again in later rounds.
     */
    private PropertyAccessorDecl accessorDecl(KSPropertyAccessor accessor) {
        var property = accessor.getReceiver();
        KSNode node = accessor.getLocation() instanceof FileLocation ? accessor : property;
        var enclosingClass = property.getParentDeclaration() instanceof KSClassDeclaration owner
                ? model.classDecl(owner).qualifiedName()
                : null;
        return new PropertyAccessorDecl(
                property.getSimpleName().asString(),
                accessor instanceof KSPropertySetter,
                new KspLocation(model.ref(node, later -> null), enclosingClass));
    }
}
