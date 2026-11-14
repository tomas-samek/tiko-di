package io.tiko.examples.basic.crossscope;

import static org.assertj.core.api.Assertions.assertThat;

import io.tiko.Container;
import io.tiko.runtime.Tiko;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * #725: a SINGLETON {@code @Produces} value that takes an EVENT-scoped {@code @Produces} value sees the
 * current unit's instance in every unit, not the instance of the unit that first resolved it.
 */
class SingletonProducesEventParameterTest {

    @Test
    void singletonProducedValueReadsTheCurrentUnitsSessionInEveryUnit() {
        List<Integer> seenThroughTheSingleton = new ArrayList<>();
        List<Integer> currentInTheUnit = new ArrayList<>();

        try (Container container = Tiko.create()) {
            for (int unit = 0; unit < 3; unit++) {
                container.runInEventScope(() -> {
                    seenThroughTheSingleton.add(
                            container.get(SessionReporter.class).currentSessionNumber());
                    currentInTheUnit.add(container.get(UnitSession.class).number());
                });
            }
        }

        assertThat(currentInTheUnit)
                .as("each unit of work has its own session")
                .doesNotHaveDuplicates()
                .hasSize(3);
        assertThat(seenThroughTheSingleton)
                .as("the SINGLETON reads the session of the unit that calls it")
                .isEqualTo(currentInTheUnit);
    }
}
