// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.apidoc;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.core.converter.AnnotatedType;
import io.swagger.v3.core.converter.ModelConverter;
import io.swagger.v3.core.converter.ModelConverterContext;
import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.oas.models.media.Schema;
import jakarta.annotation.PostConstruct;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Makes the springdoc-generated schemas <em>faithful</em> to the DTO records' JSpecify nullability:
 * the codebase is {@code @NullMarked}, so a record component is non-null unless it
 * carries {@code @Nullable}. swagger-core can't see JSpecify, so by default it emits every field as
 * optional and non-nullable — which makes the generated TS types materially weaker than the wire truth.
 *
 * <p>This {@link ModelConverter} post-processes every {@code ai.tessary.evals} record schema: Jackson's
 * default inclusion is {@code ALWAYS}, so every component is present in the JSON and therefore
 * <em>required</em> — except that a {@code @Nullable} component of a {@code @JsonInclude(NON_NULL)} class
 * is omitted when null (so optional). {@code @Nullable} components are additionally marked nullable
 * (OpenAPI 3.1 {@code type: [T, "null"]}). Registered globally via {@link ModelConverters} (idempotent
 * across the many {@code @SpringBootTest} contexts in one JVM).
 */
@Component
public class JSpecifyNullabilityConverter implements ModelConverter {

    private static final Logger log = LoggerFactory.getLogger(JSpecifyNullabilityConverter.class);
    private static final AtomicBoolean REGISTERED = new AtomicBoolean(false);

    @PostConstruct
    void register() {
        if (REGISTERED.compareAndSet(false, true)) {
            ModelConverters.getInstance().addConverter(this);
        }
    }

    @Override
    public @Nullable Schema<?> resolve(
            AnnotatedType type, ModelConverterContext context, Iterator<ModelConverter> chain) {
        Schema<?> resolved = chain.hasNext() ? chain.next().resolve(type, context, chain) : null;
        try {
            applyNullability(type, resolved, context);
        } catch (RuntimeException e) {
            // Faithful-typing is best-effort: never fail spec generation over a reflection edge case.
            log.debug("faithful-nullability post-processing skipped for a schema", e);
        }
        return resolved;
    }

    private void applyNullability(AnnotatedType type, @Nullable Schema<?> resolved, ModelConverterContext context) {
        Class<?> raw = rawClass(type);
        if (raw == null || !raw.isRecord() || !raw.getName().startsWith("ai.tessary.")) {
            return;
        }
        Schema<?> target = objectSchema(resolved, context);
        if (target == null || target.getProperties() == null) {
            return;
        }
        boolean omitNulls = raw.isAnnotationPresent(JsonInclude.class)
                && raw.getAnnotation(JsonInclude.class).value() == JsonInclude.Include.NON_NULL;
        // A request-body DTO is deserialized from a client-supplied (often partial) JSON — the server fills
        // defaults/validates — so its fields must NOT be marked required (that over-constrains the generated
        // client, forcing callers to send every field). We only mark fields required for RESPONSE DTOs, which
        // are serialized whole (Jackson ALWAYS-inclusion ⇒ every field present). Heuristic on the codebase
        // convention: request DTOs are the {@code *Request}/{@code *Filter} records used as {@code @RequestBody}.
        boolean requestDto =
                raw.getSimpleName().endsWith("Request") || raw.getSimpleName().endsWith("Filter");

        List<String> required = new ArrayList<>();
        for (RecordComponent rc : raw.getRecordComponents()) {
            String name = jsonName(rc);
            Schema<?> prop = target.getProperties().get(name);
            if (prop == null) {
                continue;
            }
            boolean nullable = isNullable(rc);
            // ALWAYS-inclusion (the app default) ⇒ every response field is present ⇒ required; a @Nullable
            // field of a NON_NULL-inclusion class is omitted when null ⇒ optional; request DTOs stay optional.
            if (!requestDto && !(nullable && omitNulls)) {
                required.add(name);
            }
            if (nullable) {
                makeNullable(prop);
            }
        }
        if (!required.isEmpty()) {
            target.setRequired(required);
        }
    }

    /** Resolve the concrete object {@link Schema} — following a {@code $ref} into the context's models. */
    private static @Nullable Schema<?> objectSchema(@Nullable Schema<?> resolved, ModelConverterContext context) {
        if (resolved == null) {
            return null;
        }
        if (resolved.get$ref() != null) {
            String ref = resolved.get$ref();
            String name = ref.substring(ref.lastIndexOf('/') + 1);
            return context.getDefinedModels().get(name);
        }
        return resolved;
    }

    /**
     * OpenAPI 3.1 nullability. For a typed schema, add {@code "null"} to its type set. For a {@code $ref}
     * (a nested-object property) there is no {@code type} to widen, so wrap it in {@code anyOf: [{$ref},
     * {type: "null"}]} — the 3.1-correct way to make a referenced schema nullable.
     */
    private static void makeNullable(Schema<?> prop) {
        if (prop.get$ref() != null) {
            String ref = prop.get$ref();
            prop.set$ref(null);
            Schema<Object> refPart = new Schema<>();
            refPart.set$ref(ref);
            Schema<Object> nullPart = new Schema<>();
            nullPart.setTypes(new LinkedHashSet<>(Set.of("null")));
            prop.setAnyOf(List.of(refPart, nullPart));
            return;
        }
        Set<String> types = prop.getTypes() != null ? new LinkedHashSet<>(prop.getTypes()) : new LinkedHashSet<>();
        if (prop.getTypes() == null && prop.getType() != null) {
            types.add(prop.getType());
        }
        if (!types.isEmpty()) {
            types.add("null");
            prop.setTypes(types);
        }
    }

    /** True when the record component's type carries a JSpecify (or any) {@code @Nullable}. */
    private static boolean isNullable(RecordComponent rc) {
        return hasNullable(rc.getAnnotatedType().getAnnotations())
                || hasNullable(rc.getAnnotations())
                || hasNullable(rc.getAccessor().getAnnotatedReturnType().getAnnotations());
    }

    private static boolean hasNullable(java.lang.annotation.Annotation[] annotations) {
        for (java.lang.annotation.Annotation a : annotations) {
            if ("Nullable".equals(a.annotationType().getSimpleName())) {
                return true;
            }
        }
        return false;
    }

    /** The JSON property name — {@code @JsonProperty} override on the component/accessor, else the name. */
    private static String jsonName(RecordComponent rc) {
        JsonProperty jp = rc.getAnnotation(JsonProperty.class);
        if (jp == null) {
            jp = rc.getAccessor().getAnnotation(JsonProperty.class);
        }
        if (jp != null && !jp.value().isEmpty()) {
            return jp.value();
        }
        return rc.getName();
    }

    private static @Nullable Class<?> rawClass(AnnotatedType type) {
        if (type == null || type.getType() == null) {
            return null;
        }
        java.lang.reflect.Type t = type.getType();
        if (t instanceof Class<?> c) {
            return c;
        }
        try {
            com.fasterxml.jackson.databind.JavaType jt =
                    io.swagger.v3.core.util.Json.mapper().getTypeFactory().constructType(t);
            return jt.getRawClass();
        } catch (RuntimeException e) {
            return null;
        }
    }
}
