// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.when;

import ai.tessary.llm.ModelProvider;
import ai.tessary.llm.ProviderCredentialSavedEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ProviderCredentialListenerTest {

    @Mock
    ClassifierService classifiers;

    /**
     * An unpause that fails stays in the listener. It runs after the provider key has been saved, and an
     * exception here would answer the save with an error for a key that was stored.
     */
    @Test
    void aFailedUnpauseDoesNotFailTheSavedKey() {
        when(classifiers.unpauseForProvider("org_1", ModelProvider.OPENAI))
                .thenThrow(new IllegalStateException("database unavailable"));

        assertDoesNotThrow(() -> new ProviderCredentialListener(classifiers)
                .onProviderCredentialSaved(new ProviderCredentialSavedEvent("org_1", ModelProvider.OPENAI)));
    }
}
