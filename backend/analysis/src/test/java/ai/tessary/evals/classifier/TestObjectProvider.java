// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.classifier;

import java.util.List;
import java.util.stream.Stream;
import org.springframework.beans.factory.ObjectProvider;

/**
 * A fixed list of beans as an {@link ObjectProvider}, for the ports that are injected that way.
 *
 * <p>Three of the classifier ports take {@code ObjectProvider<T>} rather than {@code List<T>}, and the
 * reason is the state this helper makes easy to write: ZERO candidates. Spring treats a required
 * constructor {@code List<T>} with no matching bean as an unsatisfied dependency and refuses to start
 * the context, so an edition shipping no adapter for a port would fail to boot rather than degrade —
 * with no compile error and no import for the boundary check to see. {@code ObjectProvider} is what
 * makes "nobody implements this here" an ordinary answer, and {@code of(List.of())} is what lets a test
 * say it in one line.
 *
 * <p>Only the two collection accessors are implemented. Everything else on the interface throws, so a
 * production class that starts calling {@code getObject()} on one of these ports fails loudly in a test
 * rather than picking up a default nobody chose.
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
                // The real provider sorts by @Order here. A test that cares about ordering passes the
                // list in the order it wants, which is what every caller so far actually asserts.
                return copy.stream();
            }
        };
    }

    @SafeVarargs
    public static <T> ObjectProvider<T> of(T... beans) {
        return of(List.of(beans));
    }
}
