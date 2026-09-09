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
 * <p><b>Why this test exists.</b> #842 took {@code SopIntakeService} to {@code tessary-paid/sop} while
 * both of its callers stayed open — {@code ImportController}, behind the live {@code /import} route,
 * and {@code BundleImportService}, on the observer's auto-import critical path. Had either taken
 * {@link SopIntake} as a plain required constructor parameter, the open edition would have compiled,
 * passed the enforcer, passed {@code check-open-boundary.sh} and every ArchUnit rule, and then refused
 * to START: a required parameter with no candidate bean is an UNSATISFIED dependency in Spring, not a
 * null. That is the boot-failure-with-green-gates shape tessary-paid/OPEN-CORE.md records for {@code SopCompiler},
 * and this is the third time this epic has hit it. {@link SopIntakeDispatch} is the answer, and this
 * pins it.
 *
 * <p><b>The return value is the contract, not just the absence of a crash.</b> Zero is what the paid
 * implementation already returns for an org without {@code SOP_CONFORMANCE}, at its first line. Both
 * callers ignore the count and neither branches on it, so an edition with no SOP intake and an org that
 * has not bought it produce the identical {@code /import} response — the same
 * absence-equals-withholding rule {@code FitReportSource} states for the conformance surface.
 *
 * <p>The second assertion is the one that would otherwise rot. {@code /import} is a PER-REQUEST path,
 * not a heartbeat, so an unlatched line about a permanent steady state is one egressed OPS log per push
 * for the life of the deployment — the shape {@code backend/AGENTS.md} names under "log OUTCOMES and
 * COST, not intent". Plain JUnit: no Spring, no database, and it runs on a machine that cannot finish
 * the Testcontainers suite.
 */
class AbsentSopIntakeTest {

    private static final List<NamedBody> ONE_SOP =
            List.of(new NamedBody(".tessary/sops/support_agent.yaml", "agent: support::reply\n"));

    /**
     * An empty {@code ObjectProvider} — the open edition's own state, since #842 took the only
     * implementation to the overlay. Non-null because the dispatcher resolves the provider eagerly in
     * its constructor rather than storing it.
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
