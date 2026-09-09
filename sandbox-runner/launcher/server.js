// SPDX-License-Identifier: Apache-2.0
'use strict';
/*
 * Node launcher sidecar. The tessary backend (Java, no E2B SDK) calls
 * this over HTTP; we wrap the E2B SDK. One request == one FRESH microVM: create
 * a sandbox from the runner template, run run.js, tear the sandbox down. No
 * sandbox is ever reused across requests or tenants.
 *
 * Endpoints (Bearer-authed with SANDBOX_API_KEY):
 *   POST /rca      { clone_url?, head_sha?, files, prompt, json_schema, model, mcp, timeout_ms }
 *                                                    -> { raw: "<agent stdout>" }  agentic root-cause
 *                                                    analysis over the dossier + the platform's MCP
 *                                                    surface, plus ./repo/ when a clone_url is sent
 *                                                    (read-only)
 *   POST /triage   { files, prompt, json_schema, model, mcp, timeout_ms }
 *                                                    -> { raw: "<agent stdout>" }  Layer-2 ruling over
 *                                                    the finding's dossier + the platform's MCP surface.
 *                                                    The one agentic route with NO clone — see runTriage.
 *   GET  /healthz                                    -> 200
 *
 * FIVE ROUTES WERE REMOVED, and the list is worth keeping because the shape of what remains is
 * the argument for the rename: /grade and /lint ran user-authored grader code, /synthesize and
 * /codegen authored it, and /analyze served the git observer. All five went with grading and the
 * observer. What is left — /rca and /triage — is OUR agent ruling on the surviving classifier
 * product, which is why this service is no longer named for graders.
 *
 * Failure contract: every orchestration failure answers 502 with
 *   { error: 'sandbox orchestration failed',            // unchanged, always present
 *     kind: 'timeout'|'script_exit'|'bad_output'|'bad_request'|'orchestration',
 *     timeout: bool,                                    // deadline hit (see classifyFailure)
 *     error_class: string, detail: string,              // scrubbed, <=200 chars
 *     exit_code?: int, script?: string,
 *     elapsed_ms?: int, timeout_ms?: int, sandbox_id?: string }
 * `detail` is launcher-authored or scrubbed SDK metadata ONLY — never the clone URL, sandbox
 * output, model output, or repo content (see "Failure diagnostics" below).
 *
 * Encoder classification (POST /classify) is NOT served here — it lives in the
 * standalone classify-service (../../classify-service), which runs on ECS Fargate
 * in production and as the `classify` service in docker-compose.dev.yml locally.
 *
 * Env:
 *   PORT                  (default 8080)
 *   SANDBOX_API_KEY       shared secret the backend must present
 *   SANDBOX_BACKEND       'docker' (default) | 'e2b' | 'local'. Three DISTINCT isolation
 *                         guarantees, not interchangeable:
 *                           'docker' — the agentic paths (/rca, /triage) each run in a
 *                             FRESH, hardened sibling container spawned
 *                             from AGENT_IMAGE over the mounted Docker socket, one per request,
 *                             removed on completion. This is the open default: no E2B key, no
 *                             Tessary cloud credential, isolation equivalent to the E2B path.
 *                           'e2b'    — the original path: a fresh E2B microVM per request.
 *                           'local'  — agentic paths run DIRECTLY ON THIS HOST process against a
 *                             locally installed `opencode`, with NO container/VM isolation at
 *                             all. A developer convenience only (`task dev:local`) — do not
 *                             confuse with 'docker', which is the isolated option.
 *                         All three backends get the SAME provider config and the SAME model
 *                         (see agentEnvs / toProviderModel), so they cannot drift apart.
 *   E2B_API_KEY           E2B cloud key (stays here, never sent to the backend) — E2B backend only
 *   E2B_ANALYZER_TEMPLATE agent sandbox template name/id (default tessary-agent-sandbox). Renamed
 *                         from evals-observer-analyzer with the service: the published cloud
 *                         template must be rebuilt under the new alias before an e2b-backed deploy,
 *                         or Sandbox.create resolves nothing and both /rca and /triage fail on
 *                         their first call — see agent-sandbox/build.ts.
 *   SANDBOX_TIMEOUT_MS    per-sandbox wall clock (default 60000)
 *   DOCKER_SOCKET_PATH    unix socket the docker backend talks the Engine API over
 *                         (default /var/run/docker.sock — the socket docker-compose.yml mounts
 *                         read-write into this container). Docker backend only.
 *   AGENT_IMAGE            the published agent image the docker backend runs one sibling
 *                         container from per request (default
 *                         tessaryai/tessary:agent-sandbox-latest — the agent-sandbox tag name
 *                         matches the E2B template alias tessary-agent-sandbox — see
 *                         sandbox-runner/agent-sandbox/Dockerfile and
 *                         .github/workflows/release.yml). Docker backend only.
 *   SANDBOX_DOCKER_CONCURRENCY  max sibling containers running at once (default 1).
 *                         One in-process semaphore guards the single container-spawn call site, so
 *                         every route shares one limiter rather than each keeping its own in sync.
 *   SANDBOX_DOCKER_MEMORY_MB, SANDBOX_DOCKER_CPUS, SANDBOX_DOCKER_PIDS_LIMIT
 *                         hard resource limits stamped on every spawned container (defaults
 *                         2048, 2, 256). Docker backend only.
 *   SANDBOX_NETWORK_ISOLATION
 *                         cut sibling containers off from the tessary service network (default OFF).
 *                         Off, a sibling joins the network this launcher is on and reaches the
 *                         backend's MCP door by service name — what makes agentic RCA/Triage work
 *                         on a localhost install with no public hostname. On, siblings run on
 *                         DOCKER_SANDBOX_NETWORK instead and the ONLY route back to the backend is
 *                         the SITE_DOMAIN public origin, which that install must then set.
 *   DOCKER_SANDBOX_NETWORK  dedicated bridge network the docker backend creates (if absent) and
 *                         runs every sibling container on WHEN SANDBOX_NETWORK_ISOLATION is set —
 *                         never host networking, never the tessary service network (default
 *                         tessary-sandbox). Unused, and not created, while isolation is off.
 *                         Network stays ON either way (never `--network none`): the agent needs
 *                         egress for the repo clone and the model-provider call, same as the E2B
 *                         template — see template.ts's own comment on the identical requirement.
 *   LAUNCHER_WORK_DIR, SANDBOX_WORK_VOLUME
 *                         how a sibling container is handed this container's per-request work dir:
 *                         LAUNCHER_WORK_DIR (default /launcher-work) is where THIS container
 *                         writes them; SANDBOX_WORK_VOLUME is the name of the Docker volume
 *                         mounted there, which the daemon resolves identically from inside a
 *                         container and from the host. docker-compose.yml mounts one named volume
 *                         and passes its name. Required (SANDBOX_WORK_VOLUME) whenever
 *                         SANDBOX_BACKEND=docker — the launcher fails at boot rather than at the
 *                         first confusing empty-/work run if it's missing. See LAUNCHER_WORK_DIR's
 *                         own comment in the code below for the full mechanism.
 *
 * Analysis-sandbox agent auth (FULL REMOVAL of the deployment-env-var credential path).
 * Every /rca and /triage request now carries its own `credential` object — the org's own
 * ProviderCredential row, decrypted by the backend and injected here — and this launcher reads NO
 * provider secret from its own process env any more. There is no AGENT_PROVIDER deployment knob, no
 * ANTHROPIC_API_KEY/OPENAI_API_KEY/GEMINI_API_KEY/GLM_API_KEY/GROK_API_KEY/CUSTOM_OPENAI_BASE_URL/
 * CUSTOM_OPENAI_API_KEY/AWS_BEARER_TOKEN_BEDROCK/AWS_ACCESS_KEY_ID/AWS_SECRET_ACCESS_KEY/
 * AWS_SESSION_TOKEN/MANTLE_REGION
 * env var any more — the backend throws MISSING_CREDENTIALS before ever calling this launcher when
 * a project's org has no usable credential, so every request that reaches /rca or /triage carries a
 * `credential.provider` naming BEDROCK, BEDROCK_MANTLE, or one of the OpenAI-compat providers, plus
 * that provider's own secret fields (see `credential` below). The secret fields feed agentEnvs()
 * ONLY — see runAgenticScript's own comment for why they are stripped before anything is written to
 * `input.json` or a sandbox's disk.
 *
 * `credential` (request body field, never logged, never written to input.json):
 *   { provider: "BEDROCK" | "BEDROCK_MANTLE",
 *     aws_region, aws_access_key, aws_secret_key }        -- region + SigV4 keys off the org's own
 *                                                             ProviderCredential row (Settings →
 *                                                             Providers); the two Bedrock endpoints
 *                                                             share this shape
 *   { provider: "GEMINI" | "GLM" | "GROK" | "CUSTOM" | "OPENAI" | ...,
 *     api_key, base_url?, custom_model_name? }             -- an OpenAI-compat provider's own key,
 *                                                             optional base-URL override, and (CUSTOM
 *                                                             only) the free-text model id
 *
 *   MANTLE_PROJECT_ID             the Bedrock Project (proj_…) mantle inference is attributed to
 *                                 and authorized against; blank = the account default project. Not a
 *                                 secret (an attribution scope, not a credential) and not
 *                                 per-request — stays a deployment-wide env var.
 *   OPENCODE_SMALL_MODEL          provider/model for background work (title generation
 *                                 and the like); unset means OpenCode uses the primary
 */
const http = require('node:http');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { spawn } = require('node:child_process');

// `e2b` is required LAZILY (only inside the E2B code paths) so the host launcher
// in local mode needs no `e2b` install — only the analyzer scripts' own deps.
let _Sandbox = null;
function Sandbox() {
  if (!_Sandbox) ({ Sandbox: _Sandbox } = require('e2b'));
  return _Sandbox;
}

const PORT = Number(process.env.PORT || 8080);
// Docker is the open default so the triage/RCA flow needs zero Tessary cloud credentials
// (no E2B key) out of the box. 'e2b' and 'local' are both still explicit opt-ins.
const BACKEND = (process.env.SANDBOX_BACKEND || 'docker').toLowerCase();
const SANDBOX_API_KEY = process.env.SANDBOX_API_KEY || '';
const E2B_API_KEY = process.env.E2B_API_KEY;
// E2B_TEMPLATE / 'evals-grader-runner' was the second alias here — the grader sandbox /grade and
// /lint ran in. Both routes were removed, so the constant, the env var and its compose entries
// are gone. The published cloud template is orphaned in E2B; nothing in this tree can delete it.
const ANALYZER_TEMPLATE = process.env.E2B_ANALYZER_TEMPLATE || 'tessary-agent-sandbox';
const SANDBOX_TIMEOUT_MS = Number(process.env.SANDBOX_TIMEOUT_MS || 60000);
const MAX_BODY_BYTES = 8 * 1024 * 1024;
// Cap on accumulated child stdout in the local and docker backends (neither honors a
// `maxBuffer`-style cap by default, so we enforce this ourselves — see runScriptLocally and
// runScriptInDocker) to keep a runaway agent from OOMing us.
const MAX_STDOUT_BYTES = 16 * 1024 * 1024;

