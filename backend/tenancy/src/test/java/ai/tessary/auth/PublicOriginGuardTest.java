// SPDX-License-Identifier: Apache-2.0
package ai.tessary.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.tessary.config.TessaryProperties;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class PublicOriginGuardTest {

    private TessaryProperties tessary = new TessaryProperties();
    private AuthProperties auth = new AuthProperties();
    private WorkOsProperties workos = new WorkOsProperties();
    private final MockEnvironment env = new MockEnvironment();

    private PublicOriginGuard guard(String domain, String tlsMode, String acmeEmail) {
        tessary = new TessaryProperties();
        tessary.setSiteDomain(domain);
        tessary.setTlsMode(tlsMode);
        tessary.setAcmeEmail(acmeEmail);
        auth = new AuthProperties();
        workos = new WorkOsProperties();
        return new PublicOriginGuard(tessary, auth, workos, env);
    }

    @Test
    void blankDomainIsInert() {
        PublicOriginGuard g = guard("", "acme", "");
        String before = auth.getFrontendUrl();
        g.verify();
        assertEquals(before, auth.getFrontendUrl());
    }

    @Test
    void domainAloneDerivesEveryOrigin() {
        PublicOriginGuard g = guard("Tessary.Acme-Corp.com", "acme", "ops@acme-corp.com");
        auth.setFrontendUrl("http://127.0.0.1:80/");
        workos.setRedirectUri("http://[::1]:8000/auth/callback");
        g.verify();
        assertEquals("tessary.acme-corp.com", tessary.getSiteDomain());
        assertEquals("https://tessary.acme-corp.com/", auth.getFrontendUrl());
        assertEquals("https://tessary.acme-corp.com/auth/callback", workos.getRedirectUri());
    }

    @Test
    void schemePrefixedDomainIsRejectedNotConcatenated() {
        PublicOriginGuard g = guard("https://tessary.acme-corp.com", "acme", "ops@acme-corp.com");
        IllegalStateException e = assertThrows(IllegalStateException.class, g::verify);
        assertTrue(String.valueOf(e.getMessage()).contains("SITE_DOMAIN must be a bare hostname"), e.getMessage());
        assertThrows(
                IllegalStateException.class,
                () -> guard("acme-corp.com:8443", "acme", "x@y.z").verify());
        assertThrows(
                IllegalStateException.class,
                () -> guard("acme-corp.com/app", "acme", "x@y.z").verify());
    }

    @Test
    void explicitOriginOnAnotherHostRefusesNamingBothKeys() {
        PublicOriginGuard g = guard("tessary.acme-corp.com", "acme", "ops@acme-corp.com");
        auth.setFrontendUrl("https://other.example/");
        IllegalStateException e = assertThrows(IllegalStateException.class, g::verify);
        assertTrue(
                String.valueOf(e.getMessage()).contains("SITE_DOMAIN")
                        && String.valueOf(e.getMessage()).contains("TESSARY_AUTH_FRONTEND_URL"),
                e.getMessage());
    }

    @Test
    void explicitOriginOnTheSameHostIsKept() {
        PublicOriginGuard g = guard("tessary.acme-corp.com", "owncert", "");
        auth.setFrontendUrl("https://tessary.acme-corp.com/app/");
        g.verify();
        assertEquals("https://tessary.acme-corp.com/app/", auth.getFrontendUrl());
    }

    @Test
    void acmeNeedsAnEmailButTheOtherModesDoNot() {
        IllegalStateException e = assertThrows(
                IllegalStateException.class,
                () -> guard("tessary.acme-corp.com", "acme", " ").verify());
        assertTrue(String.valueOf(e.getMessage()).contains("ACME_EMAIL"), e.getMessage());
        guard("tessary.acme-corp.com", "owncert", "").verify();
        guard("tessary.acme-corp.com", "upstream", "").verify();
        assertThrows(
                IllegalStateException.class,
                () -> guard("tessary.acme-corp.com", "cloudflare", "").verify());
    }

    @Test
    void workosRedirectOnAnotherHostRefusesOnlyWhenWorkosIsConfigured() {
        PublicOriginGuard off = guard("tessary.acme-corp.com", "upstream", "");
        workos.setRedirectUri("https://other.example/auth/callback");
        off.verify();
        assertEquals("https://other.example/auth/callback", workos.getRedirectUri());

        PublicOriginGuard on = guard("tessary.acme-corp.com", "upstream", "");
        workos.setApiKey("sk_test_placeholder");
        workos.setClientId("client_test_placeholder");
        workos.setRedirectUri("https://other.example/auth/callback");
        IllegalStateException e = assertThrows(IllegalStateException.class, on::verify);
        assertTrue(String.valueOf(e.getMessage()).contains("WORKOS_REDIRECT_URI"), e.getMessage());
    }

    @Test
    void agenticCallbackOriginOnAnotherHostRefuses() {
        PublicOriginGuard g = guard("tessary.acme-corp.com", "upstream", "");
        env.setProperty("tessary.rca.agentic.mcp-base-url", "http://localhost:8080");
        IllegalStateException e = assertThrows(IllegalStateException.class, g::verify);
        assertTrue(String.valueOf(e.getMessage()).contains("TESSARY_RCA_AGENTIC_MCP_BASE_URL"), e.getMessage());
        env.setProperty("tessary.rca.agentic.mcp-base-url", "https://tessary.acme-corp.com");
        env.setProperty("tessary.classifier.triage-mcp-base-url", "https://tessary.acme-corp.com");
        guard("tessary.acme-corp.com", "upstream", "").verify();
    }

    @Test
    void schemeOnlyAgenticOriginRefusesEvenWithNoSiteDomain() {
        // The exact value compose used to build for an install that set no SITE_DOMAIN: not blank,
        // so every blank-check downstream passed it, and both agent lanes were handed a URL with no
        // host to connect to. It has to fail HERE, under no domain, which is the only state it
        // occurs in.
        PublicOriginGuard g = guard("", "acme", "");
        env.setProperty("tessary.rca.agentic.mcp-base-url", "https://");
        IllegalStateException e = assertThrows(IllegalStateException.class, g::verify);
        assertTrue(String.valueOf(e.getMessage()).contains("TESSARY_RCA_AGENTIC_MCP_BASE_URL"), e.getMessage());
    }

    @Test
    void blankAgenticOriginStaysLegal() {
        // An install running no agentic lane sets neither variable; the engines already refuse a
        // blank one at run time. Only the half-built value above is a boot fault.
        env.setProperty("tessary.rca.agentic.mcp-base-url", "");
        env.setProperty("tessary.classifier.triage-mcp-base-url", "");
        guard("", "acme", "").verify();
    }

    @Test
    void internalServiceOriginIsAcceptedAlongsideASiteDomain() {
        // The compose default. Agent containers share the backend's network unless an operator opts
        // into SANDBOX_NETWORK_ISOLATION, so the internal address stays correct on a domain'd
        // install too — it is not a competing public origin and must not be read as one.
        env.setProperty("tessary.rca.agentic.mcp-base-url", "http://backend:8080");
        env.setProperty("tessary.classifier.triage-mcp-base-url", "http://backend:8080");
        guard("tessary.acme-corp.com", "upstream", "").verify();
    }
}
