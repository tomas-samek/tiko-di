package io.tiko;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;

/** #685: the injectable interface opens units of work and gives nothing else of the container away. */
class UnitOfWorkTest {

    @Test
    void containerIsAUnitOfWork() {
        assertThat(UnitOfWork.class).isAssignableFrom(Container.class);
    }

    @Test
    void unitOfWorkOpensUnitsAndNothingElse() {
        assertThat(UnitOfWork.class.getMethods())
                .extracting(Method::getName)
                .containsExactlyInAnyOrder("runInEventScope", "supplyInEventScope");
    }
}
