// SPDX-License-Identifier: Apache-2.0
/*
 * The playground runs one version, as edited, on one of the org's own provider keys. The bugs worth catching are a
 * provider offered that cannot run it, a decision model offered as a chat model, a request that sends the stored
 * template instead of the edit or forwards config keys the server refuses, a placeholder sent with no messages, a
 * result whose cost or price source is misread, a missing key with no way to add one, and a saved version that loses
 * the edit.
 */
import { fireEvent, screen, waitFor } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import {
  ApiError, type CatalogEntry, type PlatformDescriptor, type PlaygroundRunView, type PromptBody, type PromptConfig,
  type PromptType, type ProviderCredentialView, type ModelProvider,
} from "../../api/types";
import { api, orgApi } from "../../test/api";
import { promptDetail } from "../../test/fixtures";
import { pending, renderRoute } from "../../test/render";
import { PlaygroundPanel } from "./PlaygroundPanel";

vi.mock("../../tenant/TenantContext", () => import("../../test/api"));

const BODY: PromptBody = [{ role: "system", content: "You are {{tone}}." }, { type: "placeholder", name: "history" }];
const CONFIG: PromptConfig = { temperature: 0.2, maxTokens: 100, topP: 0.9, stop: ["END"], response_format: { type: "json" }, seed: 7 };

const platform = (id: ModelProvider, label: string): PlatformDescriptor => ({
  id, label, auth: "api_key", default_base_url: "", detail: null, supports_base_url: false, used_by: [],
});

const model = (provider: ModelProvider, model_name: string, display_name: string, decision = false): CatalogEntry => ({
  provider, model_name, display_name, decision, agentic: false, default_base_url: "", vendor: "",
});

const credential = (provider: ModelProvider, over: Partial<ProviderCredentialView> = {}): ProviderCredentialView => ({
  id: `cred-${provider}`, provider, has_api_key: true, auth_mode: "api_key", aws_region: "", base_url_override: "",
  bedrock_model_arn: "", created_at: "2026-10-01T00:00:00Z", custom_model_name: "", has_aws_credentials: false,
  updated_at: "2026-10-01T00:00:00Z", ...over,
});

const CATALOG = {
  platforms: [
    platform("OPENAI", "OpenAI"), platform("ANTHROPIC", "Anthropic"), platform("BEDROCK", "Bedrock"),
    platform("GEMINI", "Gemini"), platform("GROK", "Grok"), platform("CUSTOM", "Custom"),
  ],
  models: [
    model("OPENAI", "chat-large", "Chat Large"), model("OPENAI", "decide-latest", "Decide Latest", true),
    model("OPENAI", "chat-small", "Chat Small"), model("ANTHROPIC", "messages-mid", "Messages Mid"),
    model("BEDROCK", "bedrock-chat", "Bedrock Chat"),
  ],
};

const CREDENTIALS = [
  credential("OPENAI"), credential("ANTHROPIC"), credential("BEDROCK"), credential("GEMINI", { has_api_key: false }),
  credential("GROK"), credential("CUSTOM", { custom_model_name: "house-model" }),
];

function serve(credentials: ProviderCredentialView[] = CREDENTIALS) {
  orgApi.listProviderCatalog.mockResolvedValue(CATALOG);
  orgApi.listProviderCredentials.mockResolvedValue({ credentials });
  api.getPrompt.mockResolvedValue(promptDetail());
}

function renderPanel({ type = "chat", body = BODY, config = CONFIG }: { type?: PromptType; body?: PromptBody; config?: PromptConfig } = {}) {
  return renderRoute(<PlaygroundPanel promptId="p-1" version={2} type={type} body={body} config={config} />, {
    route: "/orgs/acme/projects/default/prompts/p-1?tab=playground",
  });
}

function result(over: Partial<PlaygroundRunView> = {}): PlaygroundRunView {
  return {
    content: "Hello there.", finish_reason: "stop", input_tokens: 118, output_tokens: 41, cache_read_tokens: null,
    cache_write_tokens: null, latency_ms: 1_800, cost_usd: 0.0003, price_source: "openrouter", provider: "OPENROUTER",
    model: "chat-large", ...over,
  };
}

const combo = (name: string) => screen.getByRole("combobox", { name }) as HTMLSelectElement;
const options = (name: string) => Array.from(combo(name).options).map((o) => o.textContent);
const field = (name: string) => screen.getByLabelText(name) as HTMLInputElement;
const type = (name: string, value: string) => fireEvent.change(screen.getByLabelText(name), { target: { value } });
const run = () => fireEvent.click(screen.getByRole("button", { name: "Run" }));
const sent = () => api.runPlayground.mock.calls[0][0];