// --- Docker backend (SANDBOX_BACKEND=docker) config — see the Env block above for what each
// of these does. ---
const DOCKER_SOCKET_PATH = process.env.DOCKER_SOCKET_PATH || '/var/run/docker.sock';
// server.js has no interpolation of its own the way docker-compose.yml's
// ${VAR:-...${OTHER:-x}} does, so this default cannot compose a version out of TESSARY_VERSION.
// It does not need to: the tag floats, and the release repoints `-latest` — which is why there is
// no version literal here to keep in step with anything. Set AGENT_IMAGE to pin.
const AGENT_IMAGE = process.env.AGENT_IMAGE || 'tessaryai/tessary:agent-sandbox-latest';
const SANDBOX_DOCKER_CONCURRENCY = Math.max(1, Number(process.env.SANDBOX_DOCKER_CONCURRENCY || 1));
const SANDBOX_DOCKER_MEMORY_MB = Number(process.env.SANDBOX_DOCKER_MEMORY_MB || 2048);
const SANDBOX_DOCKER_CPUS = Number(process.env.SANDBOX_DOCKER_CPUS || 2);
const SANDBOX_DOCKER_PIDS_LIMIT = Number(process.env.SANDBOX_DOCKER_PIDS_LIMIT || 256);
const DOCKER_SANDBOX_NETWORK = process.env.DOCKER_SANDBOX_NETWORK || 'tessary-sandbox';
// Whether a sibling agent container is CUT OFF from the tessary service network. Opt-in, default off.
//
// The cut-off used to be unconditional: every sibling ran on DOCKER_SANDBOX_NETWORK, "never the
// tessary service network this launcher itself runs on". That is the right posture for an install
// whose operator treats agent runs as untrusted — but it is not reachability-neutral, and the cost
// landed on the default install. The agent reads every trace and span it reasons about through the
// backend's MCP door; cut off from the service network, its only remaining route to that door is
// out to the internet and back in through the public origin, which is why the MCP base URL is built
// from SITE_DOMAIN. A localhost `docker compose up` has no public origin, so the URL degraded to a
// bare `https://` and agentic RCA/Triage could not run at all on the out-of-the-box install.
//
// So the default inverts: the sibling joins the network this launcher is already on and resolves
// `backend` by service name, and an operator who deems their installation untrusted sets
// SANDBOX_NETWORK_ISOLATION=1 to restore that dedicated bridge — at which point reaching the
// backend is the SITE_DOMAIN public-origin path, exactly as it was.
//
// What isolation still buys when it is on is unchanged. What turning it off costs is the honest
// other half: a sibling on the service network can address postgres and every other internal
// service directly, not just the MCP port. That is the trade this default makes.
const SANDBOX_NETWORK_ISOLATION = /^(1|true|yes|on)$/i.test(process.env.SANDBOX_NETWORK_ISOLATION || '');
// The classic Docker-outside-of-Docker path trap: this launcher itself runs INSIDE a container
// (docker-compose.yml's sandbox-runner service), and the Docker daemon it talks to over the
// mounted socket runs on the HOST. A `HostConfig.Binds` entry on a sibling container is resolved by
// that daemon against the HOST filesystem — a path like the launcher container's own `/tmp/xyz`
// means nothing to it (Docker silently creates an empty host directory at that literal path instead
// of erroring, so the sibling's /work would just be EMPTY — no input.json, a confusing failure with
// no error at all). The fix is to stop naming a path at all: ONE NAMED VOLUME
// (SANDBOX_WORK_VOLUME) is mounted into this container at LAUNCHER_WORK_DIR, and every sibling
// mounts THE SAME VOLUME BY NAME with VolumeOptions.Subpath naming the per-request subdirectory.
// A volume name means the same thing to the daemon whoever asks, so there is no host-side path for
// this process to know, guess, or be handed by an operator. Required whenever BACKEND is 'docker' —
// see the fail-at-boot check near the bottom of this file, so a missing value fails at startup
// rather than producing an agent run that silently sees no input.
const LAUNCHER_WORK_DIR = process.env.LAUNCHER_WORK_DIR || '/launcher-work';
const SANDBOX_WORK_VOLUME = process.env.SANDBOX_WORK_VOLUME || '';
// Every sibling container this launcher spawns carries this label — it is both what the startup
// reconciliation pass (reapOrphanSandboxContainers) filters on and, if a human ever needs to look,
// `docker ps --filter label=tessary.sandbox=1` on the host.
const SANDBOX_LABEL = 'tessary.sandbox';
const SANDBOX_LABEL_VALUE = '1';
// A `docker run` sibling has no owner left to reap it if THIS process dies mid-run (unlike E2B,
// whose sandboxes are torn down by the cloud side regardless of what happens to the launcher) —
// see the reconciliation pass below. Every real caller (the Java-side RcaSandbox/TriageSandbox/
// AnalysisSandbox/AgenticSynthesisSandbox/AgenticCodegenSandbox HTTP clients) sends its OWN much
// larger timeout_ms in the request body — up to 1,200,000ms for /triage, 900,000ms
// for /rca — none of which resemble SANDBOX_TIMEOUT_MS's 60000ms default. A reap threshold sized
// off that constant alone would kill still-legitimately-running containers on every redeploy or
// crash-restart. So every container is labeled with its
// own actual timeoutMs (below) and reaped against THAT, with a generous safety multiplier for
// Docker/network overhead the in-container clock doesn't see; this constant survives only as the
// fallback for a container with no parseable timeout label (e.g. one created before this fix, or
// a malformed label some future caller manages to send).
const SANDBOX_ORPHAN_GRACE_MULTIPLIER = 2;
const SANDBOX_ORPHAN_MAX_AGE_MS = SANDBOX_TIMEOUT_MS * 4;
const SANDBOX_LABEL_TIMEOUT_MS = 'tessary.sandbox.timeout-ms';

// Which OpenCode provider serves a given backend model id, and on what wire.
//
// TWO Bedrock endpoints, not one. `bedrock-runtime` speaks Converse and serves the Anthropic and
// Amazon families as cross-region inference profiles. `bedrock-mantle` is a different host with a
// different SigV4 service name that speaks the OpenAI Responses API, and it is the only way to
// reach the GPT-5.6 line. Their paths are not interchangeable: a GPT request sent to the plain
// /v1 that mantle's open-weight catalogue uses answers 404, which reads like the model is simply
// unavailable in the region.
const MANTLE_GPT = 'bedrock-mantle-gpt';
const BEDROCK_RUNTIME = 'amazon-bedrock';
// MANTLE_PROJECT_ID stays a deployment-wide env var: it is an
// attribution SCOPE, not a secret, and there is nowhere on ProviderCredential for a per-org value
// to live even if there were reason to want one. MANTLE_REGION does not: the region for a
// BEDROCK_MANTLE run comes from `credential.aws_region` (the org's own credential row), same as
// every other Bedrock/mantle field.
const MANTLE_PROJECT_ID = (process.env.MANTLE_PROJECT_ID || '').trim();

// FULL REMOVAL of the AGENT_PROVIDER deployment-wide dev-override knob and every env var
// it read (ANTHROPIC_API_KEY, AWS_BEARER_TOKEN_BEDROCK, OPENAI_API_KEY, GEMINI_API_KEY,
// GLM_API_KEY/GLM_BASE_URL, GROK_API_KEY, CUSTOM_OPENAI_BASE_URL/CUSTOM_OPENAI_API_KEY). What is
// left is the per-REQUEST form only: every /rca and /triage body now carries a `credential` object
// (the org's ProviderCredential row, decrypted by the backend — see the file-header doc), and the
// database is the only source. There is no deployment-wide fallback any more — a self-hoster with
// no org credential simply cannot run RCA/TRIAGE, the same way they cannot without an API key
// anywhere else in this product now (removing the deployment-wide knob also removed the one
// credential-free provider, Ollama).
//
// OPENCODE_PROVIDER_NAME/REQUEST_PROVIDER_TO_MODE survive from that removal because they answer a
// different question than AGENT_PROVIDER did: not "what does this deployment default to" but
// "what OpenCode provider name does THIS credential's provider map to". mirrors
// backend/llm-runtime/.../PlatformCatalog.java's own defaults so a launcher and the backend agree
// on where each provider lives.
const OPENAI_COMPAT_MODE = 'openai';
const GEMINI_MODE = 'gemini';
const GLM_MODE = 'glm';
const GROK_MODE = 'grok';
const CUSTOM_OPENAI_MODE = 'custom-openai';
const OPENROUTER_MODE = 'openrouter';
const MOONSHOT_MODE = 'moonshot';
// The one mode that is NOT an OpenAI-compat block: Anthropic speaks its own wire, so providerConfig
// gives it a dedicated `@ai-sdk/anthropic` block and openAiCompatProviderBlock never sees it. It is
// still in the two maps below because toProviderModel and agentEnvs both key off them.
const ANTHROPIC_MODE = 'anthropic';
const OPENCODE_PROVIDER_NAME = {
  [OPENAI_COMPAT_MODE]: 'openai-direct',
  [GEMINI_MODE]: 'gemini',
  [GLM_MODE]: 'glm',
  [GROK_MODE]: 'grok',
  [CUSTOM_OPENAI_MODE]: 'custom-openai',
  [OPENROUTER_MODE]: 'openrouter',
  [MOONSHOT_MODE]: 'moonshot',
  [ANTHROPIC_MODE]: 'anthropic',
};
// credential.provider (ModelProvider#name(), e.g. "GEMINI") -> the lowercase mode string above.
const REQUEST_PROVIDER_TO_MODE = {
  OPENAI: OPENAI_COMPAT_MODE,
  GEMINI: GEMINI_MODE,
  GLM: GLM_MODE,
  GROK: GROK_MODE,
  CUSTOM: CUSTOM_OPENAI_MODE,
  OPENROUTER: OPENROUTER_MODE,
  MOONSHOT: MOONSHOT_MODE,
  ANTHROPIC: ANTHROPIC_MODE,
};

/**
 * The default base URL for one OpenAI-compat mode when `credential.base_url` is blank — mirrors
 * PlatformCatalog's own defaults (backend/llm-runtime/src/main/java/ai/tessary/llm/PlatformCatalog.java)
 * so a launcher and the backend agree on where each provider lives absent an override. `mode` is
 * the lowercase mode string (OPENCODE_PROVIDER_NAME's keys); CUSTOM has none to assume — it is
 * "any other OpenAI-compatible endpoint" — so a blank `base_url` on that credential is a config
 * error, not a default to fall back to (see requireCredential).
 */
function defaultBaseUrlFor(mode) {
  switch (mode) {
    case OPENAI_COMPAT_MODE:
      return 'https://api.openai.com/v1';
    case GEMINI_MODE:
      return 'https://generativelanguage.googleapis.com/v1beta/openai/';
    case GLM_MODE:
      return 'https://open.bigmodel.cn/api/paas/v4';
    case GROK_MODE:
      return 'https://api.x.ai/v1';
    case OPENROUTER_MODE:
      return 'https://openrouter.ai/api/v1';
    case MOONSHOT_MODE:
      return 'https://api.moonshot.ai/v1';
    case ANTHROPIC_MODE:
      // WITH the /v1. OpenCode's `@ai-sdk/anthropic` treats baseURL as the full API root and
      // appends only `/messages`, so a bare host POSTs to https://api.anthropic.com/messages and
      // 404s — and in SERVER mode OpenCode folds that into an empty assistant turn rather than an
      // error, so the run surfaces as "opencode produced no usable reply" with a valid key and
      // zero tokens. The backend's langchain4j client behaves the SAME way: its own default is
      // "https://api.anthropic.com/v1/" and DefaultAnthropicClient appends the bare path
      // "messages". PlatformCatalog now carries the /v1 form for that reason, so this line really
      // does mirror the backend — one correct form for both consumers.
      return 'https://api.anthropic.com/v1';
    default:
      return null;
  }
}

