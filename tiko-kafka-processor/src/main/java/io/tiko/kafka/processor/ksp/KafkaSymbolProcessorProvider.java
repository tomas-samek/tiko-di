package io.tiko.kafka.processor.ksp;

import com.google.devtools.ksp.processing.Resolver;
import com.google.devtools.ksp.processing.SymbolProcessor;
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment;
import com.google.devtools.ksp.processing.SymbolProcessorProvider;
import com.google.devtools.ksp.symbol.KSAnnotated;
import io.tiko.kafka.annotations.KafkaSink;
import io.tiko.kafka.annotations.KafkaSource;
import io.tiko.kafka.processor.KafkaPipeline;
import io.tiko.processor.core.ksp.KspProcessingEnv;
import java.util.List;

/**
 * The KSP2 entry point of the Kafka processor, registered as a {@code SymbolProcessorProvider}
 * service next to the javac {@code Processor} one. javac never loads it, so the processor jar
 * needs no KSP classes on javac.
 *
 * <p>A thin frontend shell: each round it wraps KSP in a {@link KspProcessingEnv} and hands it to
 * the {@link KafkaPipeline}. It only decides which rounds to hand over (those whose new files
 * carry a bridge) and when to finish: in the first round without bridges after one that had them,
 * else in {@link SymbolProcessor#finish()}.
 */
public final class KafkaSymbolProcessorProvider implements SymbolProcessorProvider {

    private static final List<String> BRIDGES =
            List.of(KafkaSource.class.getCanonicalName(), KafkaSink.class.getCanonicalName());

    @Override
    public SymbolProcessor create(SymbolProcessorEnvironment environment) {
        return new KafkaSymbolProcessor(environment);
    }

    private static final class KafkaSymbolProcessor implements SymbolProcessor {

        private final SymbolProcessorEnvironment environment;
        private final KafkaPipeline pipeline = new KafkaPipeline();
        private KspProcessingEnv env;
        private boolean sawBridges;

        KafkaSymbolProcessor(SymbolProcessorEnvironment environment) {
            this.environment = environment;
        }

        @Override
        public List<KSAnnotated> process(Resolver resolver) {
            env = env == null
                    ? KspProcessingEnv.of(
                            resolver, environment.getCodeGenerator(), environment.getLogger(), environment.getOptions())
                    : env.nextRound(resolver);
            if (BRIDGES.stream()
                    .anyMatch(name -> resolver.getSymbolsWithAnnotation(name, false)
                            .iterator()
                            .hasNext())) {
                pipeline.process(env);
                sawBridges = true;
            } else if (sawBridges) {
                pipeline.finish(env);
            }
            return List.of();
        }

        @Override
        public void finish() {
            if (sawBridges) {
                pipeline.finish(env);
            }
        }
    }
}
