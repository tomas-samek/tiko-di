package io.tiko.processor;

import io.tiko.annotations.Component;
import io.tiko.annotations.Configuration;
import io.tiko.annotations.EventHandler;
import io.tiko.annotations.Produces;
import io.tiko.processor.collect.ComponentCollector;
import io.tiko.processor.collect.EventHandlerCollector;
import io.tiko.processor.collect.FactoryMethodCollector;
import io.tiko.processor.collect.MainModuleOnClasspath;
import io.tiko.processor.config.ConfigBinderGenerator;
import io.tiko.processor.config.ConfigBinderServicesWriter;
import io.tiko.processor.config.ConfigManifestWriter;
import io.tiko.processor.config.ConfigurationCollector;
import io.tiko.processor.config.ConfigurationModel;
import io.tiko.processor.config.ConfigurationValidator;
import io.tiko.processor.core.ProcessingEnv;
import io.tiko.processor.core.StackTraces;
import io.tiko.processor.generator.ComponentFactoryGenerator;
import io.tiko.processor.generator.ContainerGenerator;
import io.tiko.processor.generator.EventRegistryGenerator;
import io.tiko.processor.generator.ProxyGenerator;
import io.tiko.processor.model.ComponentModel;
import io.tiko.processor.model.FactoryMethodModel;
import io.tiko.processor.topology.ConfigSchemaWriter;
import io.tiko.processor.topology.TopologyWriter;
import io.tiko.processor.topology.WiringErrorsWriter;
import io.tiko.processor.util.ProcessorContext;
import io.tiko.processor.util.TypeUtil;
import io.tiko.processor.validation.AmbiguityValidator;
import io.tiko.processor.validation.CircularDependencyDetector;
import io.tiko.processor.validation.CloseableLeakValidator;
import io.tiko.processor.validation.ComponentShapeValidator;
import io.tiko.processor.validation.DependencyGraphValidator;
import io.tiko.processor.validation.ExposureValidator;
import io.tiko.processor.validation.KotlinConstructValidator;
import io.tiko.processor.validation.NullabilityValidator;
import io.tiko.processor.validation.PickValidator;
import io.tiko.processor.validation.PickerValidator;
import io.tiko.processor.validation.ProducesSignatureValidator;
import io.tiko.processor.validation.ScopeValidator;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;

/**
 * The DI processor's logic, independent of the compiler frontend: it collects the
 * {@code @Component}, {@code @Produces}, {@code @EventHandler} and {@code @Configuration}
 * declarations, validates the dependency graph, and generates the factories, proxies, event
 * registry, config binders and container, plus the {@code META-INF} descriptors and the
 * topology bundle.
 *
 * <p>One instance lives for the whole compilation and holds the cross-round state: the
 * {@link ProcessorContext} with everything collected so far, whether a round carried Tiko
 * annotations, and whether the output was generated. A frontend creates a fresh
 * {@link ProcessingEnv} for every round; the context then reports into the current one.
 *
 * <p>Lifecycle: {@link #process} for each round that carries at least one of the
 * {@link #ENTRY_POINT_ANNOTATIONS} (both frontends decide that with {@link #isEntryPointRound}),
 * {@link #finish} at least once at the end. {@code finish} is
 * the only place output is written, and it is idempotent: the first call validates and generates
 * whatever was collected (or warns when Tiko annotations were seen but nothing was collected),
 * every later call does nothing, and so does every call before any round carried Tiko
 * annotations. A {@code process} call after it collects nothing and reports the late sources as
 * an error, because the container is generated once per compilation.
 *
 * <p>Timing per frontend: javac calls {@code finish} in the same round as {@code process}, the
 * first round in which anything was collected (#334), and again (as a no-op) when processing is
 * over. Files written in javac's final round are not processed further and draw a "created in
 * the last round" warning, which fails {@code -Xlint:processing -Werror} builds. KSP calls
 * {@code finish} in the first round whose new files carry no entry point after a round that did,
 * so components another processor generates in a later round are still wired; when no such round
 * comes, it calls {@code finish} from {@code SymbolProcessor.finish()}, whose output KSP still
 * compiles, or from {@code SymbolProcessor.onError()}, which KSP calls instead after a round that
 * logged an error.
 */
public final class TikoPipeline {

    /** Processor option listing the active profiles, comma-separated ({@code -Atiko.profiles=dev,test}). */
    public static final String PROFILES_OPTION = "tiko.profiles";

