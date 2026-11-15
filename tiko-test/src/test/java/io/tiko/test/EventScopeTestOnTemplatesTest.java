package io.tiko.test;

import static org.assertj.core.api.Assertions.assertThat;

import io.tiko.Container;
import io.tiko.test.fixtures.EventScopedService;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * #764: {@code @EventScopeTest} opens a unit of work for every kind of test method it can annotate, not
 * only for {@code @Test}: repeated and parameterized tests, and each dynamic test of a
 * {@code @TestFactory}.
 */
@TikoTest
class EventScopeTestOnTemplatesTest {

    @RepeatedTest(2)
    @EventScopeTest
    void repeatedTestRunsInsideAUnitOfWork(Container container) {
        assertThat(container.get(EventScopedService.class)).isNotNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"x", "y"})
    @EventScopeTest
    void parameterizedTestRunsInsideAUnitOfWork(String value, Container container) {
        assertThat(container.get(EventScopedService.class)).isNotNull();
    }

    @TestFactory
    @EventScopeTest
    Stream<DynamicTest> eachDynamicTestRunsInsideItsOwnUnitOfWork(Container container) {
        return Stream.of("first", "second")
                .map(name -> DynamicTest.dynamicTest(
                        name,
                        () -> assertThat(container.get(EventScopedService.class))
                                .isNotNull()));
    }
}
