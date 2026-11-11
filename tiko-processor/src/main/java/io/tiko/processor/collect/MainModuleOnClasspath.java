package io.tiko.processor.collect;

import io.tiko.processor.core.ClasspathProbe;
import io.tiko.processor.core.ProcessingEnv;
import io.tiko.processor.core.model.ClassDecl;
import io.tiko.processor.core.model.Visibility;
import java.io.IOException;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;

/**
 * The main module a test compile builds against, as the compile classpath shows it: its
 * container and its component classes. Both are empty when no main module is on the classpath,
 * as in a main compile.
 */
public final class MainModuleOnClasspath {

    private static final String CONTAINER_DESCRIPTOR = "META-INF/tiko/container.properties";
    private static final String COMPONENTS_MANIFEST = "META-INF/tiko/components.txt";
    private static final String MODULES_ROOT = "META-INF/tiko/modules/";
    private static final String GENERATED_PACKAGE = "io.tiko.generated";
    private static final String MODULE_PREFIX = "TikoModule_";
    private static final String CONTAINER_PREFIX = "TikoContainerImpl_";

    /** {@code null} when no main module is on the classpath. */
    private final String containerFqn;

    private final Set<String> componentFqns;

    private MainModuleOnClasspath(String containerFqn, Set<String> componentFqns) {
        this.containerFqn = containerFqn;
        this.componentFqns = componentFqns;
    }

    /**
     * Reads the main module off the compile classpath of {@code env}'s round. The main compile's
     * {@code META-INF/tiko} resources are the source when the classpath has them (javac). Otherwise
     * the classes it generated into {@code io.tiko.generated} are (KSP, which cannot read
     * classpath resources): its container is named after its {@code TikoModule_<hash>}, and its
     * components are what its factories create.
     */
    public static MainModuleOnClasspath read(ProcessingEnv env) {
        var classpath = env.classpath();
        var modules = readModules(classpath);
        var descriptor = classpath.readResource(CONTAINER_DESCRIPTOR);
        var manifest = classpath.readResource(COMPONENTS_MANIFEST);
        if (!modules.componentFqns().isEmpty() || descriptor.isPresent() || manifest.isPresent()) {
            var components = new LinkedHashSet<>(modules.componentFqns());
            manifest.map(MainModuleOnClasspath::manifestLines).ifPresent(components::addAll);
            var container = modules.containerFqn != null
                    ? modules.containerFqn
                    : descriptor
                            .map(MainModuleOnClasspath::implProperty)
                            .filter(fqn -> !fqn.isBlank())
                            .orElse(null);
            return new MainModuleOnClasspath(container, components);
        }
        return fromGeneratedClasses(classpath.compiledClassesIn(GENERATED_PACKAGE));
    }

    /**
     * Every main module's own descriptors, found through its {@code TikoModule_<hash>} class: the
     * fixed-name resources hold only the first module's on a classpath that has several. The
     * container is the first module's. Empty for modules compiled before the per-module
     * descriptors, which the fixed-name resources still cover.
     */
    private static MainModuleOnClasspath readModules(ClasspathProbe classpath) {
        String container = null;
        var components = new LinkedHashSet<String>();
        for (var name : classpath.simpleClassNamesIn(GENERATED_PACKAGE)) {
            if (!name.startsWith(MODULE_PREFIX)) {
                continue;
            }
            var root = MODULES_ROOT + CONTAINER_PREFIX + name.substring(MODULE_PREFIX.length()) + "/";
            var moduleContainer = classpath
                    .readResource(root + "container.properties")
                    .map(MainModuleOnClasspath::implProperty)
                    .filter(fqn -> !fqn.isBlank());
            if (container == null) {
                container = moduleContainer.orElse(null);
            }
            classpath
                    .readResource(root + "components.txt")
                    .map(MainModuleOnClasspath::manifestLines)
                    .ifPresent(components::addAll);
        }
        return new MainModuleOnClasspath(container, components);
    }

    /**
     * The main module as its generated classes show it. With several main modules on the classpath
     * the container is the first module's, and the components are every module's.
     */
    private static MainModuleOnClasspath fromGeneratedClasses(List<ClassDecl> generated) {
        var container = generated.stream()
                .map(decl -> decl.className().simpleName())
                .filter(name -> name.startsWith(MODULE_PREFIX))
                .findFirst()
                .map(name -> GENERATED_PACKAGE + "." + CONTAINER_PREFIX + name.substring(MODULE_PREFIX.length()))
                .orElse(null);
        var components = new LinkedHashSet<String>();
        for (var decl : generated) {
            createdByMainFactory(decl).ifPresent(components::add);
        }
        return new MainModuleOnClasspath(container, components);
    }

    /**
     * The binary name of the class a main container's component factory creates; empty for any
     * other generated class. A factory takes its container in its public constructor, so the
     * factories of a standalone test container ({@code TestContainerImpl_}) are left out.
     */
    private static Optional<String> createdByMainFactory(ClassDecl decl) {
        if (!decl.className().simpleName().endsWith("Factory") || !takesMainContainer(decl)) {
            return Optional.empty();
        }
        return decl.methods().stream()
                .filter(method ->
                        method.name().equals("create") && method.parameters().isEmpty())
                .findFirst()
                .flatMap(create -> create.returnType().declaration())
                .map(created -> created.className().reflectionName());
    }

    private static boolean takesMainContainer(ClassDecl decl) {
        return decl.constructors().stream()
                .filter(constructor -> constructor.visibility() == Visibility.PUBLIC)
                .anyMatch(constructor -> constructor.parameters().size() == 1
                        && constructor
                                .parameters()
                                .get(0)
                                .type()
                                .erasedName()
                                .startsWith(GENERATED_PACKAGE + "." + CONTAINER_PREFIX));
    }

    /** The main container's fully qualified name; empty when no main module is on the classpath. */
    public Optional<String> containerFqn() {
        return Optional.ofNullable(containerFqn);
    }

    /** The fully qualified names of the main module's component classes. */
    public Set<String> componentFqns() {
        return componentFqns;
    }

    /** The {@code impl} entry of a container descriptor; {@code null} when it has none. */
    private static String implProperty(String descriptor) {
        var props = new Properties();
        try {
            props.load(new StringReader(descriptor));
        } catch (IOException e) {
            // A StringReader never fails; an IOException here is unreachable.
            throw new UncheckedIOException(e);
        }
        return props.getProperty("impl");
    }

    /** The manifest's entries: one class name per line, blank lines and {@code #} comments skipped. */
    private static Set<String> manifestLines(String text) {
        var fqns = new LinkedHashSet<String>();
        text.lines()
                .map(String::trim)
                .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                .forEach(fqns::add);
        return fqns;
    }
}
