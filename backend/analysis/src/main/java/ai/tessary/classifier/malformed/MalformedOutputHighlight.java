// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.malformed;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Where a failing field sits in a pretty-printed document: a second, location-tracking parse of the
 * exact text a reader is shown, so a highlighted line always names a real line of what's on their
 * screen rather than one recomputed from a different serialization.
 *
 * <p>A field's collapsed path ({@code items[].sku}) can own more than one span — one array element's
 * {@code sku} for every element that violated it — so every match highlights, not only the first.
 * {@code required}'s span does not exist in the document (the property is the thing that is missing),
 * so it falls back to the line the parent object opens on.
 */
final class MalformedOutputHighlight {

    private MalformedOutputHighlight() {}

    /** One node's line span in the document: {@code start == end} for a scalar. */
    private record Span(int start, int end) {}

    static List<Integer> forField(ObjectMapper mapper, String prettyJson, String field) {
        Map<String, List<Span>> index;
        try (JsonParser p = mapper.getFactory().createParser(prettyJson)) {
            index = new java.util.HashMap<>();
            if (p.nextToken() != null) {
                walk(p, new ArrayList<>(), index);
            }
        } catch (IOException | RuntimeException e) {
            return List.of();
        }
        List<Span> spans = index.get(field);
        if (spans == null) {
            int lastDot = field.lastIndexOf('.');
            String parent = lastDot < 0 ? "" : field.substring(0, lastDot);
            spans = index.get(parent);
            if (spans == null) return List.of();
            List<Integer> lines = new ArrayList<>();
            for (Span s : spans) lines.add(s.start());
            return List.copyOf(lines);
        }
        var lines = new TreeSet<Integer>();
        for (Span s : spans) {
            for (int line = s.start(); line <= s.end(); line++) lines.add(line);
        }
        return List.copyOf(lines);
    }

    private static void walk(JsonParser p, List<Object> path, Map<String, List<Span>> index) throws IOException {
        JsonToken token = p.currentToken();
        int startLine = p.currentTokenLocation().getLineNr();
        if (token == JsonToken.START_OBJECT) {
            while (p.nextToken() != JsonToken.END_OBJECT) {
                String field = p.currentName();
                p.nextToken();
                path.add(field);
                walk(p, path, index);
                path.remove(path.size() - 1);
            }
            record(index, path, startLine, p.currentTokenLocation().getLineNr());
        } else if (token == JsonToken.START_ARRAY) {
            int i = 0;
            while (p.nextToken() != JsonToken.END_ARRAY) {
                path.add(i++);
                walk(p, path, index);
                path.remove(path.size() - 1);
            }
            record(index, path, startLine, p.currentTokenLocation().getLineNr());
        } else {
            record(index, path, startLine, startLine);
        }
    }

    private static void record(Map<String, List<Span>> index, List<Object> path, int start, int end) {
        index.computeIfAbsent(collapse(path), k -> new ArrayList<>()).add(new Span(start, end));
    }

    /** The same {@code []}-collapsing convention {@code MalformedOutputDetector} writes violations in. */
    private static String collapse(List<Object> path) {
        StringBuilder sb = new StringBuilder();
        for (Object element : path) {
            if (element instanceof Integer) {
                sb.append("[]");
            } else {
                if (sb.length() > 0) sb.append('.');
                sb.append(element);
            }
        }
        return sb.toString();
    }
}