    /** Processor option that suppresses the topology bundle when {@code false}. */
    public static final String TOPOLOGY_BUNDLE_OPTION = "tiko.topology.bundle";

    /** Every processor option the pipeline reads. */
    public static final Set<String> OPTIONS = Set.of(PROFILES_OPTION, TOPOLOGY_BUNDLE_OPTION);

    /** Annotations that make a round Tiko-relevant: they drive collection and the late-round guard. */
    public static final Set<String> ENTRY_POINT_ANNOTATIONS = Set.of(
            Component.class.getCanonicalName(),
            Produces.class.getCanonicalName(),
            EventHandler.class.getCanonicalName(),
            Configuration.class.getCanonicalName(),
            ComponentCollector.TEST_COMPONENT_FQN);

    /**
     * Whether a round is Tiko-relevant: it carries one of the {@link #ENTRY_POINT_ANNOTATIONS}.
     * The one rule both frontends use to decide which rounds go to {@link #process}; a round it
     * rejects is never collected, so annotations outside the set (such as {@code @Inject} or
     * {@code @Named}) on their own never start Tiko processing.
     *
     * @param roundCarries whether the round carries the annotation with this qualified name
     */
    public static boolean isEntryPointRound(Predicate<String> roundCarries) {
        return ENTRY_POINT_ANNOTATIONS.stream().anyMatch(roundCarries);
    }

    private final List<String> activeProfiles;
    private ProcessorContext context;
    private boolean sawTikoAnnotations;
    private boolean finished;
    private boolean failed;

    public TikoPipeline(List<String> activeProfiles) {
        this.activeProfiles = List.copyOf(activeProfiles);
    }

    /** The active profiles named by the {@link #PROFILES_OPTION} processor option; empty when unset. */
    public static List<String> parseActiveProfiles(Map<String, String> options) {
        String profilesOption = options.get(PROFILES_OPTION);
        if (profilesOption == null || profilesOption.isBlank()) {
            return List.of();
        }
        return Arrays.asList(profilesOption.split(","));
    }

    /**
     * Collects this round's declarations. Call it only for rounds that carry an entry-point
     * annotation; after {@link #finish} it reports the round's sources as arriving too late.
     */
    public void process(ProcessingEnv env) {
        enterRound(env);

        // After generation, later rounds only process OUR generated (annotation-free)
        // sources — unless another processor emits new Tiko-annotated sources, which the
        // single-shot container cannot wire anymore. Make that loud instead of silent.
        if (finished) {
            context.env()
                    .diagnostics()
                    .error(
                            null,
                            "Tiko DI: Tiko-annotated sources appeared in a later annotation-processing round"
                                    + " (generated by another processor?) after the container was generated."
                                    + " Tiko generates once per compilation — ensure all Tiko-annotated"
                                    + " sources are present in the original round.");
            return;
        }
        sawTikoAnnotations = true;

        context.env().diagnostics().note(null, "Tiko DI: Processing round, collecting annotations...");
        // Before collection: the collectors skip what it rejects, and assume it was ruled out
        // (a top-level @Produces has no declaring class to build a model from).
        new KotlinConstructValidator(context).validate(context.env());
        ComponentCollector.collect(context.env(), context);
        FactoryMethodCollector.collect(context.env(), context);
        EventHandlerCollector.collect(context.env(), context);
        new ConfigurationCollector(context).collect(context.env());

        context.env()
                .diagnostics()
                .note(
                        null,
                        "Tiko DI: Collected " + context.getComponents().size() + " components, "
                                + context.getFactoryMethods().size()
                                + " factories, "
                                + context.getEventHandlers().size()
                                + " event handlers");
    }

    /**
     * Whether anything was collected that generation can start from: a component, a factory
     * method or a configuration. Until then a later round may still bring Tiko-annotated sources
     * generated by other processors.
     */
    public boolean collectedAnything() {
        return sawTikoAnnotations
                && (!context.getComponents().isEmpty()
                        || !context.getFactoryMethods().isEmpty()
                        || !context.getConfigurations().isEmpty());
    }

    /** Validates and generates what was collected; see the class Javadoc for timing. */
    public void finish(ProcessingEnv env) {
        if (finished || !sawTikoAnnotations) return;
        finished = true;
        enterRound(env);
        if (!collectedAnything()) {
            // Tiko annotations showed up, but nothing that generation can start from.
            context.env()
                    .diagnostics()
                    .warning(null, "Tiko DI: No components, factories, or configurations found to process!");
            return;
        }
        failed = !validateAndGenerate();
    }