/**
 * Base URL + API key for one OpenAI-compat mode, sourced from the request's own `credential`
 * object rather than the launcher's process env. `mode` is the lowercase mode string
 * (OPENCODE_PROVIDER_NAME's keys) — the caller resolves `credential.provider` through
 * REQUEST_PROVIDER_TO_MODE first, so this function itself never sees the uppercase wire form.
 */
function openAiCompatCredentials(mode, credential) {
  if (!OPENCODE_PROVIDER_NAME[mode]) return null;
  const baseURL = (credential.base_url || defaultBaseUrlFor(mode) || '').trim();
  return { baseURL, apiKey: (credential.api_key || '').trim() };
}

// The header that attributes a mantle request to a Bedrock Project. NOT cost metadata: mantle
// scopes inference by project the way bedrock-runtime scopes it by inference profile, so the
// production IAM policy grants `bedrock-mantle:CreateInference` on a project ARN. A request
// without this header lands in the account's `default` project, which that policy does not
// cover — a 403, not a mis-filed line item. Mirrors llm/MantleHttpClient, which adds the same
// header before signing.
const MANTLE_PROJECT_HEADER = 'OpenAI-Project';

// The provider config OpenCode runs with, injected per run rather than baked into the image so
// the region and the served model list stay with the launcher that already resolves credentials.
// An OpenAI-compat provider block for one mode (openai/gemini/glm/grok/custom-openai) — the
// OpenCode generic {npm, options: {baseURL, apiKey}} shape the mantle block below already uses,
// so this is not a new pattern, just a new (per-request) set of credentials feeding it.
function openAiCompatProviderBlock(mode, credential, model) {
  const creds = openAiCompatCredentials(mode, credential);
  if (!creds) return null;
  // `models` is not optional decoration — it is what makes the provider RESOLVABLE. A config block
  // whose key is not a models.dev provider id (openai-direct, glm, grok, gemini, moonshot,
  // custom-openai — six of our eight) contributes no models of its own, and OpenCode has no
  // catalog entry to borrow them from, so `provider/model` fails at SessionPrompt.getModel with
  // ProviderModelNotFoundError BEFORE any HTTP request. The run then surfaces as a bare
  // "Unexpected server error" with zero turns and zero tokens — the same unreadable failure the
  // Anthropic baseURL bug produced, by a different mechanism.
  //
  // Declaring the one model this run actually asked for fixes every one of them uniformly, and is
  // the only option that can work for CUSTOM at all: that provider is "any other OpenAI-compatible
  // endpoint", so no catalog id could ever exist for it. Renaming to the real models.dev ids was
  // the alternative and it is WRONG for GEMINI specifically: under the id `google`, OpenCode's
  // built-in provider plugin wins over the `npm` override and issues the NATIVE Gemini wire shape
  // with no Authorization header at all.
  const block = { npm: '@ai-sdk/openai-compatible', options: creds };
  if (model) block.models = { [model]: {} };
  return { [OPENCODE_PROVIDER_NAME[mode]]: block };
}

/**
 * The provider config OpenCode runs with, injected per run rather than baked into the image so
 * the region and the served model list stay with the launcher that already resolves credentials.
 *
 * Dispatches purely on `credential.provider` — there is no deployment-wide default any
 * more (see the file-header doc and the constants block above this function), so exactly ONE
 * provider block is declared per run: the one the request's own credential names. Previously this
 * unconditionally declared BOTH Bedrock endpoints (the platform's ambient identity served either)
 * and merged in an optional per-request OpenAI-compat block on top; now the credential IS the
 * selection, so there is nothing to merge — a Bedrock credential gets the Bedrock block, a GEMINI
 * credential gets the gemini block, and neither run declares a provider it has no key for.
 *
 * @param credential the request's own `credential` object (never logged — see the file-header doc
 *   and `requireCredential`). Required; every caller resolves and validates it first.
 */
function providerConfig(credential, qualifiedModel) {
  const config = { provider: {} };
  // The bare id the provider block has to declare, recovered from the id toProviderModel already
  // built. Split on the FIRST slash only, exactly as agent-stream.js's splitModel does, so an
  // OpenRouter id keeps its own slash: 'openrouter/openai/gpt-5.6-terra' -> 'openai/gpt-5.6-terra'.
  const bareModel = typeof qualifiedModel === 'string' ? qualifiedModel.slice(qualifiedModel.indexOf('/') + 1) : '';
  if (credential.provider === 'BEDROCK') {
    config.provider[BEDROCK_RUNTIME] = { options: { region: credential.aws_region || '' } };
  } else if (credential.provider === 'BEDROCK_MANTLE') {
    const mantleOptions = { baseURL: `https://bedrock-mantle.${credential.aws_region || ''}.api.aws/openai/v1` };
    // Blank is a working configuration (the account default project), not a degraded one — it is
    // what keeps a dev account without its own project running.
    if (MANTLE_PROJECT_ID) mantleOptions.headers = { [MANTLE_PROJECT_HEADER]: MANTLE_PROJECT_ID };
    // Declares its model for the same reason every OpenAI-compat block does: 'bedrock-mantle-gpt'
    // is not a models.dev provider id (only plain 'amazon-bedrock' is), so without this the run
    // dies at model resolution before any request. BEDROCK itself needs no such block — its key IS
    // a catalog id and the ids ModelCatalog uses ('anthropic.claude-sonnet-5' and friends) all
    // resolve under it.
    const mantleBlock = { npm: '@ai-sdk/amazon-bedrock/mantle', options: mantleOptions };
    if (bareModel) mantleBlock.models = { [bareModel]: {} };
    config.provider[MANTLE_GPT] = mantleBlock;
  } else if (credential.provider === 'ANTHROPIC') {
    // Anthropic's own wire, not an OpenAI-compat endpoint, so it gets its own npm package rather
    // than going through openAiCompatProviderBlock. Same {npm, options} shape as mantle above.
    config.provider[OPENCODE_PROVIDER_NAME[ANTHROPIC_MODE]] = {
      npm: '@ai-sdk/anthropic',
      options: {
        baseURL: (credential.base_url || defaultBaseUrlFor(ANTHROPIC_MODE) || '').trim(),
        apiKey: (credential.api_key || '').trim(),
      },
    };
  } else {
    const mode = REQUEST_PROVIDER_TO_MODE[credential.provider];
    const extra = mode && openAiCompatProviderBlock(mode, credential, bareModel);
    if (extra) Object.assign(config.provider, extra);
  }
  if (process.env.OPENCODE_SMALL_MODEL) config.small_model = process.env.OPENCODE_SMALL_MODEL;
  return config;
}

// CREDENTIAL / NETWORK POSTURE for a spawned sandbox process or sibling container. Every
// runner takes one and there is NO default: omitting it is a TypeError at the call site, not a
// silent fall-through to the full credential set. The two postures:
//   AGENT_POSTURE      -- our own agent (/rca, /triage): the host env plus the provider credentials
//                         agentEnvs() derives from it, and network egress (the sandbox bridge network
//                         on docker) for the repo clone and the model-provider call.
//   UNTRUSTED_POSTURE  -- an EMPTY env and, on docker, NetworkMode 'none'. It has NO ROUTE TODAY:
//                         its only callers were /grade and /lint, which ran user-authored grader
//                         code, and removing both routes removed its only route. It is kept
//                         deliberately: the pair exists precisely so a future untrusted-content
//                         route cannot default into the full credential set, and deleting the
//                         safe half of that pair would leave
//                         requirePosture with exactly one legal value — which is a default wearing a
//                         parameter's clothes.
// The old shape -- an optional envOverride / opts.{envs,network} that defaulted to AGENT_POSTURE's
// values when omitted -- was correct at every existing call site and wrong as a default: a future
// untrusted-content route that forgot the override would have inherited AWS/Bedrock/Anthropic
// credentials and egress with no error anywhere.
const AGENT_POSTURE = Object.freeze({ kind: 'trusted-agent' });
const UNTRUSTED_POSTURE = Object.freeze({ kind: 'untrusted-content' });
function requirePosture(posture, where) {
  if (posture !== AGENT_POSTURE && posture !== UNTRUSTED_POSTURE) {
    throw new TypeError(`${where}: a sandbox posture is required (AGENT_POSTURE or UNTRUSTED_POSTURE); got ${posture === undefined ? 'undefined' : JSON.stringify(posture)}`);
  }
}
// The child env for a LOCAL-backend spawn under a posture. PATH is kept for UNTRUSTED too: without
// it Node's execvp-based lookup can't resolve the bare `node` argv[0] and every request would
// ENOENT. It names directories, not secrets. Exported for the unit test in
// test/sandbox-posture.test.js. `credential` is UNUSED under UNTRUSTED_POSTURE (that branch
// returns before it is ever read) — it exists so AGENT_POSTURE can build agentEnvs() from it.
function childEnvFor(posture, workDir, credential, qualifiedModel) {
  requirePosture(posture, 'childEnvFor');
  return posture === AGENT_POSTURE
    ? { ...process.env, ...agentEnvs(credential, qualifiedModel), WORK_DIR: workDir }
    : { PATH: process.env.PATH, WORK_DIR: workDir };
}

// Credentials + provider config for the sandbox. Identical for the E2B and local backends, so a
// developer's local run cannot silently exercise a different provider than production.
// SigV4 only, deliberately. A Bedrock API key would be the smaller thing to hand a sandbox, but
// a Bedrock IAM policy that grants `bedrock-mantle:CreateInference` does not thereby grant
// `bedrock-mantle:CallWithBearerToken` — so a bearer token here would authenticate on bedrock-runtime and
// 403 on every mantle model, which is the confusing half-failure worth not building. Removing
// the one path that ever offered a bearer token here (AGENT_PROVIDER=bedrock-api-key) entirely means
// this reasoning is now unconditional rather than "in every mode except one".
//
// The env var an OpenAI-compat mode's credential rides in, forwarded alongside
// OPENCODE_CONFIG_CONTENT's own options.apiKey — belt-and-suspenders, since which of the two
// @ai-sdk packages honour the env var vs. the options object varies by version and both travel the
// same channel (no added exposure; the value is identical either way).
function openAiCompatEnvVars(mode, credential) {
  const apiKey = (credential.api_key || '').trim();
  switch (mode) {
    case OPENAI_COMPAT_MODE:
      return { OPENAI_API_KEY: apiKey };
    case GEMINI_MODE:
      return { GEMINI_API_KEY: apiKey };
    case GLM_MODE:
      return { GLM_API_KEY: apiKey };
    case GROK_MODE:
      return { GROK_API_KEY: apiKey };
    case CUSTOM_OPENAI_MODE:
      return { CUSTOM_OPENAI_API_KEY: apiKey };
    case OPENROUTER_MODE:
      return { OPENROUTER_API_KEY: apiKey };
    case MOONSHOT_MODE:
      return { MOONSHOT_API_KEY: apiKey };
    case ANTHROPIC_MODE:
      return { ANTHROPIC_API_KEY: apiKey };
    default:
      return {};
  }
}

