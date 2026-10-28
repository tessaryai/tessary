// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.finding;

import ai.tessary.redaction.CredentialMasking;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/** The bulk export's text: every string masked, then cut to the caller's size and marked. */
public final class EvidenceExportText {

    public static final String CUT_MARK = " …[cut]";

    /**
     * SQL cuts payload text at {@code chars + MARGIN}, so a credential that starts before {@code chars} and ends inside
     * the margin is masked before the final cut. One longer than the margin that straddles the cut is cut before its
     * end and stays unmasked, the same residual the 200-character previews carry. Every single-line gitleaks rule
     * matches well under 300 characters; the private-key rule needs its END footer, and 4 KiB covers a PEM block up to
     * a 4096-bit key.
     */
    public static final int MARGIN = 4096;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final TypeReference<LinkedHashMap<String, Object>> MAP = new TypeReference<>() {};

    private EvidenceExportText() {}

    public static @Nullable String maskThenCut(@Nullable String sqlCut, int chars) {
        String masked = CredentialMasking.mask(sqlCut);
        if (masked == null || masked.length() <= chars) return masked;
        return masked.substring(0, chars) + CUT_MARK;
    }

    /** Unparseable JSON comes back whole under {@code _raw}, masked and cut, so nothing is silently dropped. */
    public static @Nullable Map<String, Object> attributes(@Nullable String json, int chars) {
        if (json == null) return null;
        LinkedHashMap<String, Object> parsed;
        try {
            parsed = MAPPER.readValue(json, MAP);
        } catch (JsonProcessingException e) {
            Map<String, Object> raw = new LinkedHashMap<>();
            raw.put("_raw", maskThenCut(json, chars));
            return raw;
        }
        if (parsed == null) return null;
        @SuppressWarnings("unchecked")
        Map<String, Object> walked = (Map<String, Object>) walk(parsed, chars);
        return walked;
    }

    public static int chars(@Nullable String s) {
        return s == null ? 0 : s.length();
    }

    /** The serialised size of an attribute map, for the page budget. */
    public static int chars(@Nullable Map<String, Object> attributes) {
        if (attributes == null) return 0;
        try {
            return MAPPER.writeValueAsString(attributes).length();
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static @Nullable Object walk(@Nullable Object value, int chars) {
        if (value instanceof String s) return maskThenCut(s, chars);
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : map.entrySet()) out.put(String.valueOf(e.getKey()), walk(e.getValue(), chars));
            return out;
        }
        if (value instanceof List<?> list) {
            List<Object> out = new ArrayList<>(list.size());
            for (Object item : list) out.add(walk(item, chars));
            return out;
        }
        return value;
    }
}
