# Vale style checks

Prose linting for `docs/`, built on [Vale](https://vale.sh). It checks a deliberately narrow
set of mechanical rules pulled from `/handbook`: the ones a linter can apply consistently
without reading for meaning. Everything else in the handbook (voice, document structure,
terminology choices that depend on context) is left to human and agent review.

## Install

```bash
brew install vale
```

See the [Vale install docs](https://vale.sh/docs/vale-cli/installation/) for other platforms.

## Run it

```bash
docs/vale/check.sh              # lint everything under docs/
docs/vale/check.sh path/to/file.mdx
```

This runs `vale --config docs/vale/.vale.ini`. `MinAlertLevel` is set to `suggestion`, so all
three severities show up locally: `error` (fails a `--minAlertLevel=error` run), `warning`, and
`suggestion`. Vale's own exit code is nonzero if it finds anything, regardless of level. CI
should pass `--minAlertLevel=error` if it only wants hard rules to fail the build.

## Rules included

| Rule | Level | Source |
| --- | --- | --- |
| `NoEmDash` | error | [Writing style guide § Dashes](../../handbook/writing-style-guide.md#dashes) |
| `AmericanEnglish` | error | [Writing style guide § Use American English](../../handbook/writing-style-guide.md#use-american-english) |
| `SentenceCaseH2` | error | [Writing style guide § Everything within a page](../../handbook/writing-style-guide.md#everything-within-a-page) |
| `SentenceCaseH3` | error | same |
| `SentenceCaseH4` | error | same |
| `TitleCaseH1` | warning | [Writing style guide § Top-level page titles](../../handbook/writing-style-guide.md#top-level-page-titles) |
| `AmbiguousDates` | warning | [Writing style guide § Dates and times](../../handbook/writing-style-guide.md#dates-and-times) |
| `GenericLinkText` | warning | [Writing style guide § Links](../../handbook/writing-style-guide.md#links) |
| `WordsToAvoid` | suggestion | [Writing style guide § Write clearly](../../handbook/writing-style-guide.md#write-clearly) and [§ Words and phrases to avoid](../../handbook/writing-style-guide.md#words-and-phrases-to-avoid) |
| `AvoidPercentWord` | suggestion | [Writing style guide § Numbers](../../handbook/writing-style-guide.md#numbers) |
| `SentenceStartNumber` | suggestion | [Writing style guide § Numbers](../../handbook/writing-style-guide.md#numbers) |
| `AvoidTheUser` | suggestion | [Writing style guide § Address the reader directly](../../handbook/writing-style-guide.md#address-the-reader-directly) |
| `AvoidMetaIntro` | suggestion | [Documentation style guide § Start pages directly](../../handbook/documentation-style-guide.md#start-pages-directly) |
| `VaguePrerequisites` | suggestion | [Documentation style guide § State prerequisites precisely](../../handbook/documentation-style-guide.md#state-prerequisites-precisely) |
| `InterfaceByAppearance` | suggestion | [Writing style guide § Interface references](../../handbook/writing-style-guide.md#interface-references) |

`error` rules are unconditional in the handbook: never use an em dash, always use American
spelling, section headings are always sentence case. `warning` and `suggestion` rules cover
guidance the handbook itself treats as conditional ("unless required by context," "not
automatically forbidden"), or that a linter can only approximate. Vale still surfaces them, just
without blocking on them.

### Heading case, in more detail

`SentenceCaseH2`/`H3`/`H4` and `TitleCaseH1` use Vale's `capitalization` extension, one rule per
heading level (Vale doesn't support matching several levels from one rule, and silently no-ops
if you try). Each carries an `exceptions` list of proper nouns and acronyms (`Tessary`, `API`,
`GitHub`, and so on) that keep their own casing inside a sentence-case heading; extend that list
in the relevant style file as new terms show up.

`TitleCaseH1` is a `warning`, not an `error`, for two reasons:

- Mintlify pages set the H1 from frontmatter `title`, and `mintlify-guide.md` says not to add a
  `#` heading in the page body. Vale's `heading.h1` scope only sees a literal body `#` heading;
  it does not read frontmatter, so this rule is a no-op on real `.mdx` pages today. It still
  catches a stray body `#` in a `.md` file.
- Whether a given file even counts as a "top-level page" is itself a judgment call. This repo's
  own `docs/AGENTS.md`, `docs/mintlify-guide.md`, and `docs/vale/README.md` all use sentence-case
  H1s and all get flagged by this rule; they're meta-documentation about the docs tooling, not
  published product pages, so those flags were left as-is rather than "fixed."

## Left out on purpose

These handbook rules aren't encoded here, most because applying them correctly requires reading
the sentence for meaning, not just matching text, so a linter would produce more false positives
than useful signal. A few are Vale limitations rather than editorial ones:

- **Bare URLs as link text** ("don't expose a full URL as link text when descriptive text is
  available"). This one is unambiguous, but Vale strips URL-shaped text out of both the default
  text scope and the `link` scope before rules see it, so there's no reliable way to catch it
  with an existence rule in this setup.
- **MDX frontmatter title case**: see the `TitleCaseH1` limitation above.
- **Voice and tone** (directness, taking positions, separating fact from inference): this is a
  quality of the writing, not a pattern in the text.
- **Document type and structure** (tutorial vs. how-to vs. reference, "recommend one path first,"
  expected results after steps): requires understanding what a page is trying to do.
- **Oxford comma**: reliably identifying "the final item in a series" needs parsing the
  sentence, not just its punctuation.
- **Colon capitalization** ("unless it begins a complete sentence or is a proper name"): the
  exception is exactly the part that needs judgment.
- **`open source` vs. `open-source` hyphenation**: depends on whether the phrase is a noun or a
  modifier in that specific sentence.
- **Glossary term misuse** (`agent`/`model`, `finding` vs. `alert`/`anomaly`/`incident`/`case`):
  whether a word is being used as the Tessary concept, or just as an ordinary English word in
  an unrelated sentence, isn't something regex can tell apart. `case`, `incident`, and `model`
  in particular are common outside Tessary's vocabulary.
- **`just` and `easy`** were dropped from `WordsToAvoid` for the same reason: they're common
  ordinary-English words, and most of their occurrences aren't the minimizing sense the handbook
  is warning about. `obvious`, `simple`, and `trivial` were kept despite the same risk because
  the handbook names them explicitly (troubleshooting section); expect noise from compounds like
  "non-obvious" and "simplify," and treat hits as prompts to look, not violations.
- **"will" used to describe normal behavior** instead of the present tense: the misuse (`will`
  for routine behavior) and the correct use (`will` for something that actually happens later)
  aren't distinguishable by pattern; "will" is too common a word to flag on its own even at
  suggestion level.
- **Number formatting** (numerals vs. spelled out, four-digit comma grouping) beyond the
  sentence-start case: needs distinguishing measurements from things like years, ports, and IDs
  that shouldn't get commas or be spelled out.

If a left-out rule turns out to have a genuinely mechanical shape after all, add it as a new
style file in `styles/Tessary/` rather than stretching one of the above.

## Considered, not added: third-party style packs

Vale's [package hub](https://vale.sh/hub) has ready-made styles like `write-good` (weasel words,
passive voice, generally) and `alex` (inclusive-language checks). Neither is wired in here. They
overlap with rules the handbook already states more specifically (and sometimes differently, for
example on passive voice, which the handbook allows when the result matters more than the
actor), so layering one in wholesale risks contradicting `Tessary` rather than reinforcing it.
Worth a look if `docs/` grows past what the handbook-derived rules cover, but as a deliberate
addition, not a default.