/**
 * The credential env vars a spawned agent process/container needs, plus the
 * `OPENCODE_CONFIG_CONTENT` {@link providerConfig} produces from the same credential — the two
 * must always agree, which is why both are built from one `credential` object here rather than
 * independently.
 *
 * @param credential the request's own `credential` object — see {@link providerConfig}'s doc.
 */
function agentEnvs(credential, qualifiedModel) {
  const envs = {};
  if (credential.provider === 'BEDROCK' || credential.provider === 'BEDROCK_MANTLE') {
    envs.AWS_REGION = credential.aws_region || '';
    // The sandbox-side half of the IAM-role auth: omit the AWS key pair ENTIRELY when the
    // credential has none (an iam_role-mode row never
    // reaches here at all — see AGENTIC_IAM_ROLE_UNSUPPORTED on the backend — but an api_key-mode
    // row's keys are always present, so this stays a defensive omit-if-blank, not a live branch).
    // AWS_ACCESS_KEY_ID='' in the child env is not "absent" to the SDK's DefaultCredentialsProvider
    // chain — an explicitly-set-but-empty env var short-circuits the chain with an invalid
    // credential instead of falling through to the next provider.
    if ((credential.aws_access_key || '').trim()) envs.AWS_ACCESS_KEY_ID = credential.aws_access_key.trim();
    if ((credential.aws_secret_key || '').trim()) envs.AWS_SECRET_ACCESS_KEY = credential.aws_secret_key.trim();
  } else {
    const mode = REQUEST_PROVIDER_TO_MODE[credential.provider];
    if (mode) Object.assign(envs, openAiCompatEnvVars(mode, credential));
  }
  envs.OPENCODE_CONFIG_CONTENT = JSON.stringify(providerConfig(credential, qualifiedModel));
  return envs;
}

// Qualify the backend-configured model id with the provider that serves it.
//
// The backend configures a bare Bedrock model id (`global.anthropic.claude-sonnet-4-6`,
// `openai.gpt-5.6-terra`) because that is what its own Converse/mantle clients take; OpenCode
// addresses models as `provider/model`. The mapping is mechanical, so the launcher does it for
// both backends rather than making every caller know about providers. An id that already names
// a provider passes through, which keeps an explicit override possible.
/**
 * @param credential the request's own `credential` object — see {@link providerConfig}'s doc.
 */
function toProviderModel(model, credential) {
  if (!model) return model;
  const mode = credential && REQUEST_PROVIDER_TO_MODE[credential.provider];
  // OPENROUTER before the passthrough below, because an OpenRouter model id carries a slash of its
  // own ("openai/gpt-5.6-terra" is one model name, not provider+model). Letting it through unchanged
  // would address OpenCode's `openai` provider with an OpenRouter key.
  if (mode === OPENROUTER_MODE) {
    const prefix = OPENCODE_PROVIDER_NAME[OPENROUTER_MODE] + '/';
    return model.startsWith(prefix) ? model : prefix + model;
  }
  if (model.includes('/')) return model;
  if (mode) return OPENCODE_PROVIDER_NAME[mode] + '/' + model;
  // BEDROCK / BEDROCK_MANTLE (REQUEST_PROVIDER_TO_MODE has no entry for either) — the only two
  // providers left once an OpenAI-compat credential doesn't match, now that every
  // deployment-wide dev override this used to fall through to first is removed.
  return (model.startsWith('openai.') ? MANTLE_GPT : BEDROCK_RUNTIME) + '/' + model;
}

// toAnthropicModel (Bedrock inference-profile id -> first-party Anthropic model id) lived here
// until the removal of AGENT_PROVIDER=anthropic, its one caller. It is not coming back: ANTHROPIC
// is a per-request-selectable provider again, but it now carries its OWN model names
// ("claude-sonnet-5", from ModelCatalog) rather than a Bedrock inference-profile id needing
// translation, so toProviderModel qualifies it like every other non-Bedrock provider.

// ---------------------------------------------------------------------------
// Failure diagnostics
//
// Every failure on these paths used to collapse into a bare 502 `{error:'sandbox orchestration
// failed'}`, so the backend logged "launcher HTTP 502" and nothing else: a run could fail four
// times in a row, each attempt burning its full timeout, with no way to tell a deadline from a
// crash from a sandbox that never started. The helpers below classify the failure and build a
// SMALL, scrubbed envelope (buildErrorBody) the backend can log verbatim.
//
// HARD RULE: the response body carries launcher-authored strings and SDK/Node error METADATA
// only. Never the clone URL (it embeds a short-lived git token), never sandbox stdout/stderr,
// never model output or repo content. Those stay on this launcher's console, sliced and scrubbed,
// exactly as before. When a field's safety cannot be guaranteed it is omitted, not truncated.

// Local copy of the analyzer scripts' scrubber (agent-sandbox/agent-stream.js): clone tokens
// (https://x-access-token:TOKEN@host) and platform API keys (tsy_<scope>_<random>). Duplicated on
// purpose — the launcher image copies ONLY server.js (see launcher/Dockerfile), so
// agent-sandbox is not require-able from the deployed sidecar. Keep the two in sync.
function scrubToken(s) {
  return String(s)
    .replace(/x-access-token:[^@\s]+@/g, 'x-access-token:***@')
    .replace(/tsy_[a-z]_[A-Za-z0-9_-]+/g, 'tsy_***');
}

// Hard cap on the one free-text field we hand back. Small on purpose: this is a triage
// breadcrumb pointing at the launcher logs, not a log shipper.
const DETAIL_MAX = 200;

// An E2B microVM is a separate machine on E2B's network, not a process on this host — a
// callback URL of `http://localhost:8000` (docker-compose.dev.yml's own default, and the value the
// RCA/triage MCP base URL resolves to whenever nothing else is configured) means "call yourself
// back" from inside the microVM, which is unreachable and guaranteed to fail the run after burning
// a full sandbox create + the platform's evidence door never opening. Checked ONLY on the `e2b`
// backend (see the call site in runAgenticScript): 'docker' and 'local' run as siblings/children of
// THIS host, where the same `localhost` value is exactly right, so this must never fire there.
// An unparseable URL is treated the same as localhost — it is equally unusable.
//
// `0.0.0.0` (a real deployment-config typo, not just a curiosity — Linux happily lets a client
// bind/connect to it as loopback) and IPv4-mapped IPv6 literals (`::ffff:127.0.0.1`, which Node's
// URL parser normalizes to `[::ffff:7f00:1]` rather than `[::1]`) both need their own checks: they
// are equally "call yourself back" addresses but do not textually match `127.0.0.1`/`[::1]`.
// `u.hostname` already lower-cases and numerically normalizes IPv4 shorthand/
// octal/decimal forms (e.g. `0177.0.0.1` -> `127.0.0.1`) per the WHATWG URL spec, so those need no
// extra handling here.
const IPV4_MAPPED_LOCALHOST = '[::ffff:7f00:1]';
function pointsAtLocalhost(rawUrl) {
  let u;
  try { u = new URL(rawUrl); } catch { return true; }
  return u.hostname === 'localhost'
    || u.hostname === '127.0.0.1'
    || u.hostname === '0.0.0.0'
    || u.hostname === '[::1]'
    || u.hostname === IPV4_MAPPED_LOCALHOST;
}

// Attach launcher diagnostics to an in-flight error so the top-level handler can describe it
// without re-deriving context it never had (which script, which sandbox, how long, what deadline).
// Best-effort: an exotic/frozen throwable just falls back to the generic body.
function stampFailure(err, fields) {
  if (!err || typeof err !== 'object') return err;
  try { Object.assign(err, fields); } catch { /* non-extensible error object */ }
  return err;
}

function exitCodeOf(err) {
  const c = err && (err.exitCode ?? (err.result && err.result.exitCode));
  return typeof c === 'number' ? c : undefined;
}

// Does this error carry sandbox process output? Detected STRUCTURALLY, because E2B's
// `CommandExitError` is constructed as `super(result.stderr)` — its `message` IS the raw sandbox
// stderr, which can hold the tokenized clone URL, model output, and repo content. For such errors
// the message is never published and never pattern-matched.
function carriesProcessOutput(err) {
  return !!err && (err.name === 'CommandExitError'
    || err.stdout !== undefined || err.stderr !== undefined || err.result !== undefined
    || exitCodeOf(err) !== undefined);
}

function namedTimeout(err) {
  if (!err) return false;
  if (err.name === 'TimeoutError' || err.code === 'ETIMEDOUT') return true;
  // Never pattern-match the message of an error that carries process output (it IS the sandbox
  // stderr — a grader that merely PRINTS the word "timeout" would otherwise be misfiled).
  if (carriesProcessOutput(err)) return false;
  return /timed?\s?out|timeout|deadline exceeded/i.test(String(err.message || ''));
}

// Was this the deadline, or something else? THE single most useful bit for triage and the one the
// old generic 502 destroyed. Evidence in confidence order: the SDK's own TimeoutError, then wall
// clock landing on the caller's deadline — E2B can report a hit deadline as a bare `SandboxError`
// ("[unknown] terminated") with no exit code and no buffered output, so elapsed time is often the
// ONLY signal left. `timeout` stays true even for a non-zero exit that landed on the deadline
// (the script self-aborted at the wall), while `kind` keeps the finer distinction.
function classifyFailure(err, elapsedMs, timeoutMs) {
  const atDeadline = Number.isFinite(elapsedMs) && Number.isFinite(timeoutMs) && timeoutMs > 0
    // 2% slack absorbs the gap between the deadline firing and our own Date.now() bookends.
    && elapsedMs >= timeoutMs * 0.98;
  const exitCode = exitCodeOf(err);
  let kind;
  if (err && err.launcherKind) kind = err.launcherKind;          // explicitly classified at the throw site
  else if (typeof exitCode === 'number' && exitCode !== 0) kind = 'script_exit';
  else if (namedTimeout(err) || atDeadline) kind = 'timeout';
  else kind = 'orchestration';
  return { kind, timeout: kind === 'timeout' || atDeadline, atDeadline, exitCode };
}

// The one free-text field: launcher-authored wherever the underlying message could be sandbox
// output, and scrubbed + hard-truncated regardless (belt and braces — an SDK message can quote a
// URL it was handed).
function safeDetail(err, cls, meta) {
  const { elapsed_ms: elapsedMs, timeout_ms: timeoutMs, script } = meta;
  if (cls.kind === 'timeout') {
    if (!Number.isFinite(elapsedMs) || !Number.isFinite(timeoutMs)) return 'timed out before any result';
    // Burning the WHOLE budget and returning nothing (the incident shape) reads differently from
    // an SDK/network timeout that gave up early — say which one it was.
    return cls.atDeadline
      ? `deadline hit: no result after ${elapsedMs}ms of ${timeoutMs}ms`
      : `timed out after ${elapsedMs}ms (deadline ${timeoutMs}ms)`;
  }
  if (cls.kind === 'script_exit') {
    // The message here is raw sandbox stderr — withheld by design; the sliced copy is on the
    // launcher console, keyed by the sandbox id we return alongside.
    return `${script || 'command'} exited ${cls.exitCode} (output withheld; see launcher logs)`;
  }
  if (carriesProcessOutput(err)) return 'command failed (output withheld; see launcher logs)';
  return scrubToken(String((err && err.message) || err || 'unknown')).slice(0, DETAIL_MAX);
}