describe("reading the org's providers", () => {
  // Bug: the panel offers the empty state, or crashes, while either read is still in flight.
  it.each([
    ["both reads", () => { orgApi.listProviderCatalog.mockReturnValue(pending()); orgApi.listProviderCredentials.mockReturnValue(pending()); }],
    ["the key read", () => { orgApi.listProviderCatalog.mockResolvedValue(CATALOG); orgApi.listProviderCredentials.mockReturnValue(pending()); }],
  ])("shows loading while %s is pending", async (_name, stub) => {
    stub();
    renderPanel();

    expect(await screen.findByText("Loading providers…")).toBeTruthy();
    expect(screen.queryByRole("button", { name: "Run" })).toBeNull();
  });

  // Bug: a failed read reads as "no provider keys" and sends the person to add a key they already have.
  it.each([
    ["catalog", () => { orgApi.listProviderCatalog.mockRejectedValue(new ApiError(500, { code: "COMMON.INTERNAL", message: "Catalog failed" })); orgApi.listProviderCredentials.mockResolvedValue({ credentials: CREDENTIALS }); }, "COMMON.INTERNAL: Catalog failed"],
    ["key list", () => { orgApi.listProviderCatalog.mockResolvedValue(CATALOG); orgApi.listProviderCredentials.mockRejectedValue(new ApiError(403, { code: "AUTH.FORBIDDEN", message: "Keys hidden" })); }, "AUTH.FORBIDDEN: Keys hidden"],
  ])("shows the %s error", async (_name, stub, text) => {
    stub();
    renderPanel();

    expect((await screen.findByRole("alert")).textContent).toBe(text);
    expect(screen.queryByText("Add a provider key to run the playground")).toBeNull();
  });

  // Bug: a Bedrock key, or a provider row with no API key, is offered and every run fails.
  it("says to add a key when no key can run the playground, and offers no run", async () => {
    serve([credential("BEDROCK"), credential("OPENAI", { has_api_key: false })]);
    renderPanel();

    expect(await screen.findByText("Add a provider key to run the playground")).toBeTruthy();
    expect(
      screen.getByText("The playground runs on this organization's own provider keys. Bedrock and TypeSafe keys cannot run it."),
    ).toBeTruthy();
    expect(screen.getByRole("link", { name: "Add a provider key" }).getAttribute("href")).toBe(
      "/orgs/acme/projects/default/settings/providers",
    );
    expect(screen.queryByRole("button", { name: "Run" })).toBeNull();
  });
});

describe("picking a provider and model", () => {
  // Bug: Bedrock or a keyless provider offered, a decision model offered for chat, or the old model kept on a switch.
  it("offers keyed chat providers in catalog order and their chat models", async () => {
    serve();
    renderPanel();

    await screen.findByRole("button", { name: "Run" });
    expect(options("Provider")).toEqual(["OpenAI", "Anthropic", "Grok", "Custom"]);
    expect(options("Model")).toEqual(["Chat Large", "Chat Small"]);
    expect(combo("Model").value).toBe("chat-large");

    fireEvent.change(combo("Model"), { target: { value: "chat-small" } });
    fireEvent.change(combo("Provider"), { target: { value: "ANTHROPIC" } });

    expect(options("Model")).toEqual(["Messages Mid"]);
    expect(combo("Model").value).toBe("messages-mid");
  });

  // Bug: a Custom run sends a catalog model its endpoint does not serve.
  it("runs Custom on the credential's own model name", async () => {
    serve();
    api.runPlayground.mockResolvedValue(result());
    renderPanel();
    await screen.findByRole("button", { name: "Run" });

    fireEvent.change(combo("Provider"), { target: { value: "CUSTOM" } });
    expect(options("Model")).toEqual(["house-model"]);
    expect(combo("Model").disabled).toBe(true);
    run();

    await waitFor(() => expect(api.runPlayground).toHaveBeenCalled());
    expect(sent().provider).toBe("CUSTOM");
    expect(sent().model).toBe("house-model");
  });

  // Bug: a provider with no chat model in the catalog sends an empty model name.
  it("cannot run on a provider with no chat model", async () => {
    serve();
    renderPanel();
    await screen.findByRole("button", { name: "Run" });

    fireEvent.change(combo("Provider"), { target: { value: "GROK" } });

    expect(options("Model")).toEqual([]);
    expect((screen.getByRole("button", { name: "Run" }) as HTMLButtonElement).disabled).toBe(true);
  });
});

