// SPDX-License-Identifier: Apache-2.0
/*
 * A span payload is attacker-shaped text: whatever the traced agent sent or received. The viewer
 * renders images and PDFs out of it, so the rules below are the XSS guard, not presentation:
 *   - an image renders only from https:// or a data: URI of png/jpeg/gif/webp (never SVG, which can
 *     carry script, and never http:, javascript:, or a relative URL);
 *   - base64 renders only when it is actually base64, so a producer's "[image … base64 …]" prose is
 *     never passed off as a picture;
 *   - a PDF is a link that opens in its own tab with no opener, never an inline frame.
 * Anything recognised as media but refused is a chip, so the reader knows something was there.
 *
 * Beside it: which renderer a payload gets (conversation, JSON tree, or markdown), the Raw toggle,
 * and copy.
 */
import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { MediaResolverContext, PayloadBody, PayloadViewer } from "./PayloadViewer";

afterEach(cleanup);

const PNG_B64 = "iVBORw0KGgo=";

function renderBody(payload: string | null, resolve?: (id: string) => string) {
  return render(
    <MediaResolverContext.Provider value={resolve ?? null}>
      <PayloadBody payload={payload} />
    </MediaResolverContext.Provider>,
  );
}

/** What one message carrying `block` renders as media: an image src, a PDF href, or a chip label. */
function mediaOf(block: object, resolve?: (id: string) => string): string[] {
  renderBody(JSON.stringify({ role: "user", content: [{ type: "text", text: "see attached" }, block] }), resolve);
  const out = [
    ...screen.queryAllByRole("img").map((i) => `img ${i.getAttribute("src")}`),
    ...screen.queryAllByRole("link").map((a) => `pdf ${a.getAttribute("href")}`),
    ...[...document.querySelectorAll("span.uppercase")].map((c) => `chip ${c.textContent}`),
  ];
  cleanup();
  return out;
}

