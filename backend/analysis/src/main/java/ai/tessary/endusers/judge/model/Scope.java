// SPDX-License-Identifier: Apache-2.0
package ai.tessary.endusers.judge.model;

import ai.tessary.endusers.model.ScopeKind;
import org.jspecify.annotations.Nullable;

/** The IDs an event covers: all IDs, or the IDs of one shape group or account cluster named by {@code group}. */
public record Scope(ScopeKind kind, @Nullable String group) {}
