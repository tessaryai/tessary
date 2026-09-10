// SPDX-License-Identifier: Apache-2.0
package ai.tessary.open.errors;

import org.springframework.http.HttpStatus;

public enum GitError implements ErrorCode {
    INTEGRATION_NOT_FOUND(HttpStatus.NOT_FOUND, "No git integration for project '%s'"),
    GITHUB_REQUIRED(
            HttpStatus.CONFLICT,
            "Grader generation needs the project's repo: connect the GitHub App to project '%s' first"),
    DUPLICATE_INTEGRATION(HttpStatus.CONFLICT, "Project '%s' is already bound to a repo"),
    UNSUPPORTED_PROVIDER(HttpStatus.BAD_REQUEST, "Unsupported git provider '%s'"),
    INVALID_SIGNATURE(HttpStatus.UNAUTHORIZED, "Webhook signature verification failed"),
    INSTALLATION_NOT_FOUND(HttpStatus.BAD_REQUEST, "No installation '%s' on record"),
    TOKEN_MINT_FAILED(HttpStatus.BAD_GATEWAY, "Could not mint a %s access token"),
    PROVIDER_CALL_FAILED(HttpStatus.BAD_GATEWAY, "%s API call failed: %s"),
    MISSING_APP_CONFIG(HttpStatus.FAILED_DEPENDENCY, "Git provider %s is not configured on this server"),
    /**
     * The deployment has no {@code TESSARY_SECRET_KEY}, so no credential can be sealed at rest.
     * Distinct from {@link #MISSING_APP_CONFIG}: a personal access token needs no App, and telling
     * someone to configure an App is the one instruction that cannot fix this.
     */
    SECRET_KEY_MISSING(
            HttpStatus.FAILED_DEPENDENCY,
            "This deployment has no encryption key configured, so Tessary cannot store an access token."
                    + " Set TESSARY_SECRET_KEY on the deployment and restart it"),
    CREDENTIALS_REJECTED(
            HttpStatus.BAD_REQUEST, "%s rejected these credentials. Check the token has not expired or been revoked"),
    /**
     * GitHub answers 404 both for a repo that does not exist and for a private one the credential
     * cannot see, and does not say which. The message has to carry both readings rather than assert
     * the wrong one.
     */
    REPO_UNREACHABLE(
            HttpStatus.BAD_REQUEST,
            "No repository at %s that these credentials can read. Check the owner and repository name,"
                    + " and that the token grants Contents: read on it"),
    REPO_ACCESS_DENIED(
            HttpStatus.BAD_REQUEST,
            "These credentials cannot read %s. Grant the token Contents: read on this repository,"
                    + " or enter a repository it already covers"),
    INSTALL_STATE_INVALID(HttpStatus.BAD_REQUEST, "GitHub install state is invalid or expired"),
    NO_INSTALLED_REPOS(HttpStatus.BAD_REQUEST, "The GitHub App installation grants no repositories"),
    NO_ADMIN_INSTALLATIONS(
            HttpStatus.BAD_REQUEST,
            "No GitHub App installation found for the authorizing user — install the Tessary GitHub App on your account or org first, then connect"),
    INSTALL_NOT_VERIFIED(
            HttpStatus.BAD_REQUEST,
            "GitHub install could not be verified — enable 'Request user authorization (OAuth) during installation' on the App and configure its client id/secret"),
    INSTALL_FORBIDDEN(HttpStatus.FORBIDDEN, "This GitHub installation is not one the authorizing user can administer"),
    MANIFEST_CONVERSION_FAILED(
            HttpStatus.BAD_GATEWAY,
            "Could not complete GitHub App registration — the manifest code may have already been used or expired"),
    APP_ALREADY_CONFIGURED(
            HttpStatus.CONFLICT,
            "A GitHub App is already configured on this deployment — remove the existing github_app_config row"
                    + " before running the manifest wizard again, since it mints one shared App for every org");

    private final HttpStatus status;
    private final String template;

    GitError(HttpStatus status, String template) {
        this.status = status;
        this.template = template;
    }

    @Override
    public HttpStatus status() {
        return status;
    }

    @Override
    public String template() {
        return template;
    }

    @Override
    public Class<? extends Enum<?>> declaringClass() {
        return GitError.class;
    }
}