    /**
     * Whether {@link #finish} has run after a round carried Tiko annotations: it then wrote the
     * output, failed validation or generation (see {@link #failed}), or found nothing to generate
     * from and warned.
     */
    public boolean finished() {
        return finished;
    }

    /** Whether validation or generation failed; the errors are already reported. */
    public boolean failed() {
        return failed;
    }

    /**
     * Hands this round's env to the context, which is created in the first round and then
     * lives for the whole compilation: what one round collects can be generated in a later one.
     */
    private void enterRound(ProcessingEnv env) {
        if (context == null) {
            context = new ProcessorContext(env, activeProfiles);
        } else {
            context.enterRound(env);
        }
    }

    /** Validates and then generates; {@code false} when either failed. */
    private boolean validateAndGenerate() {
        context.env().diagnostics().note(null, "Tiko DI: Starting validation and code generation...");

        try {

            context.env()
                    .diagnostics()
                    .note(null, "Tiko DI: Found " + context.getComponents().size() + " components to process");

            // Stage 2: Validate
            context.env().diagnostics().note(null, "Tiko DI: Starting validation...");
            if (!validate()) {
                // The validators have already emitted specific, located errors that fail the
                // compile — no generic trailer (it only added a content-free second error).
                return false;
            }
            context.env().diagnostics().note(null, "Tiko DI: Validation passed");

            // In standalone test-compile, main @Component sources are absent from this round.
            // AmbiguityValidator's shadow detection requires both sides present, so it does not
            // fire when only the @TestComponent is visible. Bridge that gap by walking test
            // components whose testExtraKeys point at classpath-main FQNs and marking them as
            // shadows directly. Same-round detection still goes through AmbiguityValidator —
            // this only adds cross-round (peer-compile) shadow registration.
            markClasspathShadowedTestComponents();

            // Stage 3: Generate code
            context.env().diagnostics().note(null, "Tiko DI: Starting code generation...");
            generate();

            context.env()
                    .diagnostics()
                    .note(
                            null,
                            "Tiko DI: Successfully generated container with "
                                    + context.getComponents().size()
                                    + " components, "
                                    + context.getFactoryMethods().size()
                                    + " factories, "
                                    + context.getEventHandlers().size()
                                    + " event handlers");

        } catch (Exception e) {
            context.env().diagnostics().error(null, "Tiko DI processing failed:\n" + StackTraces.format(e));
            return false;
        } finally {
            // Always emit the wiring-errors sibling artifact, even when validation or
            // code generation aborted. Downstream tooling (tiko-mcp's list_wiring_errors)
            // relies on the file being present on every processed round so a clean build
            // ships {"errors": []} and a broken build ships the structured failures.
            emitWiringErrors();
        }

        return true;
    }

    /**
     * Emits {@code META-INF/tiko/wiring-errors.json} via {@link WiringErrorsWriter}.
     * Gated on {@code -Atiko.topology.bundle=true} (the same opt-out as {@code topology.json}
     * and {@code config-schema.json}). Wrapped in its own try/catch so an IOException
     * during writer creation cannot mask a real processing failure earlier in the round.
     */
    private void emitWiringErrors() {
        if (!topologyBundleEnabled()) return;
        try {
            new WiringErrorsWriter(context.getWiringErrors())
                    .write(context.env().output(), context.getOriginatingDeclarations());
        } catch (IOException e) {
            context.env().diagnostics().warning(null, "Tiko DI: failed to emit wiring-errors.json: " + e.getMessage());
        }
    }

    /**
     * Marks any {@code @TestComponent} whose implicit-walk or explicit-value keys point at
     * a main {@code @Component} FQN of the main module on the classpath (see
     * {@link MainModuleOnClasspath}). Bridges the gap left by
     * {@code AmbiguityValidator} in standalone test-compile rounds — main sources are not
     * present in the round, so the validator's shadow-pair detection (requires both sides
     * visible) cannot fire. Single-round compiles still rely on the validator path; this
     * helper only adds shadow entries the validator could not have seen.
     */
    private void markClasspathShadowedTestComponents() {
        Set<String> mainFqns = MainModuleOnClasspath.read(context.env()).componentFqns();
        if (mainFqns.isEmpty()) {
            return;
        }
        for (var component : context.getActiveComponents()) {
            if (!component.isTestComponent()) continue;
            for (String extraKey : component.getTestExtraKeys()) {
                if (mainFqns.contains(extraKey)) {
                    context.markShadowedByTestOverride(extraKey, component);
                }
            }
        }
    }

