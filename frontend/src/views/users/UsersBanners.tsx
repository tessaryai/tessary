// SPDX-License-Identifier: Apache-2.0
import { Info } from "lucide-react";
import type { EndUserBanner, EndUserLimitLine } from "../../api/types";
import { bannerText, limitText } from "./usersWords";

/** Board P6: one banner per fact above the funnel, in the order the server ranks them. */
export function UsersBanners({ banners }: { banners: EndUserBanner[] }) {
  if (banners.length === 0) return null;
  return (
    <div className="grid gap-3 md:grid-cols-2">
      {banners.map((b, i) => (
        <div
          key={`${b.code}-${i}`}
          role="note"
          className="flex items-start gap-2.5 rounded-control border border-border-strong px-3.5 py-3 text-body text-fg-secondary"
        >
          <Info size={14} strokeWidth={2} aria-hidden="true" className="mt-1 flex-none text-muted" />
          <span>{bannerText(b)}</span>
        </div>
      ))}
    </div>
  );
}

/** The limit lines under the funnel, in the order the server ranks them. */
export function LimitLines({ limits }: { limits: EndUserLimitLine[] }) {
  if (limits.length === 0) return null;
  return (
    <ul className="flex flex-col gap-1 text-small text-muted">
      {limits.map((l, i) => (
        <li key={`${l.code}-${i}`}>{limitText(l)}</li>
      ))}
    </ul>
  );
}
