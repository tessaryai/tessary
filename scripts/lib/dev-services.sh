# SPDX-License-Identifier: Apache-2.0
# Which dev-stack services `docker compose up` should start — the SINGLE source of truth,
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
    # every service EXCEPT `classify` (and `compile`, see below), whose first build downloads
    # the encoder weights from a gated HF repo (BAKE_EMBEDDERS bakes ~1.7 GB of them into the
    # dev image) and whose container is capped at 8 GB (mem_limit). The backend itself has no
    # `depends_on: classify` — it only reaches it via TESSARY_OBSERVER_ENCODER_URL — so omitting
    # classify from the `up` list used to be sufficient on its own.
    #
    # `compile` (the SOP-conformance compile service) changed that: it has `depends_on: classify`,
    # and `docker compose up <services>` auto-starts each listed service's dependencies regardless
    # of what else is excluded — so leaving compile in the list silently drags classify back up
    # (full model bake + HF-gated weights) even though its own name was filtered out. compile is
    # also useless without classify: every fit it runs calls classify's /embed, so with classify
    # skipped it can only dead-letter. Exclude both.
    #
    # Since #886 `compile` is declared in tessary-paid/docker-compose.dev.yml, not in the base file,
    # so it is in the merged service list only in the PAID edition. The filter below is unchanged
    # and still correct — it filters the MERGED list — but ONLY because the caller hands us the same
    # `-f` set it later runs `up` with (both entry points build it from scripts/lib/dev-compose.sh).
    # If those two ever diverge, `config --services` stops seeing `compile`, this grep stops
    # matching it, and its `depends_on` drags the 8 GB HF-gated classify container into a slim boot.
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