describe("media in a message", () => {
  it.each([
    ["an https image_url object", { type: "image_url", image_url: { url: "https://cdn.example/a.png" } }, ["img https://cdn.example/a.png"]],
    ["an https image_url string", { type: "image_url", image_url: "https://cdn.example/b.png" }, ["img https://cdn.example/b.png"]],
    ["the platform's own image_url", { type: "image_url", url: "https://cdn.example/c.png" }, ["img https://cdn.example/c.png"]],
    ["a png data URI", { type: "image_url", image_url: { url: `data:image/png;base64,${PNG_B64}` } }, [`img data:image/png;base64,${PNG_B64}`]],
    ["an http image", { type: "image_url", image_url: { url: "http://intranet/a.png" } }, ["chip unsupported image"]],
    ["a javascript: image", { type: "image_url", image_url: "javascript:alert(1)" }, ["chip unsupported image"]],
    ["an SVG data URI", { type: "image_url", image_url: { url: "data:image/svg+xml;base64,PHN2Zz4=" } }, ["chip unsupported image"]],
    ["a non-string url", { type: "input_image", image_url: 7 }, ["chip unsupported image"]],
    ["an OpenAI input_image", { type: "input_image", image_url: "https://cdn.example/d.png" }, ["img https://cdn.example/d.png"]],
    ["an output_image by url", { type: "output_image", url: "https://cdn.example/e.png" }, ["img https://cdn.example/e.png"]],
    ["base64 bytes", { type: "image_b64", data: `${PNG_B64}\n`, mediaType: "image/png" }, [`img data:image/png;base64,${PNG_B64}`]],
    ["base64 with no media type", { type: "image_b64", data: PNG_B64 }, [`img data:image/png;base64,${PNG_B64}`]],
    ["prose where the bytes were", { type: "image_b64", data: "[image image/jpeg 149908 chars base64]", mediaType: "image/jpeg" }, ["chip unsupported image"]],
    ["only whitespace", { type: "image_b64", data: "  \n", mediaType: "image/png" }, ["chip unsupported image"]],
    ["base64 SVG", { type: "image_b64", data: "PHN2Zz4=", mediaType: "image/svg+xml" }, ["chip unsupported image"]],
    ["empty base64", { type: "image_b64", data: "" }, ["chip unsupported image"]],
    ["an Anthropic base64 image", { type: "image", source: { type: "base64", media_type: "image/webp", data: PNG_B64 } }, [`img data:image/webp;base64,${PNG_B64}`]],
    ["an Anthropic url image", { type: "image", source: { type: "url", url: "https://cdn.example/f.png" } }, ["img https://cdn.example/f.png"]],
    ["an Anthropic image with a bad mime", { type: "image", source: { type: "base64", media_type: "text/html", data: PNG_B64 } }, ["chip unsupported image"]],
    ["an Anthropic image of another source kind", { type: "image", source: { type: "file", file_id: "f1" } }, []],
    ["an Anthropic image with no source", { type: "image", source: "x" }, []],
    ["a stored image with a resolver", { type: "image_ref", data: "m-1" }, ["img /media/m-1"]],
    ["a PDF by url", { type: "document_url", url: "https://cdn.example/a.pdf" }, ["pdf https://cdn.example/a.pdf"]],
    ["a PDF data URI", { type: "document_url", url: "data:application/pdf;base64,JVBE" }, ["pdf data:application/pdf;base64,JVBE"]],
    ["an html data URI as a document", { type: "document_url", url: "data:text/html;base64,PGI+" }, ["chip unsupported document"]],
    ["a javascript: document", { type: "document_url", url: "javascript:alert(1)" }, ["chip unsupported document"]],
    ["a non-string document url", { type: "document_url", url: null }, ["chip unsupported document"]],
    ["PDF bytes", { type: "document_b64", data: "JVBE", mediaType: "application/pdf" }, ["pdf data:application/pdf;base64,JVBE"]],
    ["PDF bytes with no media type", { type: "document_b64", data: "JVBE" }, ["pdf data:application/pdf;base64,JVBE"]],
    ["a zip as document bytes", { type: "document_b64", data: "UEsD", mediaType: "application/zip" }, ["chip unsupported document"]],
    ["prose as document bytes", { type: "document_b64", data: "not bytes!", mediaType: "application/pdf" }, ["chip unsupported document"]],
    ["only whitespace as document bytes", { type: "document_b64", data: " ", mediaType: "application/pdf" }, ["chip unsupported document"]],
    ["empty document bytes", { type: "document_b64", data: "" }, ["chip unsupported document"]],
    ["an Anthropic base64 document", { type: "document", source: { type: "base64", media_type: "application/pdf", data: "JVBE" } }, ["pdf data:application/pdf;base64,JVBE"]],
    ["an Anthropic url document", { type: "document", source: { type: "url", url: "https://cdn.example/b.pdf" } }, ["pdf https://cdn.example/b.pdf"]],
    ["an Anthropic document with a bad mime", { type: "document", source: { type: "base64", media_type: "text/plain", data: "aGk=" } }, ["chip unsupported document"]],
    ["an Anthropic document of another source kind", { type: "document", source: { type: "text", data: "hi" } }, []],
    ["an Anthropic document with no source", { type: "document" }, []],
    ["an OpenAI input_file", { type: "input_file", file_data: "data:application/pdf;base64,JVBE" }, ["pdf data:application/pdf;base64,JVBE"]],
    ["an OpenAI file by file_url", { type: "file", file_url: "https://cdn.example/c.pdf" }, ["pdf https://cdn.example/c.pdf"]],
    ["an OpenAI file by url", { type: "file", url: "http://intranet/c.pdf", file_data: "not a data uri" }, ["chip unsupported document"]],
    ["a stored document with a resolver", { type: "document_ref", data: "m-2" }, ["pdf /media/m-2"]],
  ])("%s", (_name, block, expected) => {
    expect(mediaOf(block, (id) => `/media/${id}`)).toEqual(expected);
  });

  it("names a stored image or document it cannot reach instead of drawing a broken one", () => {
    expect(mediaOf({ type: "image_ref", data: "m-1" })).toEqual(["chip stored image"]);
    expect(mediaOf({ type: "document_ref", data: "m-2" })).toEqual(["chip stored document"]);
    expect(mediaOf({ type: "image_ref", data: 42 }, (id) => `/media/${id}`)).toEqual(["chip stored image"]);
    expect(mediaOf({ type: "document_ref" }, (id) => `/media/${id}`)).toEqual(["chip stored document"]);
  });

  it("opens a PDF in its own tab with no opener", () => {
    renderBody(JSON.stringify([{ type: "document_url", url: "https://cdn.example/a.pdf" }]));
    const link = screen.getByRole("link", { name: /View PDF/ });
    expect(link.getAttribute("target")).toBe("_blank");
    expect(link.getAttribute("rel")).toBe("noopener noreferrer");
  });

  it("shows a message that is only media as the media, and keeps the tree when any of it failed", () => {
    renderBody(JSON.stringify({ role: "user", content: [{ type: "image_url", image_url: "https://cdn.example/a.png" }] }));
    expect(screen.getByRole("img")).toBeTruthy();
    expect(screen.queryByText(/content/)).toBeNull();
    cleanup();

    renderBody(JSON.stringify({ role: "user", content: [{ type: "image_ref", data: "m-9" }] }));
    expect(screen.getByText("stored image")).toBeTruthy();
    expect(screen.getByText(/content/)).toBeTruthy();
  });

  it("enlarges an image on click and shrinks it again", () => {
    renderBody(JSON.stringify([{ type: "image_url", url: "https://cdn.example/a.png" }]));
    const toggle = screen.getByTitle("Expand image");
    fireEvent.click(toggle);
    expect(screen.getByTitle("Shrink image")).toBeTruthy();
    fireEvent.click(screen.getByTitle("Shrink image"));
    expect(screen.getByTitle("Expand image")).toBeTruthy();
  });
});

