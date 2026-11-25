// SPDX-License-Identifier: Apache-2.0
/**
 * The Users view: which end users of the customer's agent come back, and which issues come before the ones who do not.
 * An hourly sweep records the user IDs each finding hit; a daily job folds closed UTC days of traces into facts, learns
 * the route classes, runs the v3 logic as pure functions over {@code judge.model} records and stores a snapshot of codes
 * and numbers. A user ID is stored only as its salted key ({@code facts.EndUserKeys}) plus a readable text that leaves
 * with its last trace; no log line carries either. {@code model} holds the shared codes, {@code judge.JudgeConstants}
 * the constants of spec section 10.
 */
package ai.tessary.endusers;
