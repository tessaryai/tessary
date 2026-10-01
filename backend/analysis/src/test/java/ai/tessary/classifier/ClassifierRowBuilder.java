// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier;

import org.jspecify.annotations.Nullable;

public final class ClassifierRowBuilder {

    private final String detector;
    private String id = "sig-1";
    private String projectId = "proj-1";
    private String classifierKey;
    private String name;
    private @Nullable String configJson;
    private boolean builtIn = true;
    private int version = 1;
    private boolean enabled = true;
    private String mode = ClassifierRow.Mode.TRACKING;
    private String at = "now";

    private ClassifierRowBuilder(String detector) {
        this.detector = detector;
        this.classifierKey = detector;
        this.name = detector;
    }

    public static ClassifierRowBuilder of(String detector) {
        return new ClassifierRowBuilder(detector);
    }

    public ClassifierRowBuilder id(String value) {
        this.id = value;
        return this;
    }

    public ClassifierRowBuilder projectId(String value) {
        this.projectId = value;
        return this;
    }

    public ClassifierRowBuilder named(String key, String value) {
        this.classifierKey = key;
        this.name = value;
        return this;
    }

    public ClassifierRowBuilder config(@Nullable String value) {
        this.configJson = value;
        return this;
    }

    public ClassifierRowBuilder custom() {
        this.builtIn = false;
        return this;
    }

    public ClassifierRowBuilder version(int value) {
        this.version = value;
        return this;
    }

    public ClassifierRowBuilder disabled() {
        this.enabled = false;
        return this;
    }

    public ClassifierRowBuilder discovery() {
        this.mode = ClassifierRow.Mode.DISCOVERY;
        return this;
    }

    public ClassifierRowBuilder at(String value) {
        this.at = value;
        return this;
    }

    public ClassifierRow build() {
        return new ClassifierRow(
                id,
                projectId,
                classifierKey,
                name,
                null,
                detector,
                configJson,
                builtIn,
                version,
                enabled,
                mode,
                at,
                at);
    }
}
