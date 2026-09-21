// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.frustration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.tessary.classifier.ClassifierPause;
import ai.tessary.classifier.ClassifierRepository;
import ai.tessary.classifier.ClassifierRow;
import ai.tessary.classifier.ClassifierService;
import ai.tessary.crypto.SecretBox;
import ai.tessary.llm.ModelProvider;
import ai.tessary.llm.ProjectModelSettings;
import ai.tessary.llm.ProviderCredential;
import ai.tessary.llm.ProviderCredentialRepository;
import ai.tessary.llm.ProviderCredentialSavedEvent;
import ai.tessary.llmspi.ModelLane;
import ai.tessary.llmspi.ServiceTier;
import ai.tessary.open.errors.ClassifierError;
import ai.tessary.open.errors.TessaryException;
import ai.tessary.plan.Capability;
import ai.tessary.tenant.Ids;
import ai.tessary.tenant.TenantService;
import ai.tessary.testsupport.CapabilityFixture;
import ai.tessary.testsupport.StubEncoderScorerConfig;
import ai.tessary.testsupport.TenantFixture;
import ai.tessary.testsupport.TurnGrainTestDetectionConfig;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Import;

/**
 * Enabling Frustration spends the org's own provider credit, so it needs a key first; a pause names why
 * the classifier stopped, and either an enable or saving the key it was waiting on lifts it.
 *
 * <p>Shares the turn-grain fingerprint with the other frustration integration tests.
 */
@SpringBootTest
@Import({StubEncoderScorerConfig.class, TurnGrainTestDetectionConfig.class})
class FrustrationEnableIntegrationTest {

    @Autowired
    ClassifierService classifierService;

    @Autowired
    ClassifierRepository classifiers;

    @Autowired
    TenantService tenants;

    @Autowired
    CapabilityFixture capabilities;

    @Autowired
    ProviderCredentialRepository credentials;

    @Autowired
    ProjectModelSettings modelSettings;

    @Autowired
    SecretBox secretBox;

    @Autowired
    ApplicationEventPublisher events;

    @Test
    void enablingWithoutAProviderKeyIsRefused() {
        TenantFixture.Setup t = tenant("fr-enable-refused");
        ClassifierRow signal = frustration(t);
        classifierService.setEnabled(t.project().id(), signal.id(), false);

        TessaryException e = assertThrows(
                TessaryException.class,
                () -> classifierService.setEnabled(t.project().id(), signal.id(), true));

        assertEquals(ClassifierError.PROVIDER_REQUIRED, e.error());
        assertFalse(classifiers
                .findByKey(t.project().id(), "frustration")
                .orElseThrow()
                .enabled());
    }

    @Test
    void enablingWithATypeSafeKeySucceedsAndClearsAPause() {
        TenantFixture.Setup t = tenant("fr-enable-ok");
        String pid = t.project().id();
        ClassifierRow signal = frustration(t);
        classifierService.setEnabled(pid, signal.id(), false);
        storeKey(t, ModelProvider.TYPESAFE);
        modelSettings.set(pid, t.org().id(), ModelLane.FRUSTRATION, "TYPESAFE:jev-latest", ServiceTier.STANDARD, null);
        classifiers.pause(pid, signal.id(), ClassifierPause.PROVIDER_REJECTED, Instant.now());

        ClassifierRow enabled = classifierService.setEnabled(pid, signal.id(), true);

        assertTrue(enabled.enabled());
        assertTrue(classifiers.findPause(pid, signal.id()).isEmpty(), "an enable is a retry");
        assertNull(classifierService.readiness(pid, enabled));
    }

    @Test
    void readinessNamesThePauseOnAnEnabledFrustration() {
        TenantFixture.Setup t = tenant("fr-readiness");
        String pid = t.project().id();
        ClassifierRow signal = frustration(t);
        classifiers.pause(pid, signal.id(), ClassifierPause.NO_PROVIDER, Instant.now());

        ClassifierRow row = classifiers.findByKey(pid, "frustration").orElseThrow();
        assertEquals(ClassifierPause.NO_PROVIDER, classifierService.readiness(pid, row));
    }

    @Test
    void savingTheKeyTheLaneRunsOnLiftsThePauseAndAnotherProviderDoesNot() {
        TenantFixture.Setup t = tenant("fr-unpause");
        String pid = t.project().id();
        ClassifierRow signal = frustration(t);
        storeKey(t, ModelProvider.TYPESAFE);
        storeKey(t, ModelProvider.OPENROUTER);
        modelSettings.set(pid, t.org().id(), ModelLane.FRUSTRATION, "TYPESAFE:jev-latest", ServiceTier.STANDARD, null);
        classifiers.pause(pid, signal.id(), ClassifierPause.PROVIDER_REJECTED, Instant.now());

        events.publishEvent(new ProviderCredentialSavedEvent(t.org().id(), ModelProvider.OPENROUTER));
        assertTrue(classifiers.findPause(pid, signal.id()).isPresent(), "the lane runs on TypeSafe, not OpenRouter");

        events.publishEvent(new ProviderCredentialSavedEvent(t.org().id(), ModelProvider.TYPESAFE));
        assertTrue(classifiers.findPause(pid, signal.id()).isEmpty());
    }

    private TenantFixture.Setup tenant(String slug) {
        return TenantFixture.bootstrap(tenants, slug, org -> capabilities.grant(org.id(), Capability.FRUSTRATION));
    }

    private ClassifierRow frustration(TenantFixture.Setup t) {
        classifierService.seedBuiltIns(t.project().id());
        return classifiers.findByKey(t.project().id(), "frustration").orElseThrow();
    }

    private void storeKey(TenantFixture.Setup t, ModelProvider provider) {
        String now = Instant.now().toString();
        credentials.insert(new ProviderCredential(
                Ids.ulid(),
                t.org().id(),
                null,
                provider,
                null,
                secretBox.seal("test-key"),
                null,
                null,
                null,
                null,
                null,
                ProviderCredential.AUTH_MODE_API_KEY,
                now,
                now));
    }
}
