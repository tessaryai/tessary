// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier;

import java.util.List;
import java.util.stream.Stream;
import org.springframework.beans.factory.ObjectProvider;

/**
 * A fixed list of beans as an {@link ObjectProvider}. Three classifier ports take {@code ObjectProvider<T>} because
 * Spring refuses to start with a required {@code List<T>} that has no bean; this makes zero candidates one line. Only
 * the collection accessors are implemented, so a class calling {@code getObject()} fails loudly.
 */
public final class TestObjectProvider {

    private TestObjectProvider() {}

    public static <T> ObjectProvider<T> of(List<T> beans) {
        List<T> copy = List.copyOf(beans);
        return new ObjectProvider<>() {
            @Override
            public T getObject() {
                throw new UnsupportedOperationException("this provider only answers as a collection");
            }

            @Override
            public Stream<T> stream() {
                return copy.stream();
            }

            @Override
            public Stream<T> orderedStream() {
                // The real provider sorts by @Order; callers pass the order they want.
                return copy.stream();
            }
        };
    }

    @SafeVarargs
    public static <T> ObjectProvider<T> of(T... beans) {
        return of(List.of(beans));
    }
}