describe("running", () => {
  // Bug: the stored template sent instead of the edit, a camelCase key sent the server refuses, or no prompt_ref.
  it("sends the edited template, compiled, with only the four config keys and the version it ran", async () => {
    serve();
    api.runPlayground.mockResolvedValue(result());
    renderPanel();
    await screen.findByRole("button", { name: "Run" });

    expect(screen.getByText("Not forwarded by the playground: response_format, seed")).toBeTruthy();
    expect(field("Temperature").value).toBe("0.2");
    expect(field("Max tokens").value).toBe("100");
    expect(field("Top p").value).toBe("0.9");
    expect(field("Stop sequences").value).toBe("END");
    type("Content of message 1", "You are {{tone}} today.");
    type("tone", "kind");
    type("Messages for history", '[{"role":"user","content":"Hi","name":"ann"}]');
    type("Temperature", "0.5");
    type("Stop sequences", "END, STOP");
    run();

    await waitFor(() => expect(api.runPlayground).toHaveBeenCalledTimes(1));
    expect(sent()).toEqual({
      provider: "OPENAI",
      model: "chat-large",
      messages: [{ role: "system", content: "You are kind today." }, { role: "user", content: "Hi" }],
      config: { temperature: 0.5, max_tokens: 100, top_p: 0.9, stop: ["END", "STOP"] },
      prompt_ref: { prompt_id: "p-1", version: 2 },
    });
  });

  // Bug: a placeholder with no messages, or with text that is not a message list, reaches the server as a message.
  it.each([
    ["left empty", null],
    ["not JSON", "hello"],
    ["not a list", '{"role":"user","content":"Hi"}'],
    // Bug: a list of non-objects passed the array check and threw inside Run, so the click did nothing and said nothing.
    ["a list of strings", '["hello"]'],
    ["a list holding a message with no content", '[{"role":"user"}]'],
  ])("leaves out a placeholder %s and says so", async (_name, text) => {
    serve();
    api.runPlayground.mockResolvedValue(result());
    renderPanel();
    await screen.findByRole("button", { name: "Run" });
    if (text !== null) type("Messages for history", text);

    expect(screen.getByText("The run leaves out history: fill it with a JSON array of {role, content} messages.")).toBeTruthy();
    run();

    await waitFor(() => expect(api.runPlayground).toHaveBeenCalled());
    expect(sent().messages).toEqual([{ role: "system", content: "You are {{tone}}." }]);
  });

  // Bug: a message spelled with type "message", which the server accepts in a body, was read as a placeholder and
  // silently left out of the run.
  it("sends a placeholder message that carries type message", async () => {
    serve();
    api.runPlayground.mockResolvedValue(result());
    renderPanel();
    await screen.findByRole("button", { name: "Run" });
    type("Messages for history", '[{"type":"message","role":"user","content":"Hi"}]');

    expect(screen.queryByText(/The run leaves out/)).toBeNull();
    run();

    await waitFor(() => expect(api.runPlayground).toHaveBeenCalled());
    expect(sent().messages).toEqual([{ role: "system", content: "You are {{tone}}." }, { role: "user", content: "Hi" }]);
  });

  // Bug: an untouched Stop sequences field rewrote the version's stops. They were joined with ", ", then split on ","
  // and trimmed, so a stop led by newlines lost them and a stop holding a comma became two.
  it("sends and saves the version's stops unchanged when the field is not edited", async () => {
    const stop = ["\n\nHuman:", "a,b"];
    serve();
    api.runPlayground.mockResolvedValue(result());
    renderPanel({ type: "text", body: "Hi", config: { stop } });
    await screen.findByRole("button", { name: "Run" });

    run();
    await waitFor(() => expect(api.runPlayground).toHaveBeenCalled());
    expect(sent().config).toEqual({ stop });

    fireEvent.click(screen.getByRole("button", { name: "Save as new version" }));
    expect(await screen.findByText("New version of support/greeting")).toBeTruthy();
    expect((screen.getByRole("textbox", { name: "Config JSON" }) as HTMLTextAreaElement).value).toBe(
      JSON.stringify({ stop }, null, 2),
    );
  });

  // Bug: a text prompt sent as a bare string, or empty settings sent as keys the server then validates.
  it("sends a text prompt as one user message and leaves out empty settings", async () => {
    serve();
    api.runPlayground.mockResolvedValue(result());
    renderPanel({ type: "text", body: "Summarize {{doc}}", config: { stop: "END" } });
    await screen.findByRole("button", { name: "Run" });

    expect(field("Temperature").value).toBe("");
    expect(field("Stop sequences").value).toBe("END");
    expect(screen.queryByText(/Not forwarded by the playground/)).toBeNull();
    expect(screen.queryByText(/The run leaves out/)).toBeNull();
    type("doc", "the ticket");
    type("Stop sequences", " , ");
    run();

    await waitFor(() => expect(api.runPlayground).toHaveBeenCalled());
    expect(sent().messages).toEqual([{ role: "user", content: "Summarize the ticket" }]);
    expect(sent().config).toEqual({});
  });

  // Bug: a blank message reaches the provider instead of being caught in the editor.
  it("checks the template before it runs", async () => {
    serve();
    renderPanel();
    await screen.findByRole("button", { name: "Run" });

    type("Content of message 1", "  ");
    run();

    expect(await screen.findByText("Enter the message content.")).toBeTruthy();
    expect(api.runPlayground).not.toHaveBeenCalled();
  });
});

