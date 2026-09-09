// SPDX-License-Identifier: Apache-2.0
package ai.tessary.auth;

import ai.tessary.open.errors.AuthError;
import ai.tessary.open.errors.TessaryException;
import ai.tessary.tenant.AuditLog;
import ai.tessary.tenant.AuditLogRepository;
import ai.tessary.tenant.Ids;
import ai.tessary.tenant.InvitationRepository;
import ai.tessary.tenant.Organization;
import ai.tessary.tenant.OrganizationRepository;
import ai.tessary.tenant.PrincipalRepository;
import ai.tessary.tenant.SignupPolicy;
import ai.tessary.tenant.TenantService;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Enforces the {@link SignupPolicy} at the one moment it applies: after a provider has
 * authenticated someone and before a principal exists for them (#1226). The policy gates account
 * creation only. An existing principal is never refused, the first account is always admitted so a
 * fresh install cannot lock itself out, and a pending invitation admits in every mode.
 */
@Service
public class SignupPolicyService {

    private static final Logger log = LoggerFactory.getLogger(SignupPolicyService.class);
    private static final String SUBJECT_ORGANIZATION = "organization";
    private static final String ACTION = "signup_policy_updated";

    private final OrganizationRepository orgs;
    private final PrincipalRepository users;
    private final InvitationRepository invitations;
    private final AuditLogRepository audits;
    private final TenantService tenants;

    public SignupPolicyService(
            OrganizationRepository orgs,
            PrincipalRepository users,
            InvitationRepository invitations,
            AuditLogRepository audits,
            TenantService tenants) {
        this.orgs = orgs;
        this.users = users;
        this.invitations = invitations;
        this.audits = audits;
        this.tenants = tenants;
    }

    /**
     * The organization whose settings govern sign-ups that belong to no organization yet: the
     * install's first. In the open edition that is the only one; a later personal org on the same
     * install carries no policy of its own, and the settings routes say so rather than accept one.
     */
    public Optional<Organization> governingOrg() {
        return orgs.findOldest();
    }

    public boolean governs(String orgId) {
        return governingOrg().map(o -> o.id().equals(orgId)).orElse(false);
    }

    /** The policy in force for signups that belong to no organization yet. */
    public SignupPolicy current() {
        return governingOrg().map(o -> SignupPolicy.fromSettings(o.settings())).orElse(SignupPolicy.OPEN);
    }

    /**
     * Throws {@link AuthError#SIGNUP_REFUSED} when creating a principal for this identity would
     * break the policy. Returns quietly when a principal already exists for the WorkOS id or the
     * email, when no human account exists yet, when the policy is open, when a pending invitation
     * names the email, or when the email's domain is listed in domain mode.
     */
    public void admit(@Nullable String email, @Nullable String workosUserId) {
        if (workosUserId != null && users.findByWorkosId(workosUserId).isPresent()) return;
        if (email != null && users.findByEmail(email).isPresent()) return;
        if (!users.anyHumanExists()) return;
        SignupPolicy policy = current();
        if (policy.mode() == SignupPolicy.Mode.OPEN) return;
        String normalised = email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
        if (!normalised.isEmpty() && !invitations.findPendingByEmail(normalised).isEmpty()) return;
        if (policy.mode() == SignupPolicy.Mode.DOMAIN && policy.admitsDomainOf(normalised)) return;
        log.warn("auth: signup refused by the {} policy", policy.mode().wire());
        throw new TessaryException(AuthError.SIGNUP_REFUSED);
    }

    /** Writes the policy into the organization's settings and records the change in the audit log. */
    public SignupPolicy update(Organization org, SignupPolicy next, @Nullable String actorUserId) {
        SignupPolicy previous = SignupPolicy.fromSettings(org.settings());
        orgs.update(org.id(), org.name(), next.intoSettings(org.settings()));
        String now = Instant.now().toString();
        String projectId = tenants.ensureDefaultProject(org.id()).id();
        audits.insert(new AuditLog(
                Ids.ulid(),
                projectId,
                org.id(),
                SUBJECT_ORGANIZATION,
                org.id(),
                actorUserId,
                ACTION,
                previous.mode().wire() + " -> " + next.mode().wire(),
                "{\"before\":" + previous.intoSettings(null) + ",\"after\":" + next.intoSettings(null) + "}",
                null,
                now));
        return next;
    }
}
