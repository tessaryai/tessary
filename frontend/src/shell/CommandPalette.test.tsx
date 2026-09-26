// SPDX-License-Identifier: Apache-2.0
/*
 * The ⌘K palette offers exactly what the org can reach (the sidebar's surfaces and the settings
 * sections its capabilities allow, never a gated one, even from a stale recent), runs the highlighted
 * command from the keyboard, and merges server search results in without letting a slow earlier
 * response overwrite a newer one. It is never a dead end: no match still offers a way out.
 */
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from "vitest";
import { act, cleanup, fireEvent, screen, waitFor, within } from "@testing-library/react";
import { useEffect } from "react";
import type { SearchResults } from "../api/types";
import { currentLocation, renderRoute } from "../test/render";
import { CommandPaletteDock } from "./CommandPalette";
import { PaletteProvider } from "./PaletteContext";
import { ShellActionsProvider, useShellActions } from "./ShellActions";
import { pushRecent } from "./recents";

const mocks = vi.hoisted(() => ({
  search: vi.fn(),
  getCapabilities: vi.fn(),
  openSwitcher: vi.fn(),
}));

vi.mock("../tenant/TenantContext", () => ({
  useTenant: () => ({ orgSlug: "acme", projectSlug: "web" }),
}));

vi.mock("../api/client", async (importOriginal) => {
  const actual = await importOriginal<typeof import("../api/client")>();
  return {
    ...actual,
    auth: { ...actual.auth, getCapabilities: mocks.getCapabilities },
    projectApi: () => ({ search: mocks.search }),
  };
});

beforeAll(() => {
  HTMLDialogElement.prototype.showModal = function (this: HTMLDialogElement) {
    this.setAttribute("open", "");
  };
});

beforeEach(() => {
  mocks.getCapabilities.mockResolvedValue({ capabilities: { api_access_enabled: false, alerts_enabled: false } });
  mocks.search.mockResolvedValue({ hits: [] } satisfies Partial<SearchResults>);
});

afterEach(() => {
  cleanup();
  vi.clearAllMocks();
  window.localStorage.clear();
});

function RegistersSwitcher() {
  const { registerProjectSwitcher } = useShellActions();
  useEffect(() => registerProjectSwitcher(mocks.openSwitcher), [registerProjectSwitcher]);
  return <input aria-label="some other field" />;
}

const BASE = "/orgs/acme/projects/web/";

function renderPalette() {
  renderRoute(
    <ShellActionsProvider>
      <PaletteProvider>
        <RegistersSwitcher />
        <CommandPaletteDock />
      </PaletteProvider>
    </ShellActionsProvider>,
    { route: `${BASE}triage` },
  );
}

function openPalette() {
  fireEvent.keyDown(window, { key: "k", metaKey: true });
  return screen.getByRole("textbox", { name: "Search commands" });
}

const labelsIn = (group: string) => {
  const heading = screen.getByText(group, { selector: "div" });
  return within(heading.parentElement!).getAllByRole("button").map((b) => b.textContent);
};

describe("opening", () => {
  it("toggles on ⌘K or Ctrl+K, and / opens it only outside a text field", () => {
    renderPalette();
    expect(screen.queryByRole("dialog")).toBeNull();

    fireEvent.keyDown(screen.getByRole("textbox", { name: "some other field" }), { key: "/" });
    expect(screen.queryByRole("dialog")).toBeNull();

    fireEvent.keyDown(document.body, { key: "/" });
    expect(screen.getByRole("dialog", { name: "Command palette" })).toBeTruthy();

    fireEvent.keyDown(window, { key: "K", ctrlKey: true });
    expect(screen.queryByRole("dialog")).toBeNull();
    openPalette();
    expect(screen.getByRole("dialog")).toBeTruthy();
  });

  it("closes on Escape and on a click on its backdrop", () => {
    renderPalette();
    fireEvent.keyDown(openPalette(), { key: "Escape" });
    expect(screen.queryByRole("dialog")).toBeNull();

    openPalette();
    fireEvent.click(screen.getByRole("dialog"));
    expect(screen.queryByRole("dialog")).toBeNull();

    openPalette();
    fireEvent(screen.getByRole("dialog"), new Event("cancel", { cancelable: true }));
    expect(screen.queryByRole("dialog")).toBeNull();
  });
});