describe("the result", () => {
  // Bug: the cost printed in cents and rounded to $0.00, or the price source misnamed.
  it.each([
    [result(), "stop · 118 in · 41 out · 1.8 s · $0.0003 · OpenRouter"],
    [result({ cost_usd: 2.5, price_source: "book", latency_ms: 400 }), "stop · 118 in · 41 out · 0.4 s · $2.50 · Price book"],
    [
      result({ finish_reason: null, input_tokens: null, output_tokens: null, cost_usd: null, price_source: "unpriced", latency_ms: 400 }),
      "0.4 s · Unpriced",
    ],
  ])("shows the answer and its meta row", async (view, meta) => {
    serve();
    api.runPlayground.mockResolvedValue(view);
    renderPanel();
    await screen.findByRole("button", { name: "Run" });

    run();

    expect(await screen.findByText("Hello there.")).toBeTruthy();
    expect(screen.getByText(meta)).toBeTruthy();
  });

  // Bug: a provider refusal shown as a generic failure, or sent to the providers page it has nothing to do with.
  it("shows a refusal with its code", async () => {
    serve();
    api.runPlayground.mockRejectedValue(
      new ApiError(422, { code: "PLAYGROUND.REQUEST_REFUSED", message: "Provider OPENAI refused the request (HTTP 400): bad" }),
    );
    renderPanel();
    await screen.findByRole("button", { name: "Run" });

    run();

    expect((await screen.findByRole("alert")).textContent).toBe(
      "PLAYGROUND.REQUEST_REFUSED: Provider OPENAI refused the request (HTTP 400): bad",
    );
    expect(screen.queryByRole("link", { name: "Add a provider key" })).toBeNull();
  });

  // Bug: a missing key leaves the person with an error and no way to add the key.
  it("links a missing key to the providers settings", async () => {
    serve();
    api.runPlayground.mockRejectedValue(
      new ApiError(412, { code: "MODEL_CONFIG.MISSING_CREDENTIALS", message: "Provider OPENAI has no credentials" }),
    );
    renderPanel();
    await screen.findByRole("button", { name: "Run" });

    run();

    expect((await screen.findByRole("alert")).textContent).toBe("MODEL_CONFIG.MISSING_CREDENTIALS: Provider OPENAI has no credentials");
    expect(screen.getByRole("link", { name: "Add a provider key" }).getAttribute("href")).toBe(
      "/orgs/acme/projects/default/settings/providers",
    );
  });
});

// Bug: the saved version drops the edit, keeps a camelCase key beside its snake_case twin, or loses unforwarded keys.
it("saves the edited template and settings as a new version", async () => {
  serve();
  renderPanel();
  await screen.findByRole("button", { name: "Run" });
  type("Content of message 1", "You are {{tone}} today.");
  type("Temperature", "0.5");
  type("Top p", "");

  fireEvent.click(screen.getByRole("button", { name: "Save as new version" }));

  expect(await screen.findByText("New version of support/greeting")).toBeTruthy();
  const contents = screen.getAllByRole("textbox", { name: "Content of message 1" }) as HTMLTextAreaElement[];
  expect(contents.map((c) => c.value)).toEqual(["You are {{tone}} today.", "You are {{tone}} today."]);
  expect((screen.getByRole("textbox", { name: "Config JSON" }) as HTMLTextAreaElement).value).toBe(
    JSON.stringify({ response_format: { type: "json" }, seed: 7, temperature: 0.5, max_tokens: 100, stop: ["END"] }, null, 2),
  );
  fireEvent.click(screen.getByRole("button", { name: "Cancel" }));
  expect(screen.queryByText("New version of support/greeting")).toBeNull();
});

// Bug: the footer promises the run is traced, or hides that it spends the org's money.
it("says where the run is billed and that it is not recorded", async () => {
  serve();
  renderPanel();

  expect(
    await screen.findByText(
      "Runs use this organization's provider key and count toward its model spend. They are not recorded as traces and do not count in usage.",
    ),
  ).toBeTruthy();
});
