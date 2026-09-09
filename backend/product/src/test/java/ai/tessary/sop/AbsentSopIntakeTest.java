// SPDX-License-Identifier: Apache-2.0
package ai.tessary.sop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ai.tessary.pipeline.BundleAssembler.NamedBody;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;

/**
 * With no {@link SopIntake} on the classpath, a bundle import stores nothing and says so once.
 *
 * <p>Neither {@code ImportController} nor {@code BundleImportService} may take {@link SopIntake} as a
 * plain required constructor parameter: a required parameter with no candidate bean is an unsatisfied
 * dependency in Spring, and the app refuses to start. {@link SopIntakeDispatch} is the seam that
 * avoids that, and this test pins its behavior.
 *
 * <p>Zero is the contract, not just the absence of a crash: both callers ignore the count and neither
 * branches on it, so an absent intake and a withheld capability produce the identical {@code /import}
 * response.
 *
 * <p>The second assertion is the one that would otherwise rot. {@code /import} is a per-request path,
 * not a heartbeat, so an unlatched line about a permanent steady state is one egressed log per push for
 * the life of the deployment. Plain JUnit: no Spring, no database.
 */
class AbsentSopIntakeTest {

    private static final List<NamedBody> ONE_SOP =
            List.of(new NamedBody(".tessary/sops/support_agent.yaml", "agent: support::reply\n"));

    /**
     * An empty {@code ObjectProvider}, non-null because the dispatcher resolves the provider eagerly
     * in its constructor rather than storing it.
     */
    @SuppressWarnings("unchecked")
    private static ObjectProvider<SopIntake> noIntake() {
        ObjectProvider<SopIntake> provider = mock(ObjectProvider.class);
        when(provider.orderedStream()).thenReturn(Stream.empty());
        return provider;
    }

    @Test
    @DisplayName("an absent intake stores nothing and answers 0, exactly as a withheld org does")
    void an_absent_intake_stores_nothing() {
        SopIntakeDispatch dispatch = new SopIntakeDispatch(noIntake());

        assertEquals(
                0,
                dispatch.importSops("proj-1", ONE_SOP, "c1"),
                "0 is what the paid implementation returns for an org without SOP_CONFORMANCE, so "
                        + "/import cannot be used to tell the two apart");
    }

    @Test
    @DisplayName("the no-intake line is latched to one per process, not one per import")
    void the_absent_intake_line_is_said_once() {
        SopIntakeDispatch dispatch = new SopIntakeDispatch(noIntake());
        Logger dispatchLog = (Logger) LoggerFactory.getLogger(SopIntakeDispatch.class);
        ListAppender<ILoggingEvent> events = new ListAppender<>();
        events.start();
        dispatchLog.addAppender(events);
        try {
            dispatch.importSops("proj-1", ONE_SOP, "c1");
            dispatch.importSops("proj-1", ONE_SOP, "c2");
            dispatch.importSops("proj-2", ONE_SOP, "c3");
        } finally {
            dispatchLog.detachAppender(events);
            events.stop();
        }

        assertEquals(
                1,
                events.list.stream()
                        .filter(e -> e.getLevel() == Level.INFO)
                        .filter(e -> e.getFormattedMessage().contains("no SopIntake on the classpath"))
                        .count(),
                "three imports in the open edition's permanent no-intake state must emit ONE line, "
                        + "not one per request");
    }
}
