// SPDX-License-Identifier: Apache-2.0
import { useEffect, useRef } from "react";

/** The slice of an infinite query the paging needs. */
type Pages = {
  hasNextPage: boolean;
  isPlaceholderData: boolean;
  isFetchingNextPage: boolean;
  fetchNextPage: () => Promise<unknown>;
};

/**
 * Infinite scroll for a list that switches filters: the next page is read when the list's last row
 * scrolls into view, and a filter switch returns the list to its top. No page is read ahead while the
 * previous filter's rows are still standing in as placeholders, since the end being in view is then
 * a fact about the old list.
 *
 * Attach `list` to the scrolling `<ul>` and `end` to the row after the last item.
 */
export function useListPaging(filterKey: string, pages: Pages) {
  const list = useRef<HTMLUListElement>(null);
  const end = useRef<HTMLLIElement>(null);
  const { isFetchingNextPage, fetchNextPage } = pages;
  const hasNextPage = pages.hasNextPage && !pages.isPlaceholderData;
  useEffect(() => {
    if (list.current) list.current.scrollTop = 0;
  }, [filterKey]);
  useEffect(() => {
    const root = list.current;
    const target = end.current;
    if (!root || !target || !hasNextPage) return;
    const seen = new IntersectionObserver(
      (entries) => {
        if (entries.some((e) => e.isIntersecting) && !isFetchingNextPage) void fetchNextPage();
      },
      { root, rootMargin: "200px" },
    );
    seen.observe(target);
    return () => seen.disconnect();
  }, [hasNextPage, isFetchingNextPage, fetchNextPage]);
  return { list, end, hasNextPage };
}
