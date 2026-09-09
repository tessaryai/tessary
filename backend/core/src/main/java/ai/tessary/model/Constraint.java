// SPDX-License-Identifier: Apache-2.0
package ai.tessary.model;

public record Constraint(String kind, String description, String enforcement) {
    public Constraint {
        if (enforcement == null) enforcement = "deterministic";
    }
}
