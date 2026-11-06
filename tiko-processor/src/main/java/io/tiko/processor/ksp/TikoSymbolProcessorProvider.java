package io.tiko.processor.ksp;

import com.google.devtools.ksp.processing.Resolver;
import com.google.devtools.ksp.processing.SymbolProcessor;
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment;
import com.google.devtools.ksp.processing.SymbolProcessorProvider;
import com.google.devtools.ksp.symbol.KSAnnotated;
import io.tiko.processor.TikoPipeline;
import io.tiko.processor.core.ksp.KspProcessingEnv;
import java.util.List;

/**
 * The KSP2 entry point of the Tiko DI processor, registered as a {@code SymbolProcessorProvider}
 * service next to the javac {@code Processor} one. javac never loads it, so the processor jar
 * needs no KSP classes on javac.
 *
 * <p>A thin frontend shell: each round it wraps KSP in a {@link KspProcessingEnv} and hands it to
 * the {@link TikoPipeline}, which collects, validates and generates. It only decides which rounds
 * to hand over and when to finish: in the first round whose new files carry no entry point after
 * a round that did, else in {@link SymbolProcessor#finish()}. Waiting for such a round lets a
 * component another processor generates in a later round join the container.
 */
public final class TikoSymbolProcessorProvider implements SymbolProcessorProvider {

    @Override
    public SymbolProcessor create(SymbolProcessorEnvironment environment) {
        return new TikoSymbolProcessor(environment);
    }

    private static final class TikoSymbolProcessor implements SymbolProcessor {

        private final SymbolProcessorEnvironment environment;
        private final TikoPipeline pipeline;
        private KspProcessingEnv env;
        private boolean sawEntryPoints;

        TikoSymbolProcessor(SymbolProcessorEnvironment environment) {
            this.environment = environment;
            this.pipeline = new TikoPipeline(TikoPipeline.parseActiveProfiles(environment.getOptions()));
        }

        @Override
        public List<KSAnnotated> process(Resolver resolver) {
            env = env == null
                    ? KspProcessingEnv.of(
                            resolver, environment.getCodeGenerator(), environment.getLogger(), environment.getOptions())
                    : env.nextRound(resolver);
            if (TikoPipeline.isEntryPointRound(name ->
                    resolver.getSymbolsWithAnnotation(name, false).iterator().hasNext())) {
                pipeline.process(env);
                sawEntryPoints = true;
            } else if (sawEntryPoints) {
                pipeline.finish(env);
            }
            return List.of();
        }

        /** No round without entry points came after the last one that had them: finish now. */
        @Override
        public void finish() {
            if (env != null) {
                pipeline.finish(env);
            }
        }
    }
}
