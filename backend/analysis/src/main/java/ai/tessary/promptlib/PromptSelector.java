// SPDX-License-Identifier: Apache-2.0
package ai.tessary.promptlib;

/** Which version a fetch wants: a number, a stored label, or the highest version. */
public sealed interface PromptSelector {

    record Version(int number) implements PromptSelector {}

    record Label(String label) implements PromptSelector {}

    record Latest() implements PromptSelector {}
}
