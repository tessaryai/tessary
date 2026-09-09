// SPDX-License-Identifier: Apache-2.0
package ai.tessary.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Application config bound from {@code application.yaml} or env vars prefixed
 * with {@code TESSARY_} (Spring relaxed binding).
 *
 * <p>Pipeline content lives in Postgres; the DB is the runtime source of truth.</p>
 *
 * <p>Database connection (required): {@code TESSARY_JDBC_URL} + {@code TESSARY_DB_USERNAME}
 * + {@code TESSARY_DB_PASSWORD}. Set by docker-compose in dev/prod and injected from the
 * pgvector Testcontainers instance in tests; a blank URL fails fast (see
 * {@code DataSourceConfig}).</p>
 */
@Component
@ConfigurationProperties(prefix = "tessary")
public class TessaryProperties {

    /** JDBC URL for external Postgres. Unset → embedded fallback (dev/test only). */
    private String jdbcUrl = "";

    private String dbUsername = "";
    private String dbPassword = "";

    /** Base64 of a 32-byte AES-GCM key used to seal ingestion-source credentials. */
    private String secretKey = "";

    /**
     * The public hostname this instance is served on, or blank when it is only reachable on
     * localhost. Bound from the bare {@code SITE_DOMAIN} env var rather than {@code
     * TESSARY_SITE_DOMAIN} (see {@code application.yaml}) because that is the one name the compose
     * file, {@code .env.example} and the Caddyfile already share; a second spelling would be a
     * second thing to keep in sync.
     *
     * <p>Blank is the safe default and is load-bearing: it is what makes {@code
     * PlaceholderSecretGuard} inert in every non-compose boot, including every {@code
     * @SpringBootTest}. See that class, and {@code AgenticRcaEngine#validateSandboxConfig}'s javadoc
     * for the failure this shape avoids.
     */
    private String siteDomain = "";

    /**
     * How the frontend container terminates TLS once {@link #siteDomain} is set: {@code acme}
     * (Caddy provisions a Let's Encrypt certificate), {@code owncert} (an operator-mounted
     * certificate and key), or {@code upstream} (a TLS terminator the operator already runs sits in
     * front, and Caddy serves plain HTTP behind it). Bound from the bare {@code TLS_MODE}, the name
     * the compose file and the frontend image share. Validated by {@code PublicOriginGuard}.
     */
    private String tlsMode = "acme";

    /** The Let's Encrypt account address, bound from the bare {@code ACME_EMAIL}; required in {@code acme} mode with a domain. */
    private String acmeEmail = "";

    public String getJdbcUrl() {
        return jdbcUrl;
    }

    public void setJdbcUrl(String jdbcUrl) {
        this.jdbcUrl = jdbcUrl;
    }

    public String getDbUsername() {
        return dbUsername;
    }

    public void setDbUsername(String dbUsername) {
        this.dbUsername = dbUsername;
    }

    public String getDbPassword() {
        return dbPassword;
    }

    public void setDbPassword(String dbPassword) {
        this.dbPassword = dbPassword;
    }

    public String getSecretKey() {
        return secretKey;
    }

    public void setSecretKey(String secretKey) {
        this.secretKey = secretKey;
    }

    public String getSiteDomain() {
        return siteDomain;
    }

    public void setSiteDomain(String siteDomain) {
        this.siteDomain = siteDomain;
    }

    public String getTlsMode() {
        return tlsMode;
    }

    public void setTlsMode(String tlsMode) {
        this.tlsMode = tlsMode;
    }

    public String getAcmeEmail() {
        return acmeEmail;
    }

    public void setAcmeEmail(String acmeEmail) {
        this.acmeEmail = acmeEmail;
    }
}