describe("what it offers", () => {
  it("lists only the settings sections the org's capabilities allow, and roadmap slots as not runnable", async () => {
    renderPalette();
    openPalette();

    await waitFor(() => expect(mocks.getCapabilities).toHaveBeenCalledWith("acme"));
    expect(labelsIn("Navigation")).toEqual([
      expect.stringContaining("Triage"),
      expect.stringContaining("Traces"),
      expect.stringContaining("Classifiers"),
      expect.stringContaining("Vitals"),
    ]);
    expect(screen.queryByText("Settings · MCP tokens")).toBeNull();
    const soon = screen.getByText("Experiments").closest("button")!;
    expect((soon as HTMLButtonElement).disabled).toBe(true);
    expect(within(soon).getByText("Soon")).toBeTruthy();
  });

  it("adds a gated section once its capability is on", async () => {
    mocks.getCapabilities.mockResolvedValue({ capabilities: { api_access_enabled: true } });
    renderPalette();
    openPalette();

    expect(await screen.findByText("Settings · MCP tokens")).toBeTruthy();
  });

  it("hides a recent whose surface the org can no longer reach, and keeps a deep link", async () => {
    mocks.getCapabilities.mockResolvedValue({ capabilities: { api_access_enabled: false } });
    pushRecent("acme", "web", { path: `${BASE}settings/api-keys`, label: "Settings · API keys" });
    pushRecent("acme", "web", { path: `${BASE}settings/members`, label: "Settings · Members" });
    pushRecent("acme", "web", { path: `${BASE}traces/tr-1`, label: "Trace tr-1" });
    pushRecent("acme", "web", { path: `${BASE}settings`, label: "Settings" });
    renderPalette();
    openPalette();

    await waitFor(() => expect(mocks.getCapabilities).toHaveBeenCalled());
    await waitFor(() => expect(labelsIn("Recent")).toEqual([
      expect.stringContaining("Settings"),
      expect.stringContaining("Trace tr-1"),
      expect.stringContaining("Settings · Members"),
    ]));
  });
});

describe("running a command", () => {
  it("filters on keywords, runs the highlighted command on Enter, and remembers it as recent", async () => {
    renderPalette();
    const box = openPalette();

    fireEvent.change(box, { target: { value: "p95" } });
    fireEvent.keyDown(box, { key: "Enter" });

    expect(currentLocation()).toBe(`${BASE}vitals`);
    expect(screen.queryByRole("dialog")).toBeNull();
    openPalette();
    expect(labelsIn("Recent")).toEqual([expect.stringContaining("Vitals")]);
  });

  it("moves the highlight with the arrows, staying inside the list", () => {
    renderPalette();
    const box = openPalette();
    fireEvent.change(box, { target: { value: "settings · m" } });

    fireEvent.keyDown(box, { key: "ArrowUp" });
    fireEvent.keyDown(box, { key: "ArrowDown" });
    fireEvent.keyDown(box, { key: "ArrowDown" });
    fireEvent.keyDown(box, { key: "ArrowDown" });
    fireEvent.keyDown(box, { key: "Enter" });

    expect(currentLocation()).toBe(`${BASE}settings/models`);
  });

  it("runs a settings command by click and the project switcher from Actions", () => {
    renderPalette();
    openPalette();
    fireEvent.mouseMove(screen.getByText("Settings · Members"));
    fireEvent.click(screen.getByText("Settings · Members"));
    expect(currentLocation()).toBe(`${BASE}settings/members`);

    openPalette();
    fireEvent.click(screen.getByText("Switch organization or project…"));
    expect(mocks.openSwitcher).toHaveBeenCalledTimes(1);
  });
});

