// SPDX-License-Identifier: Apache-2.0
package ai.tessary.apidoc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.core.converter.AnnotatedType;
import io.swagger.v3.core.converter.ModelConverter;
import io.swagger.v3.core.converter.ModelConverterContextImpl;
import io.swagger.v3.oas.models.media.Schema;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.ETag;

/**
 * {@link JSpecifyNullabilityConverter} on a DTO record's schema: response fields required, {@code @Nullable} ones
 * nullable, request DTOs and {@code NON_NULL} omissions optional, and anything unprocessable left as it came.
 */
class JSpecifyNullabilityConverterTest {

    record Part(String id) {}

    record Widget(
            String name,
            @Nullable String note,
            @Nullable Part part,
            @Nullable Integer count,
            @JsonProperty("display_name") String displayName) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Sparse(String id, @Nullable String note) {}

    record WidgetRequest(String name, @Nullable String note) {}

    private static @Nullable Schema<?> resolve(Class<?> type, Schema<?> fromChain, ModelConverterContextImpl context) {
        ModelConverter downstream = (t, c, chain) -> fromChain;
        return new JSpecifyNullabilityConverter()
                .resolve(new AnnotatedType(type), context, List.of(downstream).iterator());
    }

    private static Schema<?> prop(Schema<?> schema, String name) {
        return Objects.requireNonNull(schema.getProperties().get(name), name);
    }

    private static Schema<?> string() {
        return new Schema<>().types(Set.of("string"));
    }

    private static Schema<?> object(String... stringProperties) {
        Schema<?> schema = new Schema<>();
        for (String name : stringProperties) {
            schema.addProperty(name, string());
        }
        return schema;
    }

    /**
     * A response field the server always sends typed optional, or a {@code @Nullable} one typed non-null so the
     * client crashes on null. Covers a model {@code $ref}, a legacy {@code type}, a nested {@code $ref}, and a {@code
     * @JsonProperty} rename.
     */
    @Test
    void responseRecord_everyFieldRequired_nullableFieldsAcceptNull() {
        ModelConverterContextImpl context = new ModelConverterContextImpl(List.of());
        Schema<?> model = object("name", "note");
        model.addProperty("part", new Schema<>().$ref("#/components/schemas/Part"));
        model.addProperty("count", new Schema<>().type("integer"));
        model.addProperty("display_name", string());
        context.defineModel("Widget", model);

        resolve(Widget.class, new Schema<>().$ref("#/components/schemas/Widget"), context);

        assertEquals(
                Set.of("name", "note", "part", "count", "display_name"),
                Set.copyOf(Objects.requireNonNull(model.getRequired())));
        assertEquals(Set.of("string"), prop(model, "name").getTypes());
        assertEquals(Set.of("string", "null"), prop(model, "note").getTypes());
        assertEquals(Set.of("integer", "null"), prop(model, "count").getTypes());
        Schema<?> part = prop(model, "part");
        assertNull(part.get$ref(), "a nullable $ref moves into anyOf");
        assertEquals(
                List.of(new Schema<>().$ref("#/components/schemas/Part"), new Schema<>().types(Set.of("null"))),
                part.getAnyOf());
    }

    /**
     * A {@code @Nullable} field of a {@code NON_NULL} class typed required, and a request DTO's fields typed
     * required.
     */
    @Test
    void omittedWhenNullAndRequestFields_areOptional() {
        ModelConverterContextImpl context = new ModelConverterContextImpl(List.of());
        Schema<?> sparse = object("id", "note");
        Schema<?> request = object("name", "note");

        resolve(Sparse.class, sparse, context);
        resolve(WidgetRequest.class, request, context);

        assertEquals(List.of("id"), sparse.getRequired());
        assertEquals(Set.of("string", "null"), prop(sparse, "note").getTypes());
        assertNull(request.getRequired());
        assertEquals(Set.of("string", "null"), prop(request, "note").getTypes());
    }

    /**
     * A library record (Spring's {@code ETag}) given marks its authors never meant; only ai.tessary records are
     * processed.
     */
    @Test
    void aRecordOutsideTessary_isLeftAsTheChainProducedIt() {
        Schema<?> etag = object("tag", "weak");

        resolve(ETag.class, etag, new ModelConverterContextImpl(List.of()));

        assertNull(etag.getRequired());
    }

    static Stream<Arguments> unprocessable() {
        return Stream.of(
                Arguments.of("a type Jackson cannot construct", new AnnotatedType()),
                Arguments.of("a $ref to a model the context never defined", new AnnotatedType(Widget.class)));
    }

    /** A reflection edge case must not fail OpenAPI generation; the schema comes back as produced. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("unprocessable")
    void anUnprocessableSchema_isReturnedUntouched(String edge, AnnotatedType type) {
        Schema<?> fromChain = new Schema<>().$ref("#/components/schemas/Widget");
        ModelConverter downstream = (t, c, chain) -> fromChain;

        Schema<?> out = new JSpecifyNullabilityConverter()
                .resolve(
                        type,
                        new ModelConverterContextImpl(List.of()),
                        List.of(downstream).iterator());

        assertSame(fromChain, out, edge);
        assertEquals(new Schema<>().$ref("#/components/schemas/Widget"), out, edge);
    }
}
