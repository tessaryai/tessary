// SPDX-License-Identifier: Apache-2.0
package ai.tessary.fix;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ai.tessary.agentrun.SessionEvent;
import ai.tessary.agentrun.SessionEvent.PatchFile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The backend's own check of the sandbox's patch. The sandbox is the agent's machine, so each rule here is a path a
 * compromised or confused sandbox could send: into {@code .git}, into the agent's tool config, out of the repository,
 * or a change too large to review. Any one bad path refuses the whole patch.
 */
class FixPatchTest {

    private static PatchFile file(String path, String content) {
        return file(path, "100644", content);
    }

    private static PatchFile file(String path, String mode, String content) {
        return new PatchFile(path, mode, Base64.getEncoder().encodeToString(content.getBytes(StandardCharsets.UTF_8)));
    }

    private static SessionEvent.Patch patch(PatchFile... files) {
        return new SessionEvent.Patch(7, "base", List.of(files), List.of(), null);
    }

    @Test
    void aPatchOfOrdinaryFilesIsDeliveredWhole() {
        PatchFile edited = file("src/agent.py", "retry()");
        PatchFile script = file("bin/run", "100755", "#!/bin/sh");
        PatchFile link = file("docs/current", FixPatch.SYMLINK, "../docs/v2");
        PatchFile deleted = new PatchFile("src/old.py", null, null);

        assertEquals(
                new FixPatch.Verdict(List.of(edited, script, link, deleted), null),
                FixPatch.check(patch(edited, script, link, deleted)));
    }

    @Test
    void noPatchOrAnEmptyOneIsAnEmptyPatch() {
        assertEquals(FixRunRow.Reason.EMPTY_PATCH, FixPatch.check(null).reason());
        assertEquals(FixRunRow.Reason.EMPTY_PATCH, FixPatch.check(patch()).reason());
    }

    @Test
    void aPatchTheSandboxSaidWasOverItsCapIsOverTheLimit() {
        SessionEvent.Patch over = new SessionEvent.Patch(7, "base", List.of(), List.of(), "files");

        assertEquals(new FixPatch.Verdict(List.of(), FixRunRow.Reason.PATCH_OVER_LIMIT), FixPatch.check(over));
    }

    @Test
    void moreFilesThanTheCapAreOverTheLimitEvenWhenTheSandboxSentThem() {
        List<PatchFile> files = new ArrayList<>();
        for (int i = 0; i <= FixPatch.MAX_FILES; i++) files.add(file("src/f" + i + ".py", "x"));

        assertEquals(
                FixRunRow.Reason.PATCH_OVER_LIMIT,
                FixPatch.check(new SessionEvent.Patch(7, "base", files, List.of(), null))
                        .reason());
    }

    @Test
    void moreBytesThanTheCapAreOverTheLimit() {
        String half = "x".repeat(FixPatch.MAX_BYTES / 2 + 1);

        assertEquals(
                FixRunRow.Reason.PATCH_OVER_LIMIT,
                FixPatch.check(patch(file("a.txt", half), file("b.txt", half))).reason());
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                ".git/hooks/pre-commit",
                "sub/.GIT/config",
                ".opencode/plugin.js",
                "opencode.json",
                "nested/opencode.jsonc",
                ".cursor/rules",
                "/etc/passwd",
                "../outside.py",
                "src/../../outside.py",
                "src//double.py",
                "src/./here.py",
                "src\\windows.py",
                ""
            })
    void onePathTessaryNeverWritesRefusesTheWholePatch(String path) {
        assertEquals(
                new FixPatch.Verdict(List.of(), FixRunRow.Reason.PATCH_REFUSED),
                FixPatch.check(patch(file("src/agent.py", "ok"), file(path, "bad"))));
    }

    @Test
    void aSubmoduleOrUnknownModeIsRefused() {
        assertEquals(
                FixRunRow.Reason.PATCH_REFUSED,
                FixPatch.check(patch(file("vendor/lib", "160000", "abc"))).reason());
    }

    @ParameterizedTest
    @ValueSource(strings = {"../../etc/passwd", "/etc/passwd", "../.."})
    void aSymlinkThatLeavesTheRepositoryIsRefused(String target) {
        assertEquals(
                FixRunRow.Reason.PATCH_REFUSED,
                FixPatch.check(patch(file("docs/link", FixPatch.SYMLINK, target)))
                        .reason());
    }

    @Test
    void aSymlinkAtTheRootThatStaysInsideIsKept() {
        PatchFile link = file("current", FixPatch.SYMLINK, "docs/v2");

        assertEquals(new FixPatch.Verdict(List.of(link), null), FixPatch.check(patch(link)));
    }
}
