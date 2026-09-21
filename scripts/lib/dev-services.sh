# SPDX-License-Identifier: Apache-2.0
# Which dev-stack services `docker compose up` should start: the single source of truth,
# sourced by scripts/dev.sh (task dev / dev:slim) and scripts/dev-up.sh (task dev:up).
#
# One derivation, because two would diverge in the one direction that matters: an entry point
# that forgets the filter still *looks* like it starts the stack, right up to the point where
# classify's build fails on the HF-gated encoder weights and zero containers exist.
#
# Usage: UP_SERVICES="$(dev_up_services "<compose command>")"
# Prints a space-separated service list, or nothing at all when every service should start.
# The notice about what was skipped goes to stderr so it can't end up in the list.
dev_up_services() {
    local compose="$1"

    # Slim mode (task dev:slim, or TESSARY_SKIP_CLASSIFY=1 in front of any dev task): bring up
    # every service except `classify` (and `compile`, see below), whose first build downloads
    # the encoder weights from a gated HF repo (BAKE_EMBEDDERS bakes ~1.7 GB of them into the
    # dev image) and whose container is capped at 8 GB (mem_limit). The backend itself has no
    # `depends_on: classify`, it only reaches it via TESSARY_OBSERVER_ENCODER_URL, so omitting
    # classify from the `up` list alone would not be enough.
    #
    # `compile` (the SOP-conformance compile service) has `depends_on: classify`, and
    # `docker compose up <services>` auto-starts each listed service's dependencies regardless of
    # what else is excluded, so leaving compile in the list silently drags classify back up (full
    # model bake + HF-gated weights) even though its own name was filtered out. compile is also
    # useless without classify: every fit it runs calls classify's /embed, so with classify skipped
    # it can only dead-letter. Exclude both.
    #
    # `compile` may be declared in a compose file outside this repo's base one, in which case it
    # only shows up in the merged service list when that file is included. The filter below is
    # unchanged and still correct, it filters the merged list, but only because the caller hands us
    # the same `-f` set it later runs `up` with (both entry points build it from
    # scripts/lib/dev-compose.sh). If those two ever diverge, `config --services` stops seeing
    # `compile`, this grep stops matching it, and its `depends_on` drags the 8 GB HF-gated classify
    # container into a slim boot.
    if [ "${TESSARY_SKIP_CLASSIFY:-0}" != "1" ]; then
        return 0
    fi

    echo "slim mode — skipping the classify + compile services (no gated encoder-weight download, no 8 GB container)." >&2
    # Derive the list from compose itself (grep it out) so new services are picked up
    # automatically. `config --services` already omits profile-gated services (the classifier
    # tooling behind `--profile classifiers`), which is what we want: an explicit service list
    # would otherwise opt them in. Service names are bare tokens, so a word-split string is safe
    # here and avoids bash 3.2's empty-array-under-`set -u` pitfall on stock macOS.
    $compose config --services | grep -vxE 'classify|compile' | tr '\n' ' '
}

# The classify image bake reads HF_TOKEN through docker-compose.dev.yml's `hf_token` secret, which
# is sourced from the invoking shell's REAL environment (not .env). A developer who has done
# `hf auth login` already holds the token at ~/.cache/huggingface/token; default to it when the
# variable is unset so `task dev` bakes the gated heads without a second copy of the credential
# in a shell profile. An explicitly set HF_TOKEN wins; an empty file or no file yields nothing, and
# the bake then skips gated heads exactly as a keyless build does (classify-service/Dockerfile).
# Printed, not exported: the caller scopes it to the one `docker compose up --build` that needs it,
# so the credential never lands in a tmux server's environment or any other child process.
dev_hf_token() {
    if [ -n "${HF_TOKEN:-}" ]; then
        printf '%s' "$HF_TOKEN"
    elif [ -s "${HF_HOME:-$HOME/.cache/huggingface}/token" ]; then
        tr -d '[:space:]' < "${HF_HOME:-$HOME/.cache/huggingface}/token"
    fi
    return 0
}
