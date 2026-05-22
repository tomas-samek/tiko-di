package io.tiko.examples.testing.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;

import io.tiko.Container;
import io.tiko.test.TikoTest;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

@TikoTest(lifecycle = TikoTest.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PerClassLifecycleTest {

    private static Container firstContainer;

    @Test
    @Order(1)
    void firstTestRemembersContainerIdentity(Container c) {
        firstContainer = c;
        assertThat(c).isNotNull();
    }

    @Test
    @Order(2)
    void secondTestSeesSameContainer(Container c) {
        assertThat(c).isSameAs(firstContainer);
    }
}