describe("which renderer a payload gets", () => {
  it("renders prose as markdown", () => {
    renderBody("# Refund policy\n\nSee **section 4**.");
    expect(screen.getByRole("heading", { name: "Refund policy" })).toBeTruthy();
  });

  it("renders text that only looks like JSON as the text it is", () => {
    renderBody("{not json at all");
    expect(screen.getByText("{not json at all")).toBeTruthy();
  });

  it("renders a JSON value with no chat shape as a tree, and a scalar array's values", () => {
    renderBody(JSON.stringify({ order_id: "ord-77", total: 12 }));
    expect(screen.getByText(/order_id/)).toBeTruthy();
    cleanup();

    renderBody(JSON.stringify([1, 2, 3]));
    expect(screen.queryByRole("heading")).toBeNull();
    expect(screen.getByText("3")).toBeTruthy();
  });

  it("renders every message of a conversation", () => {
    renderBody(JSON.stringify([
      { role: "system", content: "be brief" },
      { role: "user", content: "where is my refund" },
      "stray string",
    ]));
    expect(screen.getByText(/be brief/)).toBeTruthy();
    expect(screen.getByText(/where is my refund/)).toBeTruthy();
    expect(screen.queryByText(/stray string/)).toBeNull();
  });

  it("reads a bare content block, or a list of them, as one message", () => {
    renderBody(JSON.stringify({ type: "image_url", url: "https://cdn.example/a.png" }));
    expect(screen.getByRole("img")).toBeTruthy();
    cleanup();

    renderBody(JSON.stringify({ role: 3, content: [{ type: "image_url", url: "https://cdn.example/b.png" }] }));
    expect(screen.getByRole("img").getAttribute("src")).toBe("https://cdn.example/b.png");
  });

  it("says (none) for a missing or empty payload", () => {
    renderBody(null);
    expect(screen.getByText("(none)")).toBeTruthy();
    cleanup();
    renderBody("");
    expect(screen.getByText("(none)")).toBeTruthy();
  });
});

describe("PayloadViewer", () => {
  it("shows the payload verbatim under Raw, and formatted again after", () => {
    const payload = "# Title\n\nbody";
    render(<PayloadViewer payload={payload} label="Input" />);
    expect(screen.getByRole("heading", { name: "Title" })).toBeTruthy();

    fireEvent.click(screen.getByRole("button", { name: "Raw" }));
    expect(screen.getByText((_, el) => el?.tagName === "PRE" && el.textContent === payload)).toBeTruthy();
    expect(screen.queryByRole("heading")).toBeNull();

    fireEvent.click(screen.getByRole("button", { name: "Formatted" }));
    expect(screen.getByRole("heading", { name: "Title" })).toBeTruthy();
  });

  it("copies the raw payload, not the rendered text", async () => {
    const writeText = vi.fn(async () => {});
    Object.defineProperty(navigator, "clipboard", { value: { writeText }, configurable: true });
    render(<PayloadViewer payload={'{"a":1}'} label="Output" />);

    fireEvent.click(screen.getByRole("button", { name: "Copy" }));

    expect(await screen.findByRole("button", { name: "Copied" })).toBeTruthy();
    expect(writeText).toHaveBeenCalledWith('{"a":1}');
  });
});
