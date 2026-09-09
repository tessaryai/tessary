// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.worker;

import ai.tessary.classifier.ClassifierRow;

/**
 * Everything a {@link ClassifierSweep} is handed for one pass: the claimed job and the classifier
 * definition it belongs to.
 *
 * <p>Deliberately two records and nothing else. Every sweep in the tree already took exactly this
 * pair as its two arguments, so the context is the shape the seam had before it was a seam — not a
 * new surface invented for it. What it pointedly does NOT carry is the engine's world: no
 * repositories, no {@code BuiltInClassifierCatalog}, no {@code ClassifierWorker}, no clock. An
 * implementation injects the collaborators it needs like any other Spring bean.
 *
 * <p>Widening this later costs one field. Narrowing it after out-of-tree classifiers have compiled
 * against it costs their authors a release, which is why it starts at the minimum rather than at the
 * convenient maximum.
 *
 * @param job the leased sweep coordinator — cursor, attempt count, project. Its cursor is the
 *     sweep's to advance through {@code ClassifierJobRepository}.
 * @param classifier the per-project classifier definition being swept: its {@code detector} kind,
 *     its {@code configJson} operating point, and its {@code mode}.
 */
public record SweepContext(ClassifierJobRow job, ClassifierRow classifier) {}