// F1: the numeric fields triage.js/rca.js's failure envelope carries (agent-stream.js's sumUsage
// shape). Named once so usageFromFailureStdout and buildErrorBody's re-validation of `err.usage`
// (belt and braces — see there) can never drift into allow-listing different keys.
const USAGE_FIELDS = ['input_tokens', 'output_tokens', 'cache_read_input_tokens', 'cache_creation_input_tokens'];

// F1: triage.js/rca.js write `{is_error:true, error, usage}` to stdout before exiting non-zero, so a
// failing agentic run's spend is not silently discarded (agent-stream.js's sumUsage/the two scripts'
// catch blocks). Extract ONLY the numeric usage fields, allow-listed by key AND type — this is
// launcher-controlled stdout, not arbitrary sandbox output, but the HARD RULE above is absolute: a
// malformed or truncated frame must never let a string reach the response, so nothing here is
// trusted merely because we recognise the shape.
function usageFromFailureStdout(stdout) {
  if (!stdout) return undefined;
  let parsed;
  try {
    parsed = JSON.parse(String(stdout));
  } catch {
    return undefined;
  }
  if (!parsed || typeof parsed !== 'object' || parsed.is_error !== true) return undefined;
  return numericUsageOnly(parsed.usage);
}

// Re-validated at every read, not trusted from a prior validation: keeps buildErrorBody safe even
// when `err.usage` was set somewhere other than usageFromFailureStdout.
function numericUsageOnly(u) {
  if (!u || typeof u !== 'object') return undefined;
  const out = {};
  for (const k of USAGE_FIELDS) {
    if (typeof u[k] === 'number' && Number.isFinite(u[k])) out[k] = u[k];
  }
  return Object.keys(out).length ? out : undefined;
}

// The 502 body. Every field is either a constant, a number we measured, a name from the SDK, or a
// string this file wrote — see the HARD RULE above.
function buildErrorBody(err) {
  const meta = (err && err.launcherMeta) || {};
  const cls = classifyFailure(err, meta.elapsed_ms, meta.timeout_ms);
  const body = {
    error: 'sandbox orchestration failed', // unchanged, so existing backend matching still works
    kind: cls.kind,
    timeout: cls.timeout,
    error_class: String((err && err.name) || 'Error').slice(0, 64),
    detail: safeDetail(err, cls, meta),
  };
  if (typeof cls.exitCode === 'number') body.exit_code = cls.exitCode;
  if (meta.script) body.script = meta.script;             // a filename this file chose, never input
  if (Number.isFinite(meta.elapsed_ms)) body.elapsed_ms = meta.elapsed_ms;
  if (Number.isFinite(meta.timeout_ms)) body.timeout_ms = meta.timeout_ms;
  // Opaque E2B identifier, not a secret: it is the join key between this 502 and the launcher's
  // own console output / the E2B dashboard for the same run.
  if (meta.sandbox_id) body.sandbox_id = String(meta.sandbox_id).slice(0, 64);
  // F1: what a failing agentic run spent, when its stdout carried triage.js/rca.js's envelope —
  // set directly on the error at the throw site (like exitCode above), never through launcherMeta,
  // so it survives every wrapping layer between there and here undisturbed. Re-validated here
  // regardless (see numericUsageOnly) rather than trusted from the throw site.
  const usage = numericUsageOnly(err && err.usage);
  if (usage) body.usage = usage;
  return body;
}

// JSON.parse's SyntaxError quotes the offending input straight into its message (e.g. `Unexpected
// token 'h', "hello" is not valid JSON`) — that input is raw agent stdout, so letting it escape
// would smuggle model output into the HTTP response. Replace it with a launcher-authored error and
// keep the real stdout on the console.
function parseScriptOutput(scriptName, stdout) {
  const text = typeof stdout === 'string' ? stdout : String(stdout ?? '');
  try {
    return JSON.parse(text);
  } catch {
    const bytes = Buffer.byteLength(text);
    console.error(`${scriptName}: unparseable stdout (${bytes} bytes)`);
    if (text) console.error('--- unparseable stdout ---\n' + scrubToken(text).slice(0, 2000));
    const e = new Error(`${scriptName} returned unparseable output (${bytes} bytes)`);
    e.launcherKind = 'bad_output';
    throw e;
  }
}

// Local backend (SANDBOX_BACKEND=local): run an analyzer script on the HOST instead of in
// an E2B microVM. A fresh temp dir is the per-request work root (WORK_DIR) — the script
// clones into it and starts the locally installed `opencode` with the same credentials and
// provider config the E2B path injects. Same token-safety discipline as the E2B paths: the
// script's stderr can carry the tokenized clone URL, so we never put it into the thrown error /
// HTTP response (a truncated copy is console.error'd to the launcher console for debugging).
function runScriptLocally(scriptName, payload, timeoutMs, posture, credential) {
  requirePosture(posture, 'runScriptLocally');
  return new Promise((resolve, reject) => {
    // Setup failures (no temp space, unwritable tmpdir) never reach the script; they still name it
    // so the 502 says which path died. See buildErrorBody.
    const withSetupMeta = (e) => stampFailure(e, { launcherMeta: { script: scriptName, timeout_ms: timeoutMs } });
    let workDir;
    try {
      workDir = fs.mkdtempSync(path.join(os.tmpdir(), 'launcher-'));
    } catch (e) {
      reject(withSetupMeta(e));
      return;
    }
    const cleanup = () => { try { fs.rmSync(workDir, { recursive: true, force: true }); } catch { /* best effort */ } };
    const inputPath = path.join(workDir, 'input.json');
    try {
      fs.writeFileSync(inputPath, JSON.stringify(payload));
    } catch (e) {
      cleanup();
      reject(withSetupMeta(e));
      return;
    }
    const scriptPath = path.join(__dirname, '..', 'agent-sandbox', scriptName);
    // `spawn` does NOT honor `maxBuffer` (only exec/execFile do), so the manual `stdout`
    // accumulation below is unbounded — a runaway agent could OOM the launcher. Enforce an
    // explicit byte cap ourselves: kill the child and reject (token-free message) on overflow.
    const startedAt = Date.now();
    const childEnv = childEnvFor(posture, workDir, credential, payload.model);
    const child = spawn('node', [scriptPath, inputPath], {
      cwd: workDir,
      env: childEnv,
      timeout: timeoutMs,
    });
    // `error` and `close` can BOTH fire (e.g. ENOENT spawns `error` then `close` with code -2),
    // and the overflow path settles early — guard so cleanup + settle happen exactly once.
    let settled = false;
    const settle = (fn, arg) => {
      if (settled) return;
      settled = true;
      cleanup();
      fn(arg);
    };
    // Every rejection from here on carries the launcher diagnostics the 502 body is built from.
    const withMeta = (e) => stampFailure(e, {
      launcherMeta: { script: scriptName, elapsed_ms: Date.now() - startedAt, timeout_ms: timeoutMs },
    });
    let stdout = '';
    let stderr = '';
    let stdoutBytes = 0;
    child.stdout.on('data', (d) => {
      stdoutBytes += d.length;
      if (stdoutBytes > MAX_STDOUT_BYTES) {
        child.kill('SIGKILL');
        settle(reject, withMeta(new Error(`${scriptName} stdout exceeded ${MAX_STDOUT_BYTES} bytes`)));
        return;
      }
      stdout += d.toString();
    });
    child.stderr.on('data', (d) => { stderr += d.toString(); });
    child.on('error', (e) => { settle(reject, withMeta(e)); });
    child.on('close', (code, signal) => {
      if (settled) return;
      const elapsedMs = Date.now() - startedAt;
      try {
        if (code !== 0) {
          // `spawn({timeout})` kills the child with SIGTERM on the deadline, which surfaces as a
          // NULL exit code plus a signal. Classify that as a timeout rather than a script failure:
          // the two need completely different fixes, and the backend only ever saw a bare 502.
          const timedOut = code === null && elapsedMs >= timeoutMs * 0.98;
          // The script's stderr can carry the tokenized clone URL — log a scrubbed, truncated copy
          // to the local console only, NEVER into the thrown error (which reaches the HTTP client).
          console.error(`${scriptName} ${timedOut ? 'timed out' : 'failed'} after ${elapsedMs}ms of ${timeoutMs}ms (exit ${code}, signal ${signal || 'none'})`);
          if (stderr) console.error('--- script stderr ---\n' + scrubToken(stderr).slice(-4000));
          if (stdout) console.error('--- script stdout ---\n' + scrubToken(stdout).slice(0, 2000));
          const e = new Error(timedOut ? `${scriptName} timed out after ${elapsedMs}ms` : `${scriptName} exit ${code}`);
          if (timedOut) e.launcherKind = 'timeout';
          else e.exitCode = code; // classifies as script_exit; the message stays launcher-authored
          // F1: a non-timeout exit is exactly the shape triage.js/rca.js's failure envelope
          // targets — extract its usage (if any) so buildErrorBody can carry it to the backend.
          if (!timedOut) {
            const usage = usageFromFailureStdout(stdout);
            if (usage) e.usage = usage;
          }
          settle(reject, withMeta(e));
          return;
        }
        settle(resolve, parseScriptOutput(scriptName, stdout));
      } catch (e) {
        settle(reject, withMeta(e));
      }
    });
  });
}

// ---------------------------------------------------------------------------
// Docker backend (SANDBOX_BACKEND=docker) — the open default.
//
// Talks to the Docker Engine API over the socket docker-compose.yml mounts read-write into this
// container (DOCKER_SOCKET_PATH). Deliberately raw HTTP over `node:http`'s `socketPath` option,
// not a docker-cli shellout or an SDK dependency: no docker-cli binary in the launcher image, no
// new npm dependency (so check-dependency-audit.sh's CVE surface doesn't grow for this). Only the
// five request shapes runScriptInDocker actually needs are hand-rolled below — this is not a
// general Engine API client.

// One raw HTTP round trip over the mounted socket. `stream: true` hands back the live
// `http.IncomingMessage` (used only for the follow=1 logs request below); otherwise the response
// body is buffered and returned as text.
function dockerRequest(method, urlPath, { body, stream } = {}) {
  return new Promise((resolve, reject) => {
    const data = body === undefined ? undefined : Buffer.from(JSON.stringify(body));
    const headers = {};
    if (data) {
      headers['Content-Type'] = 'application/json';
      headers['Content-Length'] = data.length;
    }
    const req = http.request({ socketPath: DOCKER_SOCKET_PATH, path: urlPath, method, headers }, (res) => {
      if (stream) { resolve(res); return; }
      const chunks = [];
      res.on('data', (c) => chunks.push(c));
      res.on('end', () => resolve({ statusCode: res.statusCode, text: Buffer.concat(chunks).toString('utf8') }));
      res.on('error', reject);
    });
    req.on('error', reject);
    if (data) req.write(data);
    req.end();
  });
}

// Buffered JSON round trip, throwing a launcher-authored error (never the daemon's own message,
// which can echo request content such as the work-dir bind path) on a non-2xx status.
async function dockerJson(method, urlPath, body) {
  const res = await dockerRequest(method, urlPath, { body });
  if (res.statusCode < 200 || res.statusCode >= 300) {
    throw stampFailure(new Error(`docker ${method} ${urlPath} -> ${res.statusCode}`), { launcherKind: 'orchestration' });
  }
  return res.text ? JSON.parse(res.text) : {};
}

