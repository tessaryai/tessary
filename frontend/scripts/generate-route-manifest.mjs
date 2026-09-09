#!/usr/bin/env node
// SPDX-License-Identifier: Apache-2.0
// Regenerates the checked-in route manifest (src/routeManifest.generated.json) from the <Route>
// elements in src/App.tsx: a written route manifest covering every open route.
//
// WHY AN AST WALK, NOT A GREP. App.tsx has 69 `path=` occurrences and roughly a tenth of them put
// the attribute on its own line, separated from the opening `<Route` tag (the multi-line
// `settings/import`, `settings/providers`, `settings/mcp-tokens` and `settings/api-keys` routes,
// plus `graders`, `graders/:graderId`, `review`, `review/:queueId`, `observer` and the
// `/orgs/:orgSlug/projects/:projectSlug/*` route). A line-based extractor risks silently missing
// exactly the routes this manifest exists to catch, so this walks the real AST via the TypeScript
// compiler API (already a devDependency — no new package).
//
// "kind" is decided by a simple rule, noted here for `/pricing`: a `<Route>` whose `element` attribute's
// OUTERMOST JSX tag is `<Navigate>` is a "redirect" (it never mounts a component); everything else,
// including the `path="*"` catch-alls that resolve to `<ToProjectSegment>` (a real component, not a
// literal `<Navigate>`), is a "view". That is a deliberate, explicit call on what "route" means
// here, not a mechanical diff of a path count.
//
// Index routes (`<Route index element={...}>`, no `path` attribute) are skipped: they render into
// the parent's outlet and carry no path of their own for this manifest to track.
//
// This manifest adds `fullPath` alongside the original `path`/`kind` fields (which stay untouched — see
// scripts/check-frontend.sh's existing diff). `path` is the raw, un-nested JSX literal, which is
// NOT a navigable URL for most entries: App.tsx nests two <Routes> trees (the top-level one in
// App(), and ProjectShell()'s own, reached only by component *reference* — <ProjectShell/> — not
// JSX nesting), and inside the `settings` layout a third level of relative paths. Two leaves even
// collide as raw `path` ("sources" is both a top-level project redirect AND a settings view;
// literal `path="*"` appears twice, for two unrelated catch-alls) — `fullPath` is what a smoke
// test can actually navigate to and what disambiguates those collisions.
//
// Usage:
//   node scripts/generate-route-manifest.mjs                 # regenerate src/routeManifest.generated.json
//   node scripts/generate-route-manifest.mjs <out-path>       # write there instead (drift-check helper —
//                                                              # scripts/check-frontend.sh points this at a
//                                                              # temp file and diffs it against the checked-in one)
import ts from "typescript";
import { readFileSync, writeFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import path from "node:path";

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const APP_TSX = path.join(__dirname, "..", "src", "App.tsx");
const DEFAULT_OUT = path.join(__dirname, "..", "src", "routeManifest.generated.json");

// ProjectShell()'s own <Routes> tree is reached by component reference (<ProjectShell/> at the
// element of the "/orgs/:orgSlug/projects/:projectSlug/*" <Route> in App(), not by JSX nesting) —
// the AST walk cannot see that link, so the ancestor-path stack is hand-seeded with this constant
// the moment the walk enters the `function ProjectShell()` declaration. Tied to the literal route
// below by an assertion in `generate()`, which throws if that literal ever changes without this
// constant being updated to match.
const PROJECT_SHELL_ROUTE = "/orgs/:orgSlug/projects/:projectSlug/*";
const PROJECT_SHELL_BASE = PROJECT_SHELL_ROUTE.replace(/\/\*$/, "");

// A <Route> whose ENTIRE `path` attribute is the literal string "*" (a bare catch-all, not merely
// ending in "/*") has no navigable URL of its own — the wildcard IS the whole pattern, there is no
// anchor to append a real leaf to. Exactly two routes in App.tsx are this shape (App.tsx's
// settings-layout fallback and its project-level fallback, both kind:view, both resolving to
// ToProjectSegment) and both need a synthetic leaf substituted or they are never render-proofed —
// see the header note. A route whose path merely ENDS in "/*" (e.g. "pipeline/*", or the top-level
// "/orgs/:orgSlug/projects/:projectSlug/*" that mounts ProjectShell) is left untouched: react-router
// matches a literal "*" character in a navigated URL against its own wildcard segment just fine, so
// the raw path is already a valid fullPath and substituting it would collide it with the *inner*
// catch-all it wraps (that collision was caught by the fullPath-uniqueness assertion during
// implementation — see the deviation note recorded elsewhere in the project's history).
const CATCH_ALL_SENTINEL = "__unmatched__";

/** Join an ancestor-path stack (already-substituted segments; PROJECT_SHELL_BASE and any
 * multi-segment entry, e.g. "cases/:caseId", carry internal slashes that need re-splitting) into
 * one absolute fullPath. */
function buildFullPath(stack) {
  const segments = stack.flatMap((seg) => seg.split("/")).filter((seg) => seg.length > 0);
  return "/" + segments.join("/");
}

/** Tag name of a JsxElement or JsxSelfClosingElement, as written (no resolution). */
function jsxTagName(node) {
  const tagNameNode = ts.isJsxElement(node) ? node.openingElement.tagName : node.tagName;
  return tagNameNode.getText();
}

/** The attribute list of a JsxElement (via its opening tag) or a JsxSelfClosingElement. */
function attributesOf(node) {
  const opening = ts.isJsxElement(node) ? node.openingElement : node;
  return opening.attributes.properties;
}

function findAttr(attrs, name) {
  return attrs.find((a) => ts.isJsxAttribute(a) && a.name.getText() === name);
}

/** The single JSX root inside a `{...}` JsxExpression attribute value, or null if there isn't one
 * (a non-JSX expression, or no initializer at all — a boolean-shorthand attribute). */
function jsxRootOfExpression(attr) {
  if (!attr?.initializer || !ts.isJsxExpression(attr.initializer)) return null;
  const expr = attr.initializer.expression;
  if (!expr) return null;
  if (ts.isJsxElement(expr) || ts.isJsxSelfClosingElement(expr) || ts.isJsxFragment(expr)) return expr;
  return null;
}

function kindOf(attrs) {
  const elementAttr = findAttr(attrs, "element");
  const root = jsxRootOfExpression(elementAttr);
  if (root && !ts.isJsxFragment(root) && jsxTagName(root) === "Navigate") return "redirect";
  return "view";
}

function extractRoutes(sourceFile) {
  const routes = [];

  // `stack` is the ancestor <Route> path segments seen so far on this branch of the walk — pushed
  // on entering a <Route> with a `path`, and implicitly scoped back out via the recursive call
  // (each call gets its own `stack` argument, so returning from a subtree "pops" for free).
  function visit(node, stack) {
    if (ts.isJsxElement(node) || ts.isJsxSelfClosingElement(node)) {
      if (jsxTagName(node) === "Route") {
        const attrs = attributesOf(node);
        const pathAttr = findAttr(attrs, "path");
        // Index routes (no `path`) and any future dynamic `path={...}` (none exist today) are
        // skipped rather than guessed at — see the header note.
        if (pathAttr?.initializer && ts.isStringLiteral(pathAttr.initializer)) {
          const segment = pathAttr.initializer.text;
          // Only a BARE "*" gets the sentinel — see CATCH_ALL_SENTINEL's comment for why a
          // trailing "/*" on a longer path (e.g. "pipeline/*") is left as-is.
          const stackSegment = segment === "*" ? CATCH_ALL_SENTINEL : segment;
          const childStack = [...stack, stackSegment];
          routes.push({ path: segment, kind: kindOf(attrs), fullPath: buildFullPath(childStack) });
          ts.forEachChild(node, (child) => visit(child, childStack));
          return;
        }
      }
    }
    // See PROJECT_SHELL_BASE above: ProjectShell()'s own <Routes> is reached by component
    // reference, not JSX nesting, so this is the one place the stack has to be seeded by hand
    // rather than inherited from an enclosing <Route>.
    if (ts.isFunctionDeclaration(node) && node.name?.getText() === "ProjectShell") {
      ts.forEachChild(node, (child) => visit(child, [PROJECT_SHELL_BASE]));
      return;
    }
    ts.forEachChild(node, (child) => visit(child, stack));
  }

  visit(sourceFile, []);
  return routes;
}

function generate() {
  const text = readFileSync(APP_TSX, "utf8");
  const sourceFile = ts.createSourceFile(APP_TSX, text, ts.ScriptTarget.Latest, true, ts.ScriptKind.TSX);
  const routes = extractRoutes(sourceFile);
  // Guards PROJECT_SHELL_BASE against silent drift: if App.tsx's own literal route ever changes,
  // this throws instead of quietly seeding ProjectShell's nested fullPaths with a stale prefix.
  if (!routes.some((r) => r.path === PROJECT_SHELL_ROUTE)) {
    throw new Error(
      `generate-route-manifest.mjs: expected App.tsx to declare a <Route path="${PROJECT_SHELL_ROUTE}">` +
        " (the ProjectShell mount point) — update PROJECT_SHELL_ROUTE to match if this route's literal changed.",
    );
  }
  return routes;
}

const outPath = process.argv[2] ?? DEFAULT_OUT;
const routes = generate();
writeFileSync(outPath, JSON.stringify(routes, null, 2) + "\n");
if (outPath === DEFAULT_OUT) {
  console.log(`wrote ${path.relative(process.cwd(), outPath)} (${routes.length} routes)`);
}
