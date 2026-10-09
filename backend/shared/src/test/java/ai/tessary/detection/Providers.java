// SPDX-License-Identifier: Apache-2.0
package ai.tessary.detection;

import java.util.stream.Stream;
import org.springframework.beans.factory.ObjectProvider;

final class Providers {

    private Providers() {}

    @SafeVarargs
    static <T> ObjectProvider<T> of(T... items) {
        return new ObjectProvider<>() {
            @Override
            public T getObject() {
                return items[0];
            }

            @Override
            public T getIfAvailable() {
                return items.length == 0 ? null : items[0];
            }

            @Override
            public Stream<T> orderedStream() {
                return Stream.of(items);
            }
        };
    }
}