// `tessaryai/tessary:agent-sandbox-latest` -> ['tessaryai/tessary', 'agent-sandbox-latest']; a bare
// `repo` (no tag) -> ['repo', 'latest'], matching the Engine API's own /images/create default.
// The colon check is anchored after the last '/' so a registry host:port (e.g.
// `myregistry:5000/repo`) is never mistaken for a tag separator.
function splitImageRef(ref) {
  const lastSlash = ref.lastIndexOf('/');
  const lastColon = ref.lastIndexOf(':');
  if (lastColon > lastSlash) return [ref.slice(0, lastColon), ref.slice(lastColon + 1)];
  return [ref, 'latest'];
}

// Set once AGENT_IMAGE is confirmed present (or freshly pulled), so every request after the
// first skips the existence round trip below.
let agentImageReady = false;

// The raw Engine API's POST /containers/create does NOT auto-pull the way `docker run`/the CLI
// does, and AGENT_IMAGE is only ever passed to this service as an env var, never declared as a
// compose `image:` — so `docker compose pull`/`up` never fetches it either. Without this, a
// fresh self-host that never manually `docker pull`-ed AGENT_IMAGE would 502 with "No such
// image" on the very first agentic request. Mirrors what the CLI does implicitly: check, and
// pull on miss, before creating the sibling container.
async function ensureAgentImage() {
  if (agentImageReady) return;
  const check = await dockerRequest('GET', `/images/${encodeURIComponent(AGENT_IMAGE)}/json`);
  if (check.statusCode >= 200 && check.statusCode < 300) {
    agentImageReady = true;
    return;
  }
  console.error(`docker backend: AGENT_IMAGE ${AGENT_IMAGE} not found locally, pulling...`);
  const [repo, tag] = splitImageRef(AGENT_IMAGE);
  const qs = `fromImage=${encodeURIComponent(repo)}&tag=${encodeURIComponent(tag)}`;
  const pullRes = await dockerRequest('POST', `/images/create?${qs}`, { stream: true });
  // Newline-delimited JSON pull-progress frames. The pull can fail MID-STREAM (bad tag, network
  // drop, registry auth failure) while the HTTP status stays 200 — Docker reports that failure
  // only as an `{"error": "..."}` frame somewhere in the stream, per the Engine API's own
  // documented behaviour. Checking statusCode alone
  // would cache agentImageReady=true forever on a failed pull, since nothing would ever retry.
  let pullError = null;
  let buffered = '';
  await new Promise((resolve, reject) => {
    pullRes.on('data', (chunk) => {
      buffered += chunk.toString();
      let newlineIdx;
      while ((newlineIdx = buffered.indexOf('\n')) !== -1) {
        const line = buffered.slice(0, newlineIdx).trim();
        buffered = buffered.slice(newlineIdx + 1);
        if (!line) continue;
        try {
          const frame = JSON.parse(line);
          if (frame && frame.error && !pullError) pullError = frame.error;
        } catch { /* a non-JSON or partial line; the pull itself isn't invalidated by this */ }
      }
    });
    pullRes.on('end', resolve);
    pullRes.on('error', reject);
  });
  if (pullRes.statusCode < 200 || pullRes.statusCode >= 300 || pullError) {
    throw stampFailure(
      new Error(`docker image pull ${AGENT_IMAGE} -> ${pullRes.statusCode}${pullError ? `: ${pullError}` : ''}`),
      { launcherKind: 'orchestration' },
    );
  }
  agentImageReady = true;
}

// The Engine API's log stream is multiplexed when the container has no TTY (which ours never
// do): each frame is an 8-byte header — byte 0 the stream (1=stdout, 2=stderr), bytes 4-7 a
// big-endian payload length — followed by that many bytes of the actual log. Returns a `feed`
// function to call with each raw chunk off the response stream.
function createDockerLogDemuxer(onStdout, onStderr) {
  let buf = Buffer.alloc(0);
  return function feed(chunk) {
    buf = buf.length ? Buffer.concat([buf, chunk]) : chunk;
    while (buf.length >= 8) {
      const streamType = buf.readUInt8(0);
      const len = buf.readUInt32BE(4);
      if (buf.length < 8 + len) break;
      const payload = buf.subarray(8, 8 + len);
      if (streamType === 2) onStderr(payload); else onStdout(payload);
      buf = buf.subarray(8 + len);
    }
  };
}

// A simple in-process counting semaphore. This is SANDBOX_DOCKER_CONCURRENCY's whole
// implementation: it wraps the ONE container-spawn call site below (not per-script), so every
// route can reuse the same instance without a second limiter to keep in sync.
class Semaphore {
  constructor(max) {
    this.max = max;
    this.active = 0;
    this.queue = [];
  }
  run(fn) {
    return new Promise((resolve, reject) => {
      const task = () => {
        this.active++;
        fn().then(
          (v) => { this.active--; this._drain(); resolve(v); },
          (e) => { this.active--; this._drain(); reject(e); },
        );
      };
      if (this.active < this.max) task();
      else this.queue.push(task);
    });
  }
  _drain() {
    if (this.queue.length && this.active < this.max) this.queue.shift()();
  }
}
const dockerSemaphore = new Semaphore(SANDBOX_DOCKER_CONCURRENCY);

// The network a sibling agent container runs on, resolved once and memoised.
//
// With isolation OFF (the default) this is the network THIS launcher is on, read back from the
// daemon rather than named in config: the launcher's own container id is its hostname, and the
// compose project prefixes the network name ('tessary_tessary'), so a hard-coded default or a
// second env var would be wrong on any install that renamed its project. A launcher attached to
// more than one network takes the first, which for the compose file in this repo is the only one.
//
// If the lookup fails we fall back to the isolated bridge rather than guessing: an agent that
// cannot reach the MCP door fails one run with a clear error, where a wrong NetworkMode string
// fails every run at container-create with a daemon message about a network nobody configured.
let sandboxNetworkPromise = null;
async function sandboxNetwork() {
  if (SANDBOX_NETWORK_ISOLATION) return DOCKER_SANDBOX_NETWORK;
  if (sandboxNetworkPromise === null) {
    sandboxNetworkPromise = (async () => {
      try {
        const self = await dockerJson('GET', `/containers/${process.env.HOSTNAME}/json`);
        const names = Object.keys((self && self.NetworkSettings && self.NetworkSettings.Networks) || {});
        if (names.length > 0) return names[0];
        console.error('warning: launcher is on no network; falling back to the isolated sandbox bridge');
      } catch (e) {
        console.error(`warning: could not read the launcher's own network (${e && e.message}); falling back to the isolated sandbox bridge`);
      }
      return DOCKER_SANDBOX_NETWORK;
    })();
  }
  return sandboxNetworkPromise;
}

// Create DOCKER_SANDBOX_NETWORK if it doesn't already exist. Idempotent and best-effort: called
// once at startup (see the bottom of this file), never on the request path. A no-op unless
// isolation is on — with it off nothing runs on that bridge, so creating it would leave an empty
// network behind on every install that never opted in.
async function ensureSandboxNetwork() {
  if (!SANDBOX_NETWORK_ISOLATION) return;
  try {
    await dockerJson('GET', `/networks/${DOCKER_SANDBOX_NETWORK}`);
    return;
  } catch { /* not found (or a transient error) — try to create it below */ }
  try {
    await dockerJson('POST', '/networks/create', { Name: DOCKER_SANDBOX_NETWORK, Driver: 'bridge' });
    console.log(`docker backend: created sandbox network '${DOCKER_SANDBOX_NETWORK}'`);
  } catch (e) {
    // Non-fatal: container create will fail loudly per-request if the network genuinely isn't
    // there, which is a clearer signal than crash-looping the launcher over it at boot.
    console.error(`warning: could not ensure sandbox network '${DOCKER_SANDBOX_NETWORK}': ${e && e.message}`);
  }
}

// Best-effort startup reconciliation: stop + remove any tessary.sandbox=1 container older than
// SANDBOX_ORPHAN_MAX_AGE_MS. Every normal run already removes its own container in
// runScriptInDocker's `finally` — this exists solely for the crash/redeploy case, where `finally`
// never ran (the launcher itself died mid-run, or was killed/replaced).
async function reapOrphanSandboxContainers() {
  let list;
  try {
    const filters = encodeURIComponent(JSON.stringify({ label: [`${SANDBOX_LABEL}=${SANDBOX_LABEL_VALUE}`] }));
    list = await dockerJson('GET', `/containers/json?all=true&filters=${filters}`);
  } catch (e) {
    console.error(`warning: could not list sandbox containers for startup reconciliation: ${e && e.message}`);
    return;
  }
  const now = Date.now();
  for (const c of list) {
    const createdMs = (c.Created || 0) * 1000; // Engine API reports `Created` as unix SECONDS
    // Reap against THIS container's own declared timeout (see SANDBOX_LABEL_TIMEOUT_MS's
    // comment), not a global constant unrelated to the real per-request deadlines callers send —
    // falling back to SANDBOX_ORPHAN_MAX_AGE_MS only when the label is missing or unparseable.
    const labels = c.Labels || {};
    const labeledTimeoutMs = Number(labels[SANDBOX_LABEL_TIMEOUT_MS]);
    const maxAgeMs = Number.isFinite(labeledTimeoutMs) && labeledTimeoutMs > 0
      ? labeledTimeoutMs * SANDBOX_ORPHAN_GRACE_MULTIPLIER
      : SANDBOX_ORPHAN_MAX_AGE_MS;
    if (now - createdMs < maxAgeMs) continue; // still plausibly a live run
    console.error(`docker backend: reaping orphaned sandbox container ${c.Id} (created ${new Date(createdMs).toISOString()}, maxAgeMs=${maxAgeMs})`);
    try { await dockerJson('POST', `/containers/${c.Id}/stop?t=1`); } catch { /* best effort */ }
    try { await dockerJson('DELETE', `/containers/${c.Id}?force=true`); } catch { /* best effort */ }
  }
}

// Docker backend (SANDBOX_BACKEND=docker): run an analyzer script in a FRESH, hardened sibling
// container spawned from AGENT_IMAGE, one per request, removed on completion — the open default.
// Cloned from runScriptLocally's shape (mkdtemp work dir -> write input.json -> run with an
// MAX_STDOUT_BYTES cap and a timeout -> cleanup in `finally`), swapping "spawn node on the host"
// for "create+start a sibling container that runs node inside it". agentEnvs() passes through
// unchanged for the agentic callers below (AGENT_POSTURE), which is what keeps E2B/local/docker
// credential resolution identical (see the Env-block comment at the top of this file).
// Every surviving route passes AGENT_POSTURE. UNTRUSTED_POSTURE has no caller since removing
// /grade and /lint — see the posture block above for why the constant stays anyway.
function runScriptInDocker(scriptName, payload, timeoutMs, posture, credential) {
  requirePosture(posture, 'runScriptInDocker');
  return dockerSemaphore.run(() => runScriptInDockerInner(scriptName, payload, timeoutMs, posture, credential));
}

