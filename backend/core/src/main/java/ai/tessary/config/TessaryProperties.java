// SPDX-License-Identifier: Apache-2.0
package ai.tessary.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Application config from {@code application.yaml} or {@code TESSARY_}-prefixed env vars. A blank
 * {@code TESSARY_JDBC_URL} fails fast (see {@code DataSourceConfig}).
 */
@Component
@ConfigurationProperties(prefix = "tessary")
public class TessaryProperties {

    private String jdbcUrl = "";

    private String dbUsername = "";
    private String dbPassword = "";

    /** Base64 of a 32-byte AES-GCM key. */
    private String secretKey = "";

    /**
     * Bound from the bare {@code SITE_DOMAIN}, the name compose, {@code .env.example} and the Caddyfile share. Blank is
     * load-bearing: it keeps {@code PlaceholderSecretGuard} inert in every non-compose boot, tests included.
     */
    private String siteDomain = "";

    /**
     * {@code acme}, {@code owncert} or {@code upstream} (Caddy serves plain HTTP behind the operator's terminator).
     * Bound from the bare {@code TLS_MODE}; validated by {@code PublicOriginGuard}.
     */
    private String tlsMode = "acme";

    /** Bound from the bare {@code ACME_EMAIL}; required in {@code acme} mode with a domain. */
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