    /**
     * The checks that run first and short-circuit. Generated Java can't construct a component
     * that injects a Kotlin value class, and stopping here keeps its rejected provider from also
     * reading as a missing dependency. The validators after these assume well-shaped models
     * (#333): a raw Provider dependency, for one, NPEs the moment any of them reads its
     * dependency key.
     */
    private boolean wellShaped(TypeUtil typeUtil) {
        return new KotlinConstructValidator(context).validateInjectionPoints()
                && new ComponentShapeValidator(context, typeUtil).validate();
    }

    /**
     * Validates the dependency graph.
     */
    private boolean validate() {
        boolean valid = true;
        var typeUtil = new TypeUtil(context.env().types());

        if (!wellShaped(typeUtil)) {
            return false;
        }

        // Validate scope rules
        ScopeValidator scopeValidator = new ScopeValidator(context);
        for (ComponentModel component : context.getActiveComponents()) {
            if (!scopeValidator.validate(component)) {
                valid = false;
            }
        }
        for (FactoryMethodModel factory : context.getActiveFactoryMethods()) {
            if (!scopeValidator.validate(factory)) {
                valid = false;
            }
        }

        // Validate @Produces method signatures the code generator can realize (no void /
        // primitive / non-public / abstract). Catches these before codegen so the user gets
        // one clean error instead of a cascade of javac errors on generated source.
        ProducesSignatureValidator producesValidator = new ProducesSignatureValidator(context);
        if (!producesValidator.validate()) {
            valid = false;
        }

        // Validate @Component(expose = {...}) entries are actually implemented.
        ExposureValidator exposureValidator = new ExposureValidator(context, typeUtil);
        if (!exposureValidator.validate()) {
            valid = false;
        }

        // Validate dependencies exist
        DependencyGraphValidator graphValidator = new DependencyGraphValidator(context, typeUtil);
        if (!graphValidator.validate()) {
            valid = false;
        }

        // Provider and injection point must agree on nullability (Kotlin T?; Java is non-null)
        if (!new NullabilityValidator(context).validate()) {
            valid = false;
        }

        // Detect circular dependencies
        CircularDependencyDetector circularDetector = new CircularDependencyDetector(context);
        if (!circularDetector.validate()) {
            valid = false;
        }

        // Detect ambiguous unnamed providers (two+ @Components/@Produces for same type)
        AmbiguityValidator ambiguityValidator = new AmbiguityValidator(context);
        if (!ambiguityValidator.validate()) {
            valid = false;
        }

        // Validate Picker<T> injection points (warn-only — never fails the build, so no verdict)
        new PickerValidator(context).validate();

        // Validate @Pick usage on injection-point parameters
        PickValidator pickValidator = new PickValidator(context);
        if (!pickValidator.validate()) {
            valid = false;
        }

        // Validate @Configuration records
        ConfigurationValidator configValidator = new ConfigurationValidator(context);
        if (!configValidator.validate()) {
            valid = false;
        }

        // Closeable-field leak warning (warning only, never fails the build)
        new CloseableLeakValidator(context).validate();

        return valid;
    }

