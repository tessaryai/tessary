// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.substrate;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.doThrow;

import ai.tessary.classifier.ClassifierService;
import ai.tessary.pipeline.CallSiteFact;
import ai.tessary.pipeline.CallSiteFactChangedEvent;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CallSiteFactListenerTest {

    @Mock
    ClassifierService signals;

    /**
     * A rewind that fails stays in the listener. It runs after the code-fact write has committed, and an
     * exception here would reach that write's caller as a failure of a change that already landed.
     */
    @Test
    void aFailedRewindDoesNotReachTheWriteThatPublishedIt() {
        CallSiteFactChangedEvent event =
                new CallSiteFactChangedEvent("proj_1", CallSiteFact.OUTPUT_SCHEMA, Set.of("cs-1"));
        doThrow(new IllegalStateException("database unavailable"))
                .when(signals)
                .rewindForCallSiteFact("proj_1", CallSiteFact.OUTPUT_SCHEMA, Set.of("cs-1"));

        assertDoesNotThrow(() -> new CallSiteFactListener(signals).onCallSiteFactChanged(event));
    }
}
