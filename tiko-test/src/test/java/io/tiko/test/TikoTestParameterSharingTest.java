package io.tiko.test;

import static org.assertj.core.api.Assertions.assertThat;

import io.tiko.NoActiveEventScopeException;
import io.tiko.test.fixtures.EventScopedService;
import io.tiko.test.fixtures.SimpleService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolutionException;
import org.junit.jupiter.api.extension.ParameterResolver;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.testkit.engine.EngineTestKit;

/**
 * #758: {@code @TikoTest} claims only the parameters its container can provide. Arguments of a
 * {@code @ParameterizedTest} come from JUnit, and a parameter another registered extension resolves is
 * left to that extension; claiming them too made JUnit fail every invocation with "Discovered multiple
 * competing ParameterResolvers".
 */
@TikoTest
@ExtendWith(TikoTestParameterSharingTest.TicketResolver.class)
class TikoTestParameterSharingTest {

    /** A type the container does not provide; only {@link TicketResolver} does. */
    record Ticket(String id) {}

    static final class TicketResolver implements ParameterResolver {
        @Override
        public boolean supportsParameter(ParameterContext parameter, ExtensionContext context) {
            return parameter.getParameter().getType() == Ticket.class;
        }

        @Override
        public Object resolveParameter(ParameterContext parameter, ExtensionContext context) {
            return new Ticket("from-the-other-extension");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"x", "y"})
    void parameterizedTestReceivesItsArgumentFromJUnit(String value) {
        assertThat(value).isIn("x", "y");
    }

    @ParameterizedTest
    @ValueSource(strings = {"x", "y"})
    void parameterizedTestReceivesItsOtherParametersFromTheContainer(String value, SimpleService service) {
        assertThat(value).isIn("x", "y");
        assertThat(service).isNotNull();
    }

    @Test
    void parameterOfAnotherExtensionIsLeftToIt(Ticket ticket, SimpleService service) {
        assertThat(ticket.id()).isEqualTo("from-the-other-extension");
        assertThat(service).isNotNull();
    }

    /** The configuration parameter that enables the failing fixtures; set only by {@link #failureOf}. */
    static final String FIXTURES = "tiko.test.parameter-fixtures";

    static boolean launchedByThisTest(ExtensionContext context) {
        return context.getConfigurationParameter(FIXTURES).isPresent();
    }

    private static Throwable failureOf(Class<?> fixture) {
        var failed = EngineTestKit.engine("junit-jupiter")
                .configurationParameter(FIXTURES, "true")
                .selectors(DiscoverySelectors.selectClass(fixture))
                .execute()
                .testEvents()
                .failed();
        assertThat(failed.count()).isEqualTo(1);
        return failed.stream()
                .findFirst()
                .orElseThrow()
                .getPayload(org.junit.platform.engine.TestExecutionResult.class)
                .orElseThrow()
                .getThrowable()
                .orElseThrow();
    }

    @Test
    void parameterTheContainerKnowsButCannotResolveNowKeepsTheContainersError() {
        assertThat(failureOf(EventScopedParameterFixture.class))
                .as("the container's own explanation, not JUnit's 'No ParameterResolver registered'")
                .hasRootCauseInstanceOf(NoActiveEventScopeException.class);
    }

    @Test
    void parameterNobodyProvidesIsReportedByJUnit() {
        assertThat(failureOf(UnknownParameterFixture.class))
                .isInstanceOf(ParameterResolutionException.class)
                .hasMessageContaining("No ParameterResolver registered")
                .hasMessageContaining("Ticket");
    }

    @TikoTest
    @EnabledIf("io.tiko.test.TikoTestParameterSharingTest#launchedByThisTest")
    static class EventScopedParameterFixture {
        @Test
        void takesAnEventScopedBeanOutsideAUnitOfWork(EventScopedService service) {
            assertThat(service).isNotNull();
        }
    }

    @TikoTest
    @EnabledIf("io.tiko.test.TikoTestParameterSharingTest#launchedByThisTest")
    static class UnknownParameterFixture {
        @Test
        void takesATypeNobodyProvides(Ticket ticket) {
            assertThat(ticket).isNotNull();
        }
    }
}
