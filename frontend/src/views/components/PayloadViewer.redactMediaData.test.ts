// SPDX-License-Identifier: Apache-2.0
/*
 * redactMediaData had no case for image_ref/document_ref — those blocks fell through to
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

  it("elides an OpenAI input_image or output_image data URI under either field, keeping an https url", () => {
    const dataUri = `data:image/png;base64,${"A".repeat(4096)}`;

    expect(redactMediaData({ type: "input_image", image_url: dataUri })).toEqual({
      type: "input_image",
      image_url: "<image data elided, 4.0 KB>",
    });
    expect(redactMediaData({ type: "output_image", url: dataUri, image_url: "https://cdn.example/a.png" })).toEqual({
      type: "output_image",
      url: "<image data elided, 4.0 KB>",
      image_url: "https://cdn.example/a.png",
    });
    const linked = { type: "input_image", image_url: "https://cdn.example/b.png" };
    expect(redactMediaData(linked)).toBe(linked);
  });

  it("elides an image_url given as a bare data URI string, and one given only on the part's url", () => {
    const dataUri = `data:image/png;base64,${"A".repeat(2048)}`;

    expect(redactMediaData({ type: "image_url", image_url: dataUri })).toEqual({
      type: "image_url",
      image_url: "<image data elided, 2.0 KB>",
    });
    expect(redactMediaData({ type: "image_url", url: dataUri })).toEqual({
      type: "image_url",
      url: "<image data elided, 2.0 KB>",
    });
  });

  it("elides a file part's inline data under each field it may carry, and leaves a linked file alone", () => {
    const dataUri = `data:application/pdf;base64,${"A".repeat(1024)}`;
    const elided = "<document data elided, 1.0 KB>";

    expect(redactMediaData({ type: "file", url: dataUri, file_url: dataUri, name: "a.pdf" })).toEqual({
      type: "file",
      url: elided,
      file_url: elided,
      name: "a.pdf",
    });
    expect(redactMediaData({ type: "input_file", file_data: dataUri })).toEqual({ type: "input_file", file_data: elided });
    const linked = { type: "input_file", file_url: "https://cdn.example/a.pdf" };
    expect(redactMediaData(linked)).toBe(linked);
  });

  it("elides a data URI in an Anthropic block's source url", () => {
    const dataUri = `data:image/png;base64,${"A".repeat(1024)}`;

    expect(redactMediaData({ type: "image", source: { type: "url", url: dataUri } })).toEqual({
      type: "image",
      source: { type: "url", url: "<image data elided, 1.0 KB>" },
    });
  });
});
