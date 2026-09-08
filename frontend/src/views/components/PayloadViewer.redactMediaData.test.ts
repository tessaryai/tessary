// SPDX-License-Identifier: Apache-2.0
/*
 * redactMediaData had no case for image_ref/document_ref (#985/#987) — those blocks fell through to
 * the generic per-field recursion, which copies string fields (including a document_ref's `text`)
 * through unchanged. MediaExternalizer caps extracted PDF text at 200,000 chars and persists it into
 * document_ref.text, so any trace message mixing text with one document rendered that entire blob
 * inline in the JSON tree — exactly the noise/weight problem this redaction exists to prevent. This
 * pins the fix: a two-case elision mirroring the existing image_b64/document_b64 pattern.
 */
import { describe, expect, it } from "vitest";
import { redactMediaData } from "./PayloadViewer";

describe("redactMediaData", () => {
  it("elides a document_ref's extracted text, keeping the media id and type intact", () => {
    const longText = "x".repeat(200_000);
    const raw = {
      role: "user",
      content: [
        { type: "text", text: "please review the attached PDF" },
        { type: "document_ref", data: "media-abc123", mediaType: "application/pdf", text: longText },
      ],
    };

    const redacted = redactMediaData(raw) as typeof raw;

    const docPart = redacted.content[1] as Record<string, unknown>;
    expect(docPart.text).not.toBe(longText);
    expect(docPart.text).toMatch(/^<document data elided/);
    expect(docPart.data).toBe("media-abc123");
    expect(docPart.mediaType).toBe("application/pdf");
    // The sibling text part and everything else must render unchanged.
    expect((redacted.content[0] as Record<string, unknown>).text).toBe("please review the attached PDF");
  });

  it("leaves an image_ref with no text field unchanged", () => {
    const raw = { type: "image_ref", data: "media-xyz", mediaType: "image/png" };

    expect(redactMediaData(raw)).toEqual(raw);
  });

  it("is a no-op for a document_ref with blank text", () => {
    const raw = { type: "document_ref", data: "media-abc", mediaType: "application/pdf", text: "" };

    expect(redactMediaData(raw)).toEqual(raw);
  });
});
