// SPDX-License-Identifier: Apache-2.0
import { useMemo, useState } from "react";
import { JsonView } from "@uiw/react-json-view";
import { Download } from "lucide-react";
import { Button, Card, CopyButton } from "../../ui";
import { jsonTheme } from "../components/jsonTheme";

/**
 * The response exactly as the API sent it, no string cut short. `foldDepth` is the deepest level open
 * on arrival, the root being 1, so a long span list starts as one row per span. The tree reads its fold
 * depth only on mount, so Expand all and Collapse all remount it.
 */
export function RawJsonView({ value, fileName, foldDepth }: { value: object; fileName: string; foldDepth: number }) {
  const [fold, setFold] = useState<{ depth: number | false; mount: number }>({ depth: foldDepth, mount: 0 });
  const text = useMemo(() => JSON.stringify(value, null, 2), [value]);
  const refold = (depth: number | false) => setFold((f) => ({ depth, mount: f.mount + 1 }));

  const download = () => {
    const url = URL.createObjectURL(new Blob([text], { type: "application/json" }));
    const a = document.createElement("a");
    a.href = url;
    a.download = fileName;
    a.click();
    setTimeout(() => URL.revokeObjectURL(url));
  };

  return (
    <Card className="border border-border">
      <div className="flex flex-wrap items-center justify-between gap-2.5 border-b border-border py-2.5 pl-4 pr-3">
        <span className="font-mono text-small text-fg">{fileName}</span>
        <div className="flex flex-wrap items-center gap-1.5">
          <Button size="sm" onClick={() => refold(false)}>
            Expand all
          </Button>
          <Button size="sm" onClick={() => refold(1)}>
            Collapse all
          </Button>
          <CopyButton value={text} />
          <Button size="sm" leadingIcon={<Download size={13} strokeWidth={1.8} aria-hidden="true" />} onClick={download}>
            Download
          </Button>
        </div>
      </div>
      <div className="overflow-x-auto px-4 py-3">
        <JsonView
          key={fold.mount}
          value={value}
          style={jsonTheme}
          collapsed={fold.depth}
          shortenTextAfterLength={0}
          displayDataTypes={false}
          enableClipboard={false}
        />
      </div>
    </Card>
  );
}
