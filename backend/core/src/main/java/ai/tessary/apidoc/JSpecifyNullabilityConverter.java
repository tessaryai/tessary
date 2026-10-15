// SPDX-License-Identifier: Apache-2.0
package ai.tessary.apidoc;

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
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Makes springdoc schemas follow JSpecify nullability, which swagger-core cannot see. Response record components are
 * required (Jackson ALWAYS inclusion) unless {@code @Nullable} in a {@code NON_NULL} class; {@code @Nullable}
 * components become OpenAPI 3.1 nullable. Registered globally via {@link ModelConverters}.
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
        if (resolved != null) {
            try {
                applyNullability(type, resolved, context);
            } catch (RuntimeException e) {
                // Best-effort: never fail spec generation over a reflection edge case.
                log.debug("faithful-nullability post-processing skipped for a schema", e);
            }
        }
        return resolved;
    }

    private void applyNullability(AnnotatedType type, Schema<?> resolved, ModelConverterContext context) {
        Class<?> raw = rawClass(type);
        if (!raw.isRecord() || !raw.getName().startsWith("ai.tessary.")) {
            return;
        }
        Schema<?> target = objectSchema(resolved, context);
        boolean omitNulls = raw.isAnnotationPresent(JsonInclude.class)
                && raw.getAnnotation(JsonInclude.class).value() == JsonInclude.Include.NON_NULL;
        // Request DTOs are deserialized from partial JSON, so their fields are never required. Heuristic:
        // {@code *Request}/{@code *Filter} records.
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

    /** A {@code $ref} to an undefined model throws, which {@link #resolve} turns into a skip. */
    private static Schema<?> objectSchema(Schema<?> resolved, ModelConverterContext context) {
        if (resolved.get$ref() != null) {
            String ref = resolved.get$ref();
            String name = ref.substring(ref.lastIndexOf('/') + 1);
            return Objects.requireNonNull(context.getDefinedModels().get(name), ref);
        }
        return resolved;
    }

    /** A {@code $ref} has no type to widen, so it is wrapped in {@code anyOf: [{$ref}, {type: "null"}]}. */
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

    /** A type Jackson cannot construct throws, which {@link #resolve} skips. */
    private static Class<?> rawClass(AnnotatedType type) {
        java.lang.reflect.Type t = type.getType();
        if (t instanceof Class<?> c) {
            return c;
        }
        return io.swagger.v3.core.util.Json.mapper()
                .getTypeFactory()
                .constructType(t)
                .getRawClass();
    }
}
