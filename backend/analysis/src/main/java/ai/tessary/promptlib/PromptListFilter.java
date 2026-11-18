// SPDX-License-Identifier: Apache-2.0
package ai.tessary.promptlib;

import ai.tessary.open.errors.CommonError;
import ai.tessary.open.errors.TessaryException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * The public list's JSON {@code filter}: a list of conditions in the grammar another prompt tool uses, each true of one
 * prompt version. A prompt is listed when one of its versions meets every condition, and only those versions count.
 * Every column and operator is checked against a fixed set here, so the repository maps each to a fixed SQL fragment
 * and binds every value.
 */
public final class PromptListFilter {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final Set<String> TEXT_COLUMNS = Set.of("id", "name", "type");
    private static final Set<String> TEXT_OPERATORS =
            Set.of("=", "contains", "does not contain", "starts with", "ends with", "is not empty");
    private static final Set<String> OPTIONS_OPERATORS = Set.of("any of", "none of");
    private static final Set<String> NUMBER_OPERATORS = Set.of("=", ">", "<", ">=", "<=");
    private static final Set<String> TIME_COLUMNS = Set.of("createdAt", "updatedAt");
    private static final Set<String> TIME_OPERATORS = Set.of(">", "<", ">=", "<=");
    private static final Set<String> ARRAY_COLUMNS = Set.of("labels", "tags");
    private static final Set<String> ARRAY_OPERATORS = Set.of("any of", "none of", "all of");
    private static final Set<String> CONFIG_OPERATORS =
            Set.of("=", "contains", "does not contain", "starts with", "ends with", "is set", "is not set");
    private static final Set<String> SUBSTRING_OPERATORS = Set.of("contains", "starts with", "ends with");

    /** One condition on a prompt version. */
    public sealed interface Condition
            permits TextMatch, OptionsMatch, NumberMatch, TimeMatch, ArrayMatch, ConfigMatch {}

    /** {@code id} (the version id), {@code name} or {@code type}; {@code value} is empty for "is not empty". */
    public record TextMatch(String column, String operator, String value) implements Condition {}

    public record OptionsMatch(String column, String operator, List<String> values) implements Condition {}

    /** On {@code version}. */
    public record NumberMatch(String operator, double value) implements Condition {}

    /** {@code createdAt} is the version's; {@code updatedAt} is the prompt's, which moves on every write. */
    public record TimeMatch(String column, String operator, Instant value) implements Condition {}

    /** {@code labels} of the version, {@code latest} included when it is the highest, or {@code tags} of the prompt. */
    public record ArrayMatch(String column, String operator, List<String> values) implements Condition {}

    /** A top-level key of the version's config, compared as text. */
    public record ConfigMatch(String key, String operator, String value) implements Condition {}

    private PromptListFilter() {}

    /** Empty when no filter was sent; an empty list is a filter with no condition. */
    public static Optional<List<Condition>> parse(@Nullable String json) {
        if (json == null || json.isBlank()) return Optional.empty();
        JsonNode root;
        try {
            root = MAPPER.readTree(json);
        } catch (Exception e) {
            throw invalid("invalid JSON");
        }
        if (root.isNull()) return Optional.empty();
        if (!root.isArray()) throw invalid("a list of conditions");
        List<Condition> conditions = new ArrayList<>(root.size());
        for (JsonNode node : root) conditions.add(condition(node));
        return Optional.of(List.copyOf(conditions));
    }

    private static Condition condition(JsonNode node) {
        if (!node.isObject()) throw invalid("each condition is an object");
        String type = text(node, "type");
        String column = text(node, "column");
        String operator = text(node, "operator");
        return switch (type) {
            case "string" -> {
                require(TEXT_COLUMNS.contains(column) && TEXT_OPERATORS.contains(operator), type);
                yield new TextMatch(column, operator, "is not empty".equals(operator) ? "" : text(node, "value"));
            }
            case "stringOptions" -> {
                require(TEXT_COLUMNS.contains(column) && OPTIONS_OPERATORS.contains(operator), type);
                yield new OptionsMatch(column, operator, strings(node));
            }
            case "number" -> {
                require("version".equals(column) && NUMBER_OPERATORS.contains(operator), type);
                JsonNode value = node.path("value");
                if (!value.isNumber()) throw invalid("a number value");
                yield new NumberMatch(operator, value.asDouble());
            }
            case "datetime" -> {
                require(TIME_COLUMNS.contains(column) && TIME_OPERATORS.contains(operator), type);
                yield new TimeMatch(column, operator, instant(node.path("value")));
            }
            case "arrayOptions" -> {
                require(ARRAY_COLUMNS.contains(column) && ARRAY_OPERATORS.contains(operator), type);
                yield new ArrayMatch(column, operator, strings(node));
            }
            case "stringObject" -> {
                require("config".equals(column) && CONFIG_OPERATORS.contains(operator), type);
                String key = text(node, "key");
                boolean presence = operator.startsWith("is ");
                String value = presence ? "" : text(node, "value");
                if (value.isEmpty() && SUBSTRING_OPERATORS.contains(operator)) operator = "is set";
                yield new ConfigMatch(key, operator, value);
            }
            default -> throw invalid("unknown condition type " + type);
        };
    }

    private static void require(boolean allowed, String type) {
        if (!allowed) throw invalid("a column or operator that a " + type + " condition does not allow");
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (!value.isTextual()) throw invalid(field + " must be a string");
        return checked(value.asText());
    }

    private static List<String> strings(JsonNode node) {
        JsonNode value = node.path("value");
        if (!value.isArray() || value.isEmpty()) throw invalid("value must be a list of at least one string");
        List<String> out = new ArrayList<>(value.size());
        for (JsonNode v : value) {
            if (!v.isTextual()) throw invalid("value must be a list of strings");
            out.add(checked(v.asText()));
        }
        return List.copyOf(out);
    }

    /** An ISO-8601 time with an offset, or epoch milliseconds. */
    private static Instant instant(JsonNode value) {
        if (value.isNumber()) return Instant.ofEpochMilli(value.asLong());
        if (!value.isTextual()) throw invalid("a datetime value");
        try {
            return OffsetDateTime.parse(value.asText()).toInstant();
        } catch (DateTimeParseException e) {
            throw invalid("a datetime value");
        }
    }

    /** Postgres refuses U+0000 as a parameter, so it is a bad request, not a 500. */
    private static String checked(String value) {
        if (PromptBody.holdsNul(value)) throw invalid("a value " + PromptBody.NUL);
        return value;
    }

    private static TessaryException invalid(String reason) {
        return new TessaryException(CommonError.INVALID_PARAMETER, "filter: " + reason);
    }
}
