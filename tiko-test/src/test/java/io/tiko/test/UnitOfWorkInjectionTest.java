package io.tiko.test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.tiko.Container;
import io.tiko.test.fixtures.UnitCloseLog;
import io.tiko.test.fixtures.UnitProbe;
import io.tiko.test.fixtures.UnitRunner;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Behavioral end-to-end for #685: a {@code @Component} that takes the built-in {@code UnitOfWork}
 * opens one unit of work per call, with the contract of {@code Container.runInEventScope}.
 */
@TikoTest
class UnitOfWorkInjectionTest {

    @Test
    void eachCallOpensItsOwnUnit(Container c) {
        var runner = c.get(UnitRunner.class);

        List<String> first = runner.inUnit(probe -> List.of(probe.id(), probe.id()));
        String second = runner.inUnit(UnitProbe::id);

        assertThat(first.get(0)).as("one unit, one instance").isEqualTo(first.get(1));
        assertThat(second).as("another call, another unit").isNotEqualTo(first.get(0));
    }

    @Test
    void aUnitInsideAUnitIsRejected(Container c) {
        var runner = c.get(UnitRunner.class);

        String[] ids = runner.inUnit(probe -> {
            String before = probe.id();
            assertThatThrownBy(() -> runner.runInUnit(() -> {}))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("already open");
            return new String[] {before, probe.id()};
        });

        assertThat(ids[1]).as("the open unit is still usable").isEqualTo(ids[0]);
    }

    @Test
    void theUnitClosesWhenTheCallReturns(Container c) {
        var runner = c.get(UnitRunner.class);
        var log = c.get(UnitCloseLog.class);

        String id = runner.inUnit(probe -> {
            String mine = probe.id();
            assertThat(log.closed()).as("still open inside the call").doesNotContain(mine);
            return mine;
        });

        assertThat(log.closed()).containsExactly(id);
    }

    @Test
    void theUnitClosesWhenTheWorkThrows(Container c) {
        var runner = c.get(UnitRunner.class);
        var log = c.get(UnitCloseLog.class);
        var seen = new String[1];

        assertThatThrownBy(() -> runner.inUnit(probe -> {
                    seen[0] = probe.id();
                    throw new IllegalArgumentException("work failed");
                }))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("work failed");

        assertThat(log.closed()).containsExactly(seen[0]);
    }
}
