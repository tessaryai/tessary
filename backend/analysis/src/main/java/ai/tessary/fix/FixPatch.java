// SPDX-License-Identifier: Apache-2.0
package ai.tessary.fix;

import ai.tessary.agentrun.SessionEvent;
import ai.tessary.agentrun.SessionEvent.PatchFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Checks a sandbox's patch again before any of it reaches the repository, with the sandbox's own rules: the sandbox is
 * the agent's machine, so its word is not enough. A patch with one path Tessary may not write is refused whole. Paths
 * the sandbox itself refused (its tool config, for one) never reach the patch's files, and the rest is delivered.
 */
final class FixPatch {

    static final int MAX_FILES = 50;
    static final int MAX_BYTES = 1 << 20;

    /** Tool configuration the fix lane quarantines; an edit to it is never delivered. */
    static final Set<String> QUARANTINED = Set.of(".opencode", "opencode.json", "opencode.jsonc", ".cursor");

    static final String SYMLINK = "120000";
    private static final Set<String> MODES = Set.of("100644", "100755", SYMLINK);

    private FixPatch() {}

    /** @param reason a {@link FixRunRow.Reason} when the patch cannot be delivered, else null */
    record Verdict(List<PatchFile> files, @Nullable String reason) {}

    static Verdict check(SessionEvent.@Nullable Patch patch) {
        if (patch == null) return refused(FixRunRow.Reason.EMPTY_PATCH);
        if (patch.overLimit() != null || patch.files().size() > MAX_FILES) {
            return refused(FixRunRow.Reason.PATCH_OVER_LIMIT);
        }
        if (patch.files().isEmpty()) return refused(FixRunRow.Reason.EMPTY_PATCH);
        long bytes = 0;
        for (PatchFile f : patch.files()) {
            if (!writable(f)) return refused(FixRunRow.Reason.PATCH_REFUSED);
            if (!f.deleted()) bytes += f.content().length;
        }
        if (bytes > MAX_BYTES) return refused(FixRunRow.Reason.PATCH_OVER_LIMIT);
        return new Verdict(patch.files(), null);
    }

    private static Verdict refused(String reason) {
        return new Verdict(List.of(), reason);
    }

    private static boolean writable(PatchFile f) {
        String path = f.path();
        if (path.isEmpty() || path.startsWith("/") || path.contains("\\")) return false;
        for (String segment : path.split("/", -1)) {
            String lower = segment.toLowerCase(Locale.ROOT);
            if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment) || ".git".equals(lower)) return false;
            if (QUARANTINED.contains(segment)) return false;
        }
        if (f.deleted()) return true;
        String mode = f.mode();
        if (mode == null || !MODES.contains(mode)) return false;
        return !SYMLINK.equals(mode) || staysInRepo(path, new String(f.content(), StandardCharsets.UTF_8));
    }

    /** A symlink's target, read from where the link sits, must not leave the repository. */
    static boolean staysInRepo(String linkPath, String target) {
        if (target.startsWith("/")) return false;
        Path parent = Path.of(linkPath).getParent();
        Path resolved = (parent == null ? Path.of(target) : parent.resolve(target)).normalize();
        return !resolved.startsWith("..");
    }
}
