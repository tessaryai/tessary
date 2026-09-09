// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The root of the checkout a test is running inside: the nearest ancestor of the working directory
 * that holds a {@code .git} or {@code .jj} entry. Tests that read cross-language fixtures resolve
 * them from here and never past it, so a checkout that happens to contain an export candidate
 * cannot satisfy the export's tests with its own private files.
 */
public final class CheckoutRoot {
    private CheckoutRoot() {}

    public static Path locate() {
        Path start = Path.of("").toAbsolutePath();
        for (Path dir = start; dir != null; dir = dir.getParent()) {
            if (Files.exists(dir.resolve(".git")) || Files.exists(dir.resolve(".jj"))) return dir;
        }
        throw new IllegalStateException("no checkout root (.git or .jj) above " + start);
    }
}
