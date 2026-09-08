// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.llm.catalog;

import java.util.List;

/**
 * Mantle has no confirmed per-request discovery endpoint reachable from this codebase — {@code
 * MantleProperties} only documents chat-call paths, and there is no mantle equivalent of Bedrock's
 * {@code ListFoundationModels} control-plane call.
 *
 * <p><b>What was actually checked (#939 TASK 2, 2026-09-04) before falling back:</b> {@code
 * scripts/refresh-model-prices.sh} vendors LiteLLM's price book into {@code
 * backend/substrate/.../pricing/litellm-model-prices.json}, refreshed on its own daily cadence, and
 * it DOES carry a versioned mantle model-identity list — as of this run, 15 {@code bedrock_mantle/}
 * entries spanning three makers (OpenAI's GPT-5.4/5.5/5.6 line and gpt-oss, Google's Gemma 4 line,
 * xAI's Grok 4.x), not just Luna/Terra. That is a real, better identity source than what this class
 * uses. It was NOT wired in: {@code llm-runtime} (this module) does not depend on {@code substrate}
 * today, and that dependency direction is not incidental — {@code llm-runtime}'s own module
 * description names it "the only module that talks to a model provider", while {@code substrate} owns
 * the observed-record/ingest path; adding an edge from provider-facing code to the ingest module for
 * one JSON resource would be a real architecture change, not a plumbing detail, and the price book's
 * refresh cadence (daily, pricing-driven) is not the same contract as "what mantle currently serves"
 * (capability-driven) even though today's data happens to agree. Recorded per the corrective brief's
 * instruction to leave a one-line, measured divergence note either way — see tessary-paid/OPEN-CORE.md.
 *
 * <p>So: a small hardcoded allowlist of today's two known mantle models, run through the same
 * {@link SupportedMaker} filter every other lister uses (both are OpenAI, so both pass) — NOT
 * returning empty, which the corrective brief calls out explicitly: that would regress the picker
 * from today's two static entries the moment this ships. {@link ai.tessary.evals.llm.ModelCatalog}'s
 * own static {@code openai.gpt-5.6-luna} / {@code openai.gpt-5.6-terra} entries stay authoritative for
 * capability metadata regardless (this class only supplies the live listing merge input); when mantle
 * gains a real discovery surface, or {@code llm-runtime} gains a legitimate reason to depend on
 * {@code substrate}, this class is the one to replace.
 */
public final class BedrockMantleModelLister implements ProviderModelLister {

    private static final List<ProviderModel> KNOWN_MANTLE_MODELS = List.of(
            new ProviderModel("openai.gpt-5.6-luna", "GPT-5.6 Luna", "OpenAI"),
            new ProviderModel("openai.gpt-5.6-terra", "GPT-5.6 Terra", "OpenAI"));

    @Override
    public List<ProviderModel> list(ResolvedCredential credential) {
        // No network call to make and therefore nothing that can fail per-credential — every org with
        // a mantle credential sees the same fixed list, consistent with it being a hardcoded fallback
        // rather than a real per-account discovery read.
        return KNOWN_MANTLE_MODELS;
    }
}
