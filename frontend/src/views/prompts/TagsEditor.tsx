// SPDX-License-Identifier: Apache-2.0
import { useState } from "react";
import { Badge, Button, Input } from "../../ui";
import { Note } from "./promptBits";

const TAG = /^\S{1,64}$/;
export const TAGS_ERROR = "Use up to 50 tags with no spaces, each at most 64 characters.";

/** Comma separated; blanks and repeats dropped, order kept. */
export function parseTags(text: string): { ok: true; tags: string[] } | { ok: false } {
  const tags = [...new Set(text.split(",").map((t) => t.trim()).filter(Boolean))];
  return tags.length <= 50 && tags.every((t) => TAG.test(t)) ? { ok: true, tags } : { ok: false };
}

/** `onSave` resolves when the tags are stored; the editor stays open when it rejects. */
export function TagsEditor({ tags, onSave }: { tags: string[]; onSave: (tags: string[]) => Promise<unknown> }) {
  const [draft, setDraft] = useState<string | null>(null);
  const [error, setError] = useState(false);

  if (draft === null) {
    return (
      <div className="flex flex-wrap items-center gap-1.5">
        {tags.map((t) => (
          <Badge key={t}>{t}</Badge>
        ))}
        <Button size="sm" variant="ghost" onClick={() => setDraft(tags.join(", "))}>
          Edit tags
        </Button>
      </div>
    );
  }

  const save = () => {
    const parsed = parseTags(draft);
    setError(!parsed.ok);
    if (parsed.ok) onSave(parsed.tags).then(() => setDraft(null), () => {});
  };

  return (
    <div className="flex flex-col gap-1.5">
      <div className="flex items-center gap-2">
        <Input
          aria-label="Tags"
          placeholder="Separate tags with commas"
          className="max-w-md"
          value={draft}
          onChange={(e) => setDraft(e.target.value)}
        />
        <Button size="sm" variant="primary" onClick={save}>
          Save
        </Button>
        <Button
          size="sm"
          variant="ghost"
          onClick={() => {
            setDraft(null);
            setError(false);
          }}
        >
          Cancel
        </Button>
      </div>
      {error && <Note tone="error">{TAGS_ERROR}</Note>}
    </div>
  );
}