async function runScriptInDockerInner(scriptName, payload, timeoutMs, posture, credential) {
  const startedAt = Date.now();
  const withMeta = (e) => stampFailure(e, { launcherMeta: { script: scriptName, elapsed_ms: Date.now() - startedAt, timeout_ms: timeoutMs } });

  // workDir is a path INSIDE this container, under the shared LAUNCHER_WORK_DIR mount — see that
  // constant's comment for why it cannot be os.tmpdir() here the way runScriptLocally uses it.
  let workDir;
  try {
    fs.mkdirSync(LAUNCHER_WORK_DIR, { recursive: true });
    workDir = fs.mkdtempSync(path.join(LAUNCHER_WORK_DIR, 'run-'));
  } catch (e) {
    throw withMeta(e);
  }
  const cleanupDir = () => { try { fs.rmSync(workDir, { recursive: true, force: true }); } catch { /* best effort */ } };
  try {
    fs.writeFileSync(path.join(workDir, 'input.json'), JSON.stringify(payload));
  } catch (e) {
    cleanupDir();
    throw withMeta(e);
  }
  // The SAME subdirectory, named the way a sibling container has to address it: relative to the
  // shared volume's root (see LAUNCHER_WORK_DIR's comment).
  const workSubpath = path.basename(workDir);

  const envList = Object.entries(posture === AGENT_POSTURE ? agentEnvs(credential, payload.model) : {}).map(([k, v]) => `${k}=${v}`);
  envList.push('WORK_DIR=/work');

  let containerId;
  try {
    await ensureAgentImage();
    const created = await dockerJson('POST', '/containers/create', {
      Image: AGENT_IMAGE,
      Cmd: ['node', scriptName, '/work/input.json'],
      WorkingDir: '/home/user',
      Env: envList,
      // Findable/reapable by reconciliation (reapOrphanSandboxContainers) and by a human running
      // `docker ps --filter label=tessary.sandbox=1` — see the constant's own comment above.
      Labels: {
        [SANDBOX_LABEL]: SANDBOX_LABEL_VALUE,
        'tessary.sandbox.script': scriptName,
        [SANDBOX_LABEL_TIMEOUT_MS]: String(timeoutMs),
      },
      HostConfig: {
        // The ONLY mount: the per-request work dir, read-write, nothing else from the host. The
        // shared volume by NAME plus the subdirectory this request wrote, never a host path — see
        // LAUNCHER_WORK_DIR's comment for why a path cannot work here.
        Mounts: [
          {
            Type: 'volume',
            Source: SANDBOX_WORK_VOLUME,
            Target: '/work',
            ReadOnly: false,
            VolumeOptions: { Subpath: workSubpath },
          },
        ],
        // Explicit remove in `finally` below, not AutoRemove — the orphan-leak note above is
        // exactly why: AutoRemove only fires on a normal container exit, never on a launcher
        // crash, so an explicit, always-attempted remove plus the startup reconciliation pass is
        // the actual guarantee, not a single `--rm`-equivalent flag.
        AutoRemove: false,
        CapDrop: ['ALL'],
        SecurityOpt: ['no-new-privileges'],
        Memory: SANDBOX_DOCKER_MEMORY_MB * 1024 * 1024,
        NanoCpus: Math.round(SANDBOX_DOCKER_CPUS * 1e9),
        PidsLimit: SANDBOX_DOCKER_PIDS_LIMIT,
        // Never host networking. Which network an agent-posture sibling lands on is
        // SANDBOX_NETWORK_ISOLATION's call — see that constant's comment: the tessary service network
        // by default so the agent can reach the backend's MCP door by service name, the dedicated
        // bridge when an operator has opted into cutting it off. Either way the network stays ON
        // (not `--network none`): the agent needs egress for the repo clone and the model-provider
        // call. A non-agent posture still gets no network at all.
        NetworkMode: posture === AGENT_POSTURE ? await sandboxNetwork() : 'none',
      },
    });
    containerId = created.Id;
  } catch (e) {
    cleanupDir();
    throw withMeta(e);
  }

  let stdout = '';
  let stdoutBytes = 0;
  let stderr = '';
  let overflowed = false;
  let killedForTimeout = false;
  let timer;
  const killContainer = () => dockerJson('POST', `/containers/${containerId}/kill`).catch(() => { /* best effort */ });

  try {
    await dockerJson('POST', `/containers/${containerId}/start`);

    const logsRes = await dockerRequest('GET', `/containers/${containerId}/logs?follow=1&stdout=1&stderr=1`, { stream: true });
    const feed = createDockerLogDemuxer(
      (chunk) => {
        stdoutBytes += chunk.length;
        if (stdoutBytes > MAX_STDOUT_BYTES) {
          if (!overflowed) { overflowed = true; killContainer(); }
          return;
        }
        stdout += chunk.toString();
      },
      (chunk) => { stderr += chunk.toString(); },
    );
    logsRes.on('data', feed);
    const logsEnded = new Promise((resolve) => {
      logsRes.once('end', resolve);
      logsRes.once('error', resolve);
    });

    const waitPromise = dockerJson('POST', `/containers/${containerId}/wait`);
    timer = setTimeout(() => { killedForTimeout = true; killContainer(); }, timeoutMs);
    const result = await waitPromise;
    clearTimeout(timer);
    // Give the log stream a short grace window to flush its final frames after the container
    // exits — `wait` resolving does not guarantee the demuxer above has seen every byte yet.
    await Promise.race([logsEnded, new Promise((r) => setTimeout(r, 500))]);

    const elapsedMs = Date.now() - startedAt;
    if (overflowed) {
      throw new Error(`${scriptName} stdout exceeded ${MAX_STDOUT_BYTES} bytes`);
    }
    if (killedForTimeout) {
      const e = new Error(`${scriptName} timed out after ${elapsedMs}ms`);
      e.launcherKind = 'timeout';
      throw e;
    }
    const exitCode = result && result.StatusCode;
    if (exitCode !== 0) {
      console.error(`${scriptName} exit ${exitCode} after ${elapsedMs}ms of ${timeoutMs}ms (container ${containerId})`);
      if (stderr) console.error('--- container stderr ---\n' + scrubToken(stderr).slice(-4000));
      if (stdout) console.error('--- container stdout ---\n' + scrubToken(stdout).slice(0, 2000));
      const e = new Error(`${scriptName} exit ${exitCode}`);
      e.exitCode = exitCode;
      // F1: same failure-envelope extraction as the local backend above.
      const usage = usageFromFailureStdout(stdout);
      if (usage) e.usage = usage;
      throw e;
    }
    return parseScriptOutput(scriptName, stdout);
  } catch (e) {
    if (timer) clearTimeout(timer);
    throw withMeta(e);
  } finally {
    cleanupDir();
    // Belt-and-braces removal on the normal path — see the AutoRemove:false comment above for why
    // this, plus reapOrphanSandboxContainers at the next boot, is the real guarantee.
    try { await dockerJson('DELETE', `/containers/${containerId}?force=true`); } catch { /* best effort */ }
  }
}

// Shared runner for the agentic paths (rca / triage): one FRESH microVM per call, run the named
// script over input.json, tear the sandbox down. Both scripts
// scrub the clone token from their own output, so it's safe to surface (sliced) on this
// launcher console for debugging — never returned to the backend.
//
// Diagnostics on failure: E2B's `commands.run` throws two shapes — a `CommandExitError`
// (non-zero exit; carries .stdout/.stderr) OR, when the command stream is severed mid-run,
// a `SandboxError` (`Unknown` -> "[unknown] terminated") that DROPS the buffered output. To
// stay observable in the latter case we accumulate stdout/stderr ourselves via onStdout/
// onStderr (the error object's copies are empty there), log elapsed time, and run a sandbox
// post-mortem (logSandboxDiagnostics) before re-throwing.
// The backend throws MISSING_CREDENTIALS before ever calling this launcher when a
// project's org has no usable credential, so every well-formed request carries one — but this
// launcher does not trust that promise blindly. A missing or malformed `credential` is rejected
// as `bad_request` (the CALLER sent something unusable) rather than allowed to reach
// providerConfig()/agentEnvs(), which would otherwise build a provider block with a blank
// apiKey/region and fail deep inside the agent run as an opaque auth error with no clue what was
// actually wrong.
const KNOWN_CREDENTIAL_PROVIDERS = new Set(['BEDROCK', 'BEDROCK_MANTLE', ...Object.keys(REQUEST_PROVIDER_TO_MODE)]);
function requireCredential(credential, scriptName) {
  const bad = (why) => {
    const e = new Error(`${scriptName}: missing or invalid credential (${why})`);
    e.launcherKind = 'bad_request';
    throw e;
  };
  if (!credential || typeof credential !== 'object') bad('no credential object on the request');
  if (!KNOWN_CREDENTIAL_PROVIDERS.has(credential.provider)) bad(`unknown provider '${credential.provider}'`);
  if (credential.provider === 'BEDROCK' || credential.provider === 'BEDROCK_MANTLE') {
    if (!(credential.aws_region || '').trim()) bad('BEDROCK/BEDROCK_MANTLE credential is missing aws_region');
    if (!(credential.aws_access_key || '').trim() || !(credential.aws_secret_key || '').trim()) {
      bad('BEDROCK/BEDROCK_MANTLE credential is missing aws_access_key/aws_secret_key — an iam_role-mode'
        + ' credential must never reach this launcher (see AGENTIC_IAM_ROLE_UNSUPPORTED on the backend)');
    }
  } else if (!(credential.api_key || '').trim()) {
    bad(`${credential.provider} credential is missing api_key`);
  }
  // CUSTOM is the one provider with no default base URL to fall back on (see defaultBaseUrlFor),
  // so a blank one is a config error, not a default. Caught HERE because the alternative is a
  // baseURL of '' reaching the SDK, which throws ERR_INVALID_URL deep inside the agent run.
  if (credential.provider === 'CUSTOM' && !(credential.base_url || '').trim()) {
    bad('CUSTOM credential is missing base_url — there is no default endpoint to assume');
  }
}

