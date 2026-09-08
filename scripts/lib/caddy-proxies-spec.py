#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Every operation in the open OpenAPI spec must be proxied to the backend by a Caddy config.

The gap this closes (#1255) is silent by construction: an unproxied /v1/... path does not 404, it
falls through to the SPA and is answered by index.html with a 200, so a self-hoster sees a page of
HTML where an API response belongs and nothing anywhere reports an error. A controller reaching the
spec without reaching the matcher is therefore invisible until someone calls the route in anger.

Reads the `@backend` matcher's tokens rather than the whole file, and applies Caddy's own `path`
semantics: a token ending in `/*` matches that prefix (and the bare prefix itself), anything else is
an exact match.
"""

import json
import sys

def matcher_tokens(config):
    """The path tokens of every line that BEGINS with `path `.

    Caddy's other matchers here are written inline (`@hashed path /assets/*`,
    `@document not path /assets/*`), so they begin with their matcher name or with `not` and are
    excluded by this test rather than by naming them. The `/assets` filter below is belt-and-braces
    for a future named matcher written as a standalone `path` line, which would otherwise be
    vacuumed into the @backend token set and could mask a real gap.
    """
    tokens = []
    with open(config, encoding="utf-8") as handle:
        for line in handle:
            line = line.strip()
            if not line.startswith("path "):
                continue
            tokens += [t for t in line.split()[1:] if not t.startswith("/assets")]
    return tokens

def proxied(tokens, path):
    for token in tokens:
        if token == path:
            return True
        # Caddy's `/x/*` requires the literal `/x/` before the wildcard, so it matches `/x/anything`
        # and `/x/` but NOT a bare `/x` — which would fall through to the SPA exactly as #1255
        # describes. Reporting the bare prefix as covered would be this checker passing a config that
        # is still broken, which is worse than having no checker.
        if token.endswith("/*") and path.startswith(token[:-1]):
            return True
    return False

def main(argv):
    # --internal PATH: a spec operation that must NOT be reachable through the proxy. Asserted in
    # both directions — the path has to still exist in the spec, so the declaration cannot rot into a
    # silent exemption, and it has to stay OUT of the matcher, so nobody widens the public surface by
    # reflex when this checker reports it missing.
    internal = set()
    argv = list(argv)
    while "--internal" in argv:
        i = argv.index("--internal")
        internal.add(argv[i + 1])
        del argv[i : i + 2]

    spec, configs = argv[1], argv[2:]
    with open(spec, encoding="utf-8") as handle:
        paths = sorted(json.load(handle)["paths"])
    failed = False
    for declared in sorted(internal):
        if declared not in paths:
            print(
                f"check-caddy: RED  --internal {declared} is not an operation in {spec}; "
                "drop the declaration rather than leaving it to cover nothing.",
                file=sys.stderr,
            )
            failed = True
    for config in configs:
        tokens = matcher_tokens(config)
        if not tokens:
            print(f"check-caddy: RED  no @backend path matcher found in {config}", file=sys.stderr)
            failed = True
            continue
        exposed = [p for p in sorted(internal) if proxied(tokens, p)]
        for path in exposed:
            print(
                f"check-caddy: RED  {config} proxies {path}, which is declared internal-only. It is "
                "reached in-network (the caller addresses the backend directly), so routing it "
                "through the proxy only publishes it.",
                file=sys.stderr,
            )
        failed = failed or bool(exposed)
        missing = [p for p in paths if p not in internal and not proxied(tokens, p)]
        for path in missing:
            print(
                f"check-caddy: RED  {config} does not proxy {path}; it would be answered by the SPA "
                "fallback with index.html and a 200. Add its prefix to the @backend matcher.",
                file=sys.stderr,
            )
        failed = failed or bool(missing)
    return 1 if failed else 0

if __name__ == "__main__":
    sys.exit(main(sys.argv))