    /**
     * Generates all code.
     */
    private void generate() throws IOException {
        // Compute container class name first (needed by all generators)
        String containerClassName = computeContainerClassName();
        context.setContainerClassName(containerClassName);

        // Generate factory for each component
        ComponentFactoryGenerator factoryGenerator = new ComponentFactoryGenerator(context);
        for (ComponentModel component : context.getActiveComponents()) {
            context.env().diagnostics().note(null, "Tiko DI: Generating factory for " + component.getClassName());
            factoryGenerator.generate(component);
        }

        // Generate proxies for cross-scope injection
        ProxyGenerator proxyGenerator = new ProxyGenerator(context);
        for (ComponentModel component : context.getActiveComponents()) {
            if (component.requiresProxy()) {
                context.env().diagnostics().note(null, "Tiko DI: Generating proxy for " + component.getClassName());
            }
            proxyGenerator.generate(component);
        }
        // Factory outputs that are interfaces in shorter-lived scopes also need proxies so
        // longer-lived consumers (e.g., SINGLETON repo injecting an EVENT-scoped Connection)
        // resolve per-call to the current scope's value instead of capturing the first one.
        for (FactoryMethodModel factory : context.getActiveFactoryMethods()) {
            if (factory.requiresProxy()) {
                context.env()
                        .diagnostics()
                        .note(null, "Tiko DI: Generating proxy for " + factory.getFactoryIdentifier());
                proxyGenerator.generate(factory);
            }
        }

        // Generate event registry
        context.env().diagnostics().note(null, "Tiko DI: Generating event registry...");
        EventRegistryGenerator eventRegistryGenerator = new EventRegistryGenerator(context);
        eventRegistryGenerator.generate();

        // Generate per-record ConfigBinder classes
        ConfigBinderGenerator configBinderGen =
                new ConfigBinderGenerator(context.env().output());
        for (ConfigurationModel cfg : context.getConfigurations()) {
            if (configBinderGen.canGenerate(cfg)) {
                configBinderGen.generate(cfg);
            }
        }

        // List the binders as ConfigBinder services (#531) and write the configs.txt manifest
        List<ConfigurationModel> configs = context.getConfigurations();
        new ConfigBinderServicesWriter(context.env().output()).write(configs);
        new ConfigManifestWriter(context.env().output(), moduleRootOf(containerClassName)).write(configs);

        // Emit machine-readable topology.json for AI agents / IDE tooling / doc generators
        // when the build has anything worth describing. Gated to avoid empty-file noise.
        // Suppressed entirely when -Atiko.topology.bundle=false (closed-source services, sensitive jars).
        if (topologyBundleEnabled()
                && (!context.getActiveComponents().isEmpty()
                        || !context.getActiveFactoryMethods().isEmpty()
                        || !context.getEventHandlers().isEmpty()
                        || !context.getConfigurations().isEmpty())) {
            new TopologyWriter(context).write(context.env().output());
        }

        // Emit config-schema.json when @Configuration records are present
        if (topologyBundleEnabled() && !context.getConfigurations().isEmpty()) {
            new ConfigSchemaWriter(context).write(context.env().output());
        }

        // Generate container (must be last)
        context.env().diagnostics().note(null, "Tiko DI: Generating container...");
        ContainerGenerator containerGenerator = new ContainerGenerator(context);
        containerGenerator.generate();
    }

    /**
     * The per-module descriptor directory for a main container (#537); {@code null} for
     * test-compile output ({@code TestContainerImpl_}), which keeps only the fixed-name manifest.
     */
    private static String moduleRootOf(String containerClassName) {
        return containerClassName.startsWith("TikoContainerImpl_")
                ? ContainerGenerator.moduleRoot(containerClassName)
                : null;
    }

    /**
     * Returns true when the user has opted into topology emission (the default).
     * Set {@code -Atiko.topology.bundle=false} to suppress {@code topology.json} and
     * {@code config-schema.json} entirely (closed-source services, sensitive jars).
     */
    private boolean topologyBundleEnabled() {
        var v = context.env().options().get(TOPOLOGY_BUNDLE_OPTION);
        return v == null || !v.equalsIgnoreCase("false");
    }

    /**
     * Computes a unique container class name based on component keys.
     * This ensures different modules generate different container class names.
     *
     * <p>When the round is a test-compile happening on top of an existing main
     * container (detected via {@link MainModuleOnClasspath}), the emitted
     * container is a standalone test container that peers with the existing main
     * via {@code AggregatingContainer} at runtime — so its class name carries the
     * {@code TestContainerImpl_} prefix instead of {@code TikoContainerImpl_}. All
     * factories, proxies, and the event registry are then typed against this test
     * container, with no parallel main-container emission in the test-compile round.
     */
    private String computeContainerClassName() {
        // Create deterministic ID based on component names
        List<String> componentKeys = context.getActiveComponents().stream()
                .map(ComponentModel::getComponentKey)
                .sorted()
                .toList();

        int hash = Objects.hash(componentKeys.toArray());
        // Convert to positive hex string
        String suffix = Integer.toHexString(hash & 0x7FFFFFFF);

        String prefix = (context.hasTestComponents()
                        && MainModuleOnClasspath.read(context.env())
                                .containerFqn()
                                .isPresent())
                ? "TestContainerImpl_"
                : "TikoContainerImpl_";
        return prefix + suffix;
    }
}