async function runAgenticScript(scriptName, rawPayload) {
  const timeoutMs = Number(rawPayload.timeout_ms || SANDBOX_TIMEOUT_MS);
  // `credential` is the org's own ProviderCredential row, decrypted by the backend and
  // sent ONLY on this field — see the file-header doc for its shape. It is destructured OUT of
  // `rawPayload` here and never rejoins `payload` below: `payload` is what gets written to
  // input.json (on every backend, including inside the E2B microVM's own filesystem at
  // /home/user/input.json), so a credential secret riding on `payload` would leak onto disk
  // inside the sandbox — the exact leak class UNTRUSTED_POSTURE's no-credential-leak property
  // guards against for the untrusted-content route this one is NOT. `credential` reaches the
  // agent ONLY via env vars (agentEnvs(), below and in runScriptLocally/runScriptInDockerInner),
  // the same channel every provider secret has always traveled on.
  const { credential, ...rest } = rawPayload;
  requireCredential(credential, scriptName);
  // One place qualifies the model, so both backends address the same provider.
  const payload = { ...rest, model: toProviderModel(rest.model, credential) };
  // The qualified id is the one thing about a run that is decided HERE rather than by the backend,
  // and a wrong provider prefix surfaces as an agent-side 404 with no clue where it came from.
  // A model id is neither a secret nor model output, so it is safe on this console.
  console.log(`${scriptName}: model ${rest.model} -> ${payload.model} (provider ${credential.provider})`);
  if (BACKEND === 'local') return runScriptLocally(scriptName, payload, timeoutMs, AGENT_POSTURE, credential);
  if (BACKEND === 'docker') return runScriptInDocker(scriptName, payload, timeoutMs, AGENT_POSTURE, credential);
  // Both surviving scripts carry an `mcp.url` (see the endpoint doc comment at the top of this
  // file). Reject a missing or localhost-pointed callback URL BEFORE spending an E2B sandbox create
  // call: on this backend the microVM cannot reach the host's localhost at all, so letting the run
  // proceed only guarantees a slower, more expensive version of the same failure. The guard is kept
  // conditional rather than unconditional so a future clone-only agentic route does not inherit an
  // MCP requirement it has no use for.
  if (scriptName === 'rca.js' || scriptName === 'triage.js') {
    const mcpUrl = payload.mcp && payload.mcp.url;
    if (!mcpUrl || pointsAtLocalhost(mcpUrl)) {
      const e = new Error(`${scriptName}: mcp.url is missing or unreachable from an E2B microVM `
        + `(got ${mcpUrl ? JSON.stringify(mcpUrl) : 'unset'}) — set a publicly reachable `
        + 'tessary.rca.agentic.mcp-base-url / tessary.classifier.triage-mcp-base-url, or switch '
        + 'SANDBOX_BACKEND to docker/local for development');
      e.launcherKind = 'bad_request';
      throw e;
    }
  }
  const startedAt = Date.now();
  // Diagnostics ride on the thrown error (see buildErrorBody) so the backend stops seeing a
  // context-free 502: which script, which sandbox, how long, against what deadline.
  const meta = { script: scriptName, timeout_ms: timeoutMs };
  const fail = (e) => stampFailure(e, { launcherMeta: { ...meta, elapsed_ms: Date.now() - startedAt } });
  let sbx;
  try {
    sbx = await Sandbox().create(ANALYZER_TEMPLATE, { apiKey: E2B_API_KEY, timeoutMs });
  } catch (e) {
    // No microVM ever existed (quota, missing template, E2B outage). Previously indistinguishable
    // from an agent that ran and crashed, since both ended as the same bare 502.
    console.error(`${scriptName}: sandbox create failed after ${Date.now() - startedAt}ms: ${(e && e.name) || 'Error'}: ${scrubToken(String((e && e.message) || e)).slice(0, 500)}`);
    throw fail(e);
  }
  const sandboxId = sbx.sandboxId;
  meta.sandbox_id = sandboxId;
  console.log(`${scriptName}: sandbox ${sandboxId} created (timeoutMs=${timeoutMs})`);
  // Capture streamed output ourselves — the severed-stream error path discards the buffers.
  let outBuf = '';
  let errBuf = '';
  try {
    await sbx.files.write('/home/user/input.json', JSON.stringify(payload));
    let res;
    try {
      res = await sbx.commands.run(`node /home/user/${scriptName} /home/user/input.json`, {
        timeoutMs,
        envs: agentEnvs(credential, payload.model),
        onStdout: (d) => { outBuf += d; },
        onStderr: (d) => { errBuf += d; },
      });
    } catch (e) {
      const err = e || {};
      const elapsedMs = Date.now() - startedAt;
      // Lead the log line with the classification (timeout vs script_exit vs orchestration): it is
      // what decides where to look next, and the elapsed/deadline pair is what exposed the runs
      // that burn their full per-grader budget and return nothing.
      const cls = classifyFailure(err, elapsedMs, timeoutMs);
      // Prefer the error's own buffers (CommandExitError carries them); fall back to ours
      // (the "terminated" SandboxError drops them, so our capture is the only copy).
      const stderr = (err.stderr ?? err.result?.stderr ?? errBuf ?? '').toString();
      const stdout = (err.stdout ?? err.result?.stdout ?? outBuf ?? '').toString();
      console.error(`${scriptName} ${cls.kind} after ${elapsedMs}ms of ${timeoutMs}ms (sandbox ${sandboxId}): ${err.name || 'Error'}: ${scrubToken(String(err.message || '')).slice(0, 500)}`);
      if (stderr) console.error('--- sandbox stderr ---\n' + scrubToken(stderr).slice(-4000));
      if (stdout) console.error('--- sandbox stdout ---\n' + scrubToken(stdout).slice(0, 2000));
      await logSandboxDiagnostics(sandboxId);
      // F1: same failure-envelope extraction as the other two backends — CommandExitError is the
      // NORMAL shape a triage/rca non-zero exit takes here (see the defensive res.exitCode branch
      // below for the abnormal one), so this is the primary site for the E2B backend, not a fallback.
      const usage = usageFromFailureStdout(stdout);
      if (usage && e && typeof e === 'object') e.usage = usage;
      throw e;
    }
    if (res.exitCode !== 0) {
      // Defensive: E2B normally throws CommandExitError on a non-zero exit. stderr stays on this
      // console — the error's message is now published as `detail`.
      console.error(`${scriptName} exit ${res.exitCode} after ${Date.now() - startedAt}ms (sandbox ${sandboxId})`);
      if (res.stderr) console.error('--- sandbox stderr ---\n' + scrubToken(res.stderr).slice(-4000));
      const e = new Error(`${scriptName} exit ${res.exitCode}`);
      e.exitCode = res.exitCode;
      const usage = usageFromFailureStdout(res.stdout);
      if (usage) e.usage = usage;
      throw e;
    }
    return parseScriptOutput(scriptName, res.stdout);
  } catch (e) {
    throw fail(e);
  } finally {
    try { await sbx.kill(); } catch { /* best effort */ }
  }
}

// Agentic RCA: one fresh microVM materializes the finding's dossier as files and runs the agent
// over it, wired to the platform's MCP surface via the short-lived key in payload.mcp (scrubbed
// from all output, never returned to the backend) — that door is how it reads any trace at all.
// payload.clone_url is OPTIONAL and adds ./repo/ when the project has an integration: the repo is
// where a located change lives, but an evidence-only project still gets its investigation.
// Read-only: no working-tree change collection.
function runRca(payload) {
  return runAgenticScript('rca.js', payload);
}

// Layer-2 triage: one fresh microVM materializes the finding's two-file dossier and runs the agent
// over it, wired to the platform's MCP surface via the short-lived key in payload.mcp (scrubbed from
// all output, never returned to the backend). The ONLY agentic route with no clone at all: triage
// rules on whether a claim about production traffic is true, which no repository can settle, so this
// route must never grow a clone_url leg back.
function runTriage(payload) {
  return runAgenticScript('triage.js', payload);
}

function readBody(req) {
  return new Promise((resolve, reject) => {
    let size = 0;
    const chunks = [];
    req.on('data', (c) => {
      size += c.length;
      // `bad_request` so the 502 says the CALLER sent something unusable, rather than blaming the
      // sandbox — these two never touch E2B at all.
      if (size > MAX_BODY_BYTES) { reject(stampFailure(new Error('body too large'), { launcherKind: 'bad_request' })); req.destroy(); return; }
      chunks.push(c);
    });
    req.on('end', () => {
      // Note the parse error is discarded, not forwarded: JSON.parse quotes the offending input.
      try { resolve(JSON.parse(Buffer.concat(chunks).toString('utf8') || '{}')); }
      catch { reject(stampFailure(new Error('invalid json body'), { launcherKind: 'bad_request' })); }
    });
    req.on('error', reject);
  });
}

function send(res, status, obj) {
  const body = JSON.stringify(obj);
  res.writeHead(status, { 'Content-Type': 'application/json' });
  res.end(body);
}

const server = http.createServer(async (req, res) => {
  if (req.method === 'GET' && req.url === '/healthz') return send(res, 200, { ok: true });

  if (req.method !== 'POST'
      || (req.url !== '/rca' && req.url !== '/triage')) {
    return send(res, 404, { error: 'not found' });
  }
  const auth = req.headers['authorization'] || '';
  if (!SANDBOX_API_KEY || auth !== `Bearer ${SANDBOX_API_KEY}`) {
    return send(res, 401, { error: 'unauthorized' });
  }
  try {
    const payload = await readBody(req);
    let out;
    if (req.url === '/rca') out = await runRca(payload);
    else out = await runTriage(payload);
    return send(res, 200, out);
  } catch (e) {
    // Transport/orchestration failure.
    //
    // The body is the classified, scrubbed envelope from buildErrorBody — small enough to log
    // verbatim on the backend, and carrying the one bit that used to be lost entirely: whether the
    // run hit its deadline. It never contains the clone URL, a token, model output, or repo
    // content; the full (sliced, scrubbed) sandbox output stays on this console, joinable via
    // sandbox_id.
    const body = buildErrorBody(e);
    console.error(`${req.url} failed: kind=${body.kind} timeout=${body.timeout} class=${body.error_class}`
      + (body.script ? ` script=${body.script}` : '')
      + (body.sandbox_id ? ` sandbox=${body.sandbox_id}` : '')
      + (body.elapsed_ms !== undefined ? ` elapsed=${body.elapsed_ms}ms` : '')
      + (body.timeout_ms !== undefined ? ` deadline=${body.timeout_ms}ms` : '')
      + ` detail=${body.detail}`);
    return send(res, 502, body);
  }
});

// Fail at boot, not on the first /analyze an hour later.
//
// The AGENT_PROVIDER boot checks that used to live here (a misspelled mode, a mode with no
// credential set) are GONE with the knob itself — there is no deployment-wide provider
// mode left to validate at boot; every /rca and /triage's credential is validated per-request by
// requireCredential() instead, since it is now per-request data, not a deployment property.
//
// A missing SANDBOX_WORK_VOLUME would otherwise surface only when the Engine API rejects a mount
// with an empty source, one confusing request at a time — see LAUNCHER_WORK_DIR's own comment for
// the full mechanism this guards.
if (BACKEND === 'docker' && !SANDBOX_WORK_VOLUME) {
  console.error('error: SANDBOX_BACKEND=docker requires SANDBOX_WORK_VOLUME (the name of the Docker volume mounted at LAUNCHER_WORK_DIR).');
  console.error('       See docker-compose.yml\'s sandbox-runner service for how the two are wired together.');
  process.exit(1);
}

// Docker backend startup: reconcile before the first request rather than lazily on it, so a
// crash-orphaned container from a prior instance is gone before it can collide with a fresh run's
// resource limits. Best-effort and non-blocking of server.listen below — see each function's own
// comment for why a failure here degrades to a per-request error rather than a boot crash.
if (require.main === module && BACKEND === 'docker') {
  ensureSandboxNetwork()
    .then(reapOrphanSandboxContainers)
    .catch((e) => console.error(`docker backend startup reconciliation failed: ${e && e.message}`));
}

if (require.main === module) server.listen(PORT, () => {
  const detail = BACKEND === 'docker' ? `backend=docker, image=${AGENT_IMAGE}, concurrency=${SANDBOX_DOCKER_CONCURRENCY}`
    : BACKEND === 'local' ? 'backend=local' : `backend=e2b, template=${ANALYZER_TEMPLATE}`;
  // No more deployment-wide provider note — every request's provider now rides on its
  // own `credential.provider`, not a boot-time env var.
  console.log(`sandbox-runner launcher listening on :${PORT} (${detail})`);
});

// Test seam only (test/sandbox-posture.test.js): requiring this module never listens or touches Docker.
module.exports = {
  childEnvFor,
  AGENT_POSTURE,
  UNTRUSTED_POSTURE,
  // The provider-dispatch seam, exported for test/provider-dispatch.test.js — see that file's
  // header for why each of these needs direct coverage rather than only the end-to-end request tests.
  toProviderModel,
  providerConfig,
  agentEnvs,
};
