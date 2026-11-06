package io.tiko.processor.core.ksp;

import com.google.devtools.ksp.processing.CodeGenerator;
import com.google.devtools.ksp.processing.Dependencies;
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment;
import com.google.devtools.ksp.processing.SymbolProcessorProvider;
import com.google.devtools.ksp.symbol.KSClassDeclaration;
import com.google.devtools.ksp.symbol.KSFile;
import com.google.devtools.ksp.symbol.KSFunctionDeclaration;
import com.google.devtools.ksp.symbol.KSPropertyDeclaration;
import java.io.File;
import java.io.OutputStream;
import java.util.Collection;
import java.util.List;

/**
 * A KSP {@link CodeGenerator} that records every file request, with its {@link Dependencies},
 * before delegating to KSP's own generator. A test helper shared through the core test-jar.
 */
public final class RecordingCodeGenerator implements CodeGenerator {

    /**
     * One file request: the path as KSP builds it ({@code pkg/Name.java}, or the resource path),
     * whether it is aggregating, and the originating files' names in request order.
     */
    public record Request(String path, boolean aggregating, List<String> originatingFiles) {}

    private final CodeGenerator delegate;
    private final List<Request> requests;

    public RecordingCodeGenerator(CodeGenerator delegate, List<Request> requests) {
        this.delegate = delegate;
        this.requests = requests;
    }

    /** {@code provider}, with every file it asks KSP for recorded into {@code requests}. */
    public static SymbolProcessorProvider recording(SymbolProcessorProvider provider, List<Request> requests) {
        return environment -> provider.create(new SymbolProcessorEnvironment(
                environment.getOptions(),
                environment.getKotlinVersion(),
                new RecordingCodeGenerator(environment.getCodeGenerator(), requests),
                environment.getLogger(),
                environment.getApiVersion(),
                environment.getCompilerVersion(),
                environment.getPlatforms(),
                environment.getKspVersion()));
    }

    @Override
    public OutputStream createNewFile(
            Dependencies dependencies, String packageName, String fileName, String extensionName) {
        record(dependencies, packageName.replace('.', '/') + "/" + fileName + "." + extensionName);
        return delegate.createNewFile(dependencies, packageName, fileName, extensionName);
    }

    @Override
    public OutputStream createNewFileByPath(Dependencies dependencies, String path, String extensionName) {
        record(dependencies, extensionName.isEmpty() ? path : path + "." + extensionName);
        return delegate.createNewFileByPath(dependencies, path, extensionName);
    }

    private void record(Dependencies dependencies, String path) {
        var files = dependencies.getOriginatingFiles().stream()
                .map(KSFile::getFileName)
                .toList();
        requests.add(new Request(path, dependencies.getAggregating(), files));
    }

    @Override
    public void associate(List<? extends KSFile> sources, String packageName, String fileName, String extensionName) {
        delegate.associate(sources, packageName, fileName, extensionName);
    }

    @Override
    public void associateByPath(List<? extends KSFile> sources, String path, String extensionName) {
        delegate.associateByPath(sources, path, extensionName);
    }

    @Override
    public void associateWithClasses(
            List<? extends KSClassDeclaration> classes, String packageName, String fileName, String extensionName) {
        delegate.associateWithClasses(classes, packageName, fileName, extensionName);
    }

    @Override
    public void associateWithFunctions(
            List<? extends KSFunctionDeclaration> functions,
            String packageName,
            String fileName,
            String extensionName) {
        delegate.associateWithFunctions(functions, packageName, fileName, extensionName);
    }

    @Override
    public void associateWithProperties(
            List<? extends KSPropertyDeclaration> properties,
            String packageName,
            String fileName,
            String extensionName) {
        delegate.associateWithProperties(properties, packageName, fileName, extensionName);
    }

    @Override
    public Collection<File> getGeneratedFile() {
        return delegate.getGeneratedFile();
    }
}
