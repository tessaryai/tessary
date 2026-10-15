// SPDX-License-Identifier: Apache-2.0
package ai.tessary.tenant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Who may create an account on this install, stored under {@code signupPolicy} in {@link Organization#settings()}.
 * {@link Mode#OPEN} is the default. A pending invitation admits in every mode.
 */
public record SignupPolicy(Mode mode, List<String> domains) {

    public static final String SETTINGS_KEY = "signupPolicy";
    public static final SignupPolicy OPEN = new SignupPolicy(Mode.OPEN, List.of());

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(SignupPolicy.class);
    private static final Pattern DOMAIN =
            Pattern.compile("^(?!-)[a-z0-9-]{1,63}(?<!-)(\\.(?!-)[a-z0-9-]{1,63}(?<!-))+$");

    public enum Mode {
        OPEN,
        DOMAIN,
        INVITE;

        public String wire() {
            return name().toLowerCase(Locale.ROOT);
        }

        public static Mode parse(@Nullable String value) {
            if (value == null) throw new IllegalArgumentException("mode is required");
            return switch (value.trim().toLowerCase(Locale.ROOT)) {
                case "open" -> OPEN;
                case "domain" -> DOMAIN;
                case "invite" -> INVITE;
                default -> throw new IllegalArgumentException("mode must be one of open, domain, invite");
            };
        }
    }

    public SignupPolicy {
        domains = List.copyOf(domains);
    }

    /** Lower-cases and de-duplicates domains; domain mode requires at least one. */
    public static SignupPolicy of(@Nullable String mode, @Nullable List<String> domains) {
        Mode m = Mode.parse(mode);
        Set<String> clean = new LinkedHashSet<>();
        if (domains != null) {
            for (String d : domains) {
                if (d == null) continue;
                String normalised = d.trim().toLowerCase(Locale.ROOT);
                if (normalised.startsWith("@")) normalised = normalised.substring(1);
                if (normalised.isEmpty()) continue;
                if (!DOMAIN.matcher(normalised).matches()) {
                    throw new IllegalArgumentException("not a domain name: " + d.trim());
                }
                clean.add(normalised);
            }
        }
        if (m == Mode.DOMAIN && clean.isEmpty()) {
            throw new IllegalArgumentException("domain mode needs at least one email domain");
        }
        return new SignupPolicy(m, new ArrayList<>(clean));
    }

    /** {@link #OPEN} when absent or unreadable. */
    public static SignupPolicy fromSettings(@Nullable String settingsJson) {
        if (settingsJson == null || settingsJson.isBlank()) return OPEN;
        try {
            JsonNode node = MAPPER.readTree(settingsJson).path(SETTINGS_KEY);
            if (!node.isObject()) return OPEN;
            List<String> domains = new ArrayList<>();
            for (JsonNode d : node.path("domains")) {
                if (d.isTextual()) domains.add(d.asText());
            }
            return of(node.path("mode").asText("open"), domains);
        } catch (IllegalArgumentException | com.fasterxml.jackson.core.JacksonException e) {
            // Fail open: an unreadable policy must not refuse the owner who would fix it. Loud, because open is not
            // what the operator asked for.
            log.warn("organization settings carry an unreadable signupPolicy; treating it as open");
            return OPEN;
        }
    }

    /**
     * True when {@code proposed} carries a different policy, or omits one while one is stored (a whole-blob write would
     * drop it). {@link #carryInto} keeps it.
     */
    public static boolean changesPolicy(@Nullable String current, @Nullable String proposed) {
        if (proposed == null || proposed.isBlank()) return false;
        try {
            JsonNode next = MAPPER.readTree(proposed).path(SETTINGS_KEY);
            if (next.isMissingNode()) {
                return current != null
                        && !current.isBlank()
                        && !MAPPER.readTree(current).path(SETTINGS_KEY).isMissingNode();
            }
            JsonNode now = current == null || current.isBlank()
                    ? MAPPER.missingNode()
                    : MAPPER.readTree(current).path(SETTINGS_KEY);
            return !next.equals(now);
        } catch (com.fasterxml.jackson.core.JacksonException e) {
            return true;
        }
    }

    /** Every other key is kept. */
    public String intoSettings(@Nullable String settingsJson) {
        ObjectNode root;
        try {
            JsonNode existing = settingsJson == null || settingsJson.isBlank() ? null : MAPPER.readTree(settingsJson);
            root = existing instanceof ObjectNode o ? o : MAPPER.createObjectNode();
        } catch (com.fasterxml.jackson.core.JacksonException e) {
            root = MAPPER.createObjectNode();
        }
        ObjectNode policy = root.putObject(SETTINGS_KEY);
        policy.put("mode", mode.wire());
        ArrayNode list = policy.putArray("domains");
        domains.forEach(list::add);
        return root.toString();
    }

    public static boolean omitsPolicy(String settingsJson) {
        try {
            return MAPPER.readTree(settingsJson).path(SETTINGS_KEY).isMissingNode();
        } catch (com.fasterxml.jackson.core.JacksonException e) {
            return false;
        }
    }

    /** So a rename cannot drop the policy. */
    public static String carryInto(@Nullable String current, String proposed) {
        if (current == null || current.isBlank() || omitsPolicy(current)) return proposed;
        return fromSettings(current).intoSettings(proposed);
    }

    public boolean admitsDomainOf(String email) {
        int at = email.lastIndexOf('@');
        if (at < 0 || at == email.length() - 1) return false;
        return domains.contains(email.substring(at + 1).toLowerCase(Locale.ROOT));
    }
}