describe("server search", () => {
  const hit = (id: string, title: string, snippet: string | null = null) => ({ id, title, snippet, type: "trace", score: 1 });

  it("asks once per typing pause and lists ranked results first, highlighting the terms", async () => {
    mocks.search.mockResolvedValue({ hits: [hit("tr-42", "Refund lookup failed", "order 881")] });
    renderPalette();
    const box = openPalette();

    fireEvent.change(box, { target: { value: "r" } });
    fireEvent.change(box, { target: { value: "refund" } });
    expect(screen.getAllByText("Searching…").length).toBeGreaterThan(0);

    const title = await screen.findByText("Refund");
    expect(mocks.search).toHaveBeenCalledTimes(1);
    expect(mocks.search.mock.calls[0][0]).toBe("refund");
    expect(title.className).toContain("bg-accent-subtle");
    expect(screen.getByText("order 881")).toBeTruthy();
    expect(screen.getByText("1 result")).toBeTruthy();
    expect(labelsIn("Results")).toHaveLength(1);

    fireEvent.keyDown(box, { key: "Enter" });
    expect(currentLocation()).toBe(`${BASE}traces/tr-42`);
  });

  it("never lets a slow earlier response replace a newer one", async () => {
    const replies: ((r: { hits: ReturnType<typeof hit>[] }) => void)[] = [];
    mocks.search.mockImplementation(() => new Promise((resolve) => replies.push(resolve)));
    renderPalette();
    const box = openPalette();

    fireEvent.change(box, { target: { value: "alpha" } });
    await waitFor(() => expect(replies).toHaveLength(1));
    fireEvent.change(box, { target: { value: "beta" } });
    await waitFor(() => expect(replies).toHaveLength(2));

    replies[1]({ hits: [hit("tr-b", "beta trace"), hit("tr-c", "beta two")] });
    replies[0]({ hits: [hit("tr-a", "alpha trace")] });

    expect(await screen.findByText("2 results")).toBeTruthy();
    expect(screen.queryByText(/alpha trace/)).toBeNull();
  });

  it("drops a search the next keystroke cancelled without reporting search as unavailable", async () => {
    mocks.search.mockImplementation(
      (q: string, signal: AbortSignal) =>
        new Promise((resolve, reject) => {
          if (q === "beta") resolve({ hits: [hit("tr-b", "beta trace")] });
          // A browser's AbortError is an Error; whatever the rejection, a cancelled search is not a failure.
          signal.addEventListener("abort", () => reject(new Error("The operation was aborted.")));
        }),
    );
    renderPalette();
    const box = openPalette();

    fireEvent.change(box, { target: { value: "alpha" } });
    await waitFor(() => expect(mocks.search).toHaveBeenCalledTimes(1));
    fireEvent.change(box, { target: { value: "beta" } });
    // The cancelled request rejects at once; the new one is still waiting out its debounce.
    await act(() => new Promise((resolve) => setTimeout(resolve, 0)));
    expect(screen.queryByText(/Search is unavailable/)).toBeNull();

    expect(await screen.findByText("1 result")).toBeTruthy();
  });

  it("prints a result's title plainly when the match was elsewhere in it", async () => {
    mocks.search.mockResolvedValue({ hits: [hit("tr-7", "Checkout failed", "refund requested")] });
    renderPalette();
    const box = openPalette();

    fireEvent.change(box, { target: { value: "refund" } });

    const row = (await screen.findByText("Checkout failed")).closest("button")!;
    expect(row.querySelectorAll("mark, .bg-accent-subtle")).toHaveLength(0);
  });

  it("falls back to matching pages when search is unavailable", async () => {
    mocks.search.mockRejectedValue(new Error("503"));
    renderPalette();
    const box = openPalette();

    fireEvent.change(box, { target: { value: "vitals" } });

    expect(await screen.findByText("Search is unavailable. Showing matching pages only.")).toBeTruthy();
    expect(screen.getByText("Vitals")).toBeTruthy();
    expect(screen.getByText("0 results")).toBeTruthy();
  });

  it("offers Triage and Traces when nothing at all matches", async () => {
    renderPalette();
    const box = openPalette();
    fireEvent.change(box, { target: { value: "zzzz" } });

    expect(await screen.findByText("No matches for “zzzz”")).toBeTruthy();
    fireEvent.click(screen.getByRole("button", { name: "Go to Traces" }));
    expect(currentLocation()).toBe(`${BASE}traces`);

    openPalette();
    expect(labelsIn("Recent")).toEqual([expect.stringContaining("Traces")]);
    fireEvent.change(screen.getByRole("textbox", { name: "Search commands" }), { target: { value: "qqqq" } });
    fireEvent.click(await screen.findByRole("button", { name: "Go to Triage" }));
    expect(currentLocation()).toBe(`${BASE}triage`);
  });

  it("clears the results when the query is emptied", async () => {
    mocks.search.mockResolvedValue({ hits: [hit("tr-1", "a trace")] });
    renderPalette();
    const box = openPalette();
    fireEvent.change(box, { target: { value: "trace" } });
    await screen.findByText("1 result");

    fireEvent.change(box, { target: { value: "" } });

    expect(screen.queryByText("Results", { selector: "div" })).toBeNull();
  });
});

describe("recents", () => {
  it("goes back to a recent on click", async () => {
    pushRecent("acme", "web", { path: `${BASE}traces/tr-7`, label: "Trace tr-7" });
    renderPalette();
    openPalette();

    fireEvent.click(await screen.findByText("Trace tr-7"));

    expect(currentLocation()).toBe(`${BASE}traces/tr-7`);
  });

  it("opens with no recents when what is stored is corrupt or not a list", () => {
    for (const stored of ["{not json", '{"path":"x"}']) {
      window.localStorage.setItem("tessary.recents.acme/web", stored);
      renderPalette();
      openPalette();
      expect(screen.queryByText("Recent", { selector: "div" })).toBeNull();
      expect(screen.getByText("Triage")).toBeTruthy();
      cleanup();
    }
  });
});
