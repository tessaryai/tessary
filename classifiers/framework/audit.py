# SPDX-License-Identifier: Apache-2.0
"""Dataset audit — label-noise, contamination, lexical-shortcut control, prevalence (PROGRAM.md §1④).

Every dataset version passes these checks before it is accepted (the agreement row of the quality
bar lives in ``framework.agreement``). All model-based checks here use a deliberately dumb
TF-IDF + logistic-regression classifier: cheap, deterministic, and — for the shortcut control —
the whole point (if the dumb model rivals the encoder, the data has lexical giveaways and the
encoder learned vocabulary, not the behavior).

Deps: the ``quality`` extra (scikit-learn, cleanlab>=2.9 — Apache-2.0 from 2.9.0, keep the pin) —
imported lazily so the core harness installs without them.
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import TYPE_CHECKING, Sequence

from .metrics import OperatingPoint, operating_point_at_fixed_fp

if TYPE_CHECKING:
    from .schema import EvalItem, LabeledExample

_PREVIEW = 160  # chars of text kept in reports — enough to recognize the row, not the whole doc


def _word_lr_pipeline(seed: int):
    """The shared dumb model: word 1-2gram TF-IDF -> class-weighted logistic regression."""
    from sklearn.feature_extraction.text import TfidfVectorizer  # lazy — quality extra
    from sklearn.linear_model import LogisticRegression
    from sklearn.pipeline import make_pipeline

    return make_pipeline(
        TfidfVectorizer(ngram_range=(1, 2), min_df=2, sublinear_tf=True),
        LogisticRegression(max_iter=2000, class_weight="balanced", random_state=seed),
    )


# ---------------------------------------------------------------------------- label noise


@dataclass(slots=True)
class FlaggedRow:
    index: int
    text: str  # preview
    given_label: int
    suggested_label: int
    quality: float  # cleanlab label-quality score in [0,1]; lower = more suspect

    def render(self) -> str:
        return (
            f"  [{self.index}] given={self.given_label} suggested={self.suggested_label} "
            f"quality={self.quality:.3f}  {self.text!r}"
        )


@dataclass(slots=True)
class NoiseReport:
    n: int
    n_flagged: int
    flagged: list[FlaggedRow]  # worst-first, truncated to max_flagged

    def render(self) -> str:
        head = f"label-noise: {self.n_flagged}/{self.n} rows flagged for hand review"
        return "\n".join([head, *(f.render() for f in self.flagged)])


def label_noise(examples: "Sequence[LabeledExample]", *, folds: int = 5, seed: int = 13, max_flagged: int = 100) -> NoiseReport:
    """Confident-learning pass: flag rows whose label the out-of-sample model confidently rejects.

    The flagged tail is a review queue, not a verdict — a human looks at every flagged row before
    the dataset version is accepted (PROGRAM.md §1④). Needs both classes present and enough rows
    for ``folds``-fold cross-validation.
    """
    import numpy as np
    from cleanlab.filter import find_label_issues  # lazy — quality extra
    from cleanlab.rank import get_label_quality_scores
    from sklearn.model_selection import StratifiedKFold, cross_val_predict

    texts = [ex.text for ex in examples]
    labels = np.array([ex.label for ex in examples])
    if len(set(labels.tolist())) < 2:
        raise ValueError("label_noise needs both classes present")
    if min((labels == y).sum() for y in set(labels.tolist())) < folds:
        raise ValueError(f"label_noise needs >= {folds} rows per class for {folds}-fold CV")

    probs = cross_val_predict(
        _word_lr_pipeline(seed),
        texts,
        labels,
        cv=StratifiedKFold(n_splits=folds, shuffle=True, random_state=seed),
        method="predict_proba",
        n_jobs=-1,
    )
    issue_indices = find_label_issues(labels=labels, pred_probs=probs, return_indices_ranked_by="self_confidence")
    quality = get_label_quality_scores(labels=labels, pred_probs=probs)
    flagged = [
        FlaggedRow(
            index=int(i),
            text=texts[i][:_PREVIEW],
            given_label=int(labels[i]),
            suggested_label=int(probs[i].argmax()),
            quality=float(quality[i]),
        )
        for i in issue_indices[:max_flagged]
    ]
    return NoiseReport(n=len(texts), n_flagged=len(issue_indices), flagged=flagged)


# ---------------------------------------------------------------------------- contamination


@dataclass(slots=True)
class DupPair:
    index_a: int
    index_b: int
    similarity: float  # 1.0 for exact (after normalization)
    kind: str  # "exact" | "near"
    text_a: str  # preview
    text_b: str

    def render(self) -> str:
        return f"  {self.kind} sim={self.similarity:.3f}  a[{self.index_a}]={self.text_a!r}  b[{self.index_b}]={self.text_b!r}"


@dataclass(slots=True)
class DupReport:
    mode: str  # "within" | "cross"
    n_a: int
    n_b: int
    n_exact: int
    n_near: int
    pairs: list[DupPair]  # truncated to max_pairs

    @property
    def clean(self) -> bool:
        return self.n_exact == 0 and self.n_near == 0

    def render(self) -> str:
        head = f"duplicates ({self.mode}): exact={self.n_exact} near={self.n_near} over a={self.n_a} b={self.n_b}"
        return "\n".join([head, *(p.render() for p in self.pairs)])


def _normalize(text: str) -> str:
    return " ".join(text.lower().split())


def duplicates(
    texts_a: Sequence[str],
    texts_b: Sequence[str] | None = None,
    *,
    threshold: float = 0.85,
    max_pairs: int = 200,
) -> DupReport:
    """Exact + near duplicates, within one set (``texts_b=None``) or across two (train vs eval).

    The contamination bar is **zero overlap** between train and every gold eval set. Exact =
    equality after lowercase/whitespace normalization; near = char 3-5gram TF-IDF cosine >=
    ``threshold``. The default 0.85 is calibrated to catch suffix/prefix-append variants of short
    conversational texts (measured ~0.90 for "…" vs "… at all"); flagged pairs are a human review
    queue, so mild over-flagging is the intended failure mode. Cross-set checks compare every
    train row against every eval row.
    """
    from sklearn.feature_extraction.text import TfidfVectorizer  # lazy — quality extra

    cross = texts_b is not None
    b_texts = list(texts_b) if cross else list(texts_a)
    a_texts = list(texts_a)

    pairs: list[DupPair] = []
    n_exact = n_near = 0

    norm_b: dict[str, list[int]] = {}
    for j, t in enumerate(b_texts):
        norm_b.setdefault(_normalize(t), []).append(j)
    for i, t in enumerate(a_texts):
        for j in norm_b.get(_normalize(t), []):
            if not cross and j <= i:  # within-set: upper triangle only
                continue
            n_exact += 1
            if len(pairs) < max_pairs:
                pairs.append(DupPair(i, j, 1.0, "exact", a_texts[i][:_PREVIEW], b_texts[j][:_PREVIEW]))

    vec = TfidfVectorizer(analyzer="char_wb", ngram_range=(3, 5), min_df=1)
    matrix_b = vec.fit_transform(b_texts)  # L2-normalized rows -> dot product = cosine
    matrix_a = vec.transform(a_texts)
    chunk = 1024
    for start in range(0, matrix_a.shape[0], chunk):
        sims = (matrix_a[start : start + chunk] @ matrix_b.T).tocoo()
        for i_off, j, sim in zip(sims.row, sims.col, sims.data):
            i = start + int(i_off)
            j = int(j)
            if sim < threshold or (not cross and j <= i):
                continue
            if _normalize(a_texts[i]) == _normalize(b_texts[j]):
                continue  # already counted as exact
            n_near += 1
            if len(pairs) < max_pairs:
                pairs.append(DupPair(i, j, float(sim), "near", a_texts[i][:_PREVIEW], b_texts[j][:_PREVIEW]))

    return DupReport(
        mode="cross" if cross else "within",
        n_a=len(a_texts),
        n_b=len(b_texts),
        n_exact=n_exact,
        n_near=n_near,
        pairs=pairs,
    )


# ---------------------------------------------------------------------------- shortcut control


def shortcut_control(
    train: "Sequence[LabeledExample]",
    evalset: "Sequence[EvalItem]",
    *,
    max_fp: float,
    seed: int = 13,
) -> OperatingPoint:
    """Fit the dumb TF-IDF model on the training set and grade it on a gold eval set.

    Read it side by side with the encoder's operating point at the same ``max_fp``: if the dumb
    model is within a few points of the encoder, the dataset has giveaway vocabulary — regenerate
    with more lexical diversity (hard positives without the obvious words, hard negatives with
    them) before trusting the encoder's number.
    """
    pipe = _word_lr_pipeline(seed)
    pipe.fit([ex.text for ex in train], [ex.label for ex in train])
    positive_col = list(pipe.classes_).index(1)
    scores = [float(p[positive_col]) for p in pipe.predict_proba([item.text for item in evalset])]
    return operating_point_at_fixed_fp([item.label for item in evalset], scores, max_fp)


# ---------------------------------------------------------------------------- prevalence


@dataclass(slots=True)
class Prevalence:
    n: int
    n_pos: int

    @property
    def rate(self) -> float:
        return self.n_pos / self.n if self.n else 0.0

    def render(self) -> str:
        return f"prevalence: {self.n_pos}/{self.n} positive ({self.rate:.1%})"


def prevalence(rows: "Sequence[LabeledExample | EvalItem]") -> Prevalence:
    """Class balance, reported per dataset version. Eval sets should sit near realistic prevalence
    (or be sliced); training sets may be balanced — the trainer class-weights the loss."""
    return Prevalence(n=len(rows), n_pos=sum(1 for r in rows if r.label == 1))
