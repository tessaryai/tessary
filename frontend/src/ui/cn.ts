// SPDX-License-Identifier: Apache-2.0
export function cn(...parts: Array<string | false | null | undefined>): string {
  return parts.filter(Boolean).join(" ");
}

/** The ten type-scale utilities, for {@link hasTypeSize}. */
const TYPE_SIZE =
  /(?:^|\s)text-(display|metric|h1|h2|h3|body|code|small|column-header|label)(?:\s|$)/;

/**
 * Does this className already pick a size off the type scale?
 *
 * A primitive that hardcodes its own `text-*` silently WINS over one a caller passes: both are
 * font-size utilities of equal specificity, so the winner is whichever Tailwind emits later in the
 * stylesheet, which is the scale's order and not the author's intent. `cn` is a plain join and
 * cannot know that. A primitive with a default size therefore has to stand its default down when
 * the caller supplies one — see `Table`.
 */
export function hasTypeSize(className: string | undefined): boolean {
  return className != null && TYPE_SIZE.test(className);
}
