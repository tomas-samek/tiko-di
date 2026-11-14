package io.tiko.test;

import static org.assertj.core.api.Assertions.assertThat;

import io.tiko.Container;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * #760: the lifecycle stated on the class that carries {@code @TikoTest} applies to its subclasses and
 * to its {@code @Nested} classes. Before, both silently fell back to a container per test.
 */
class InheritedLifecycleTest {

    @TikoTest(lifecycle = TikoTest.Lifecycle.PER_CLASS)
    abstract static class PerClassBase {}

    @Nested
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    class SubclassOfAPerClassBase extends PerClassBase {

        private static Container first;

        @Test
        @Order(1)
        void firstTestRemembersItsContainer(Container container) {
            first = container;
            assertThat(container).isNotNull();
        }

        @Test
        @Order(2)
        void secondTestSeesTheSameContainer(Container container) {
            assertThat(container).isSameAs(first);
        }
    }

    @Nested
    @TikoTest(lifecycle = TikoTest.Lifecycle.PER_CLASS)
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    class PerClassOuter {

        private static Container outer;

        @Test
        @Order(1)
        void outerTestRemembersItsContainer(Container container) {
            outer = container;
            assertThat(container).isNotNull();
        }

        @Nested
        @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
        class NestedInAPerClassOuter {

            private static Container first;

            @Test
            @Order(1)
            void firstNestedTestRemembersItsContainer(Container container) {
                first = container;
                assertThat(container)
                        .as("a @Nested class is a test class of its own and boots its own container")
                        .isNotSameAs(outer);
            }

            @Test
            @Order(2)
            void secondNestedTestSeesTheSameContainer(Container container) {
                assertThat(container).isSameAs(first);
            }
        }
    }

    @Nested
    @TikoTest(lifecycle = TikoTest.Lifecycle.PER_CLASS)
    class OuterWithANestedClassThatStatesItsOwnLifecycle {

        @Nested
        @TikoTest
        @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
        class PerMethodNested {

            private static Container first;

            @Test
            @Order(1)
            void firstTestRemembersItsContainer(Container container) {
                first = container;
                assertThat(container).isNotNull();
            }

            @Test
            @Order(2)
            void secondTestGetsANewContainer(Container container) {
                assertThat(container)
                        .as("the lifecycle stated on the nested class wins over the enclosing one")
                        .isNotSameAs(first);
            }
        }
    }
}
