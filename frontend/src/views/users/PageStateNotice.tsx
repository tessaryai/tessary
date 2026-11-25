// SPDX-License-Identifier: Apache-2.0
import { useId } from "react";
import type { EndUserOverview } from "../../api/types";
import { Card } from "../../ui";
import { pageNotice } from "./usersWords";

/** Board P6: the page state that replaces the funnel and the issue numbers, or names the IDs left out of them. */
export function PageStateNotice({ overview }: { overview: EndUserOverview }) {
  const titleId = useId();
  const notice = pageNotice(overview);
  if (notice == null) return null;
  return (
    <Card role="region" aria-labelledby={titleId} className="flex flex-col gap-2 border border-border px-5 py-4">
      <h2 id={titleId} className="text-h3 text-fg">
        {notice.title}
      </h2>
      <p className="text-body text-fg-secondary">{notice.text}</p>
    </Card>
  );
}
