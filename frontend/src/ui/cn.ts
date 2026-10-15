// SPDX-License-Identifier: Apache-2.0
export function cn(...parts: Array<string | false | null | undefined>): string {
  return parts.filter(Boolean).join(" ");
}

const TYPE_SIZE =
  /(?:^|\s)text-(metric|h1|h2|h3|body|code|small|column-header|label)(?:\s|$)/;

/**
 * Two `text-*` utilities have equal specificity and Tailwind's emit order picks the winner, so a
 * primitive with a default size must stand it down when the caller supplies one.
 */
export function hasTypeSize(className: string | undefined): boolean {
  return className != null && TYPE_SIZE.test(className);
}
