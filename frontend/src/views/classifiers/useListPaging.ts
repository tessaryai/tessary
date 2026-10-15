// SPDX-License-Identifier: Apache-2.0
import { useEffect, useRef } from "react";

type Pages = {
  hasNextPage: boolean;
  isPlaceholderData: boolean;
  isFetchingNextPage: boolean;
  fetchNextPage: () => Promise<unknown>;
};

/**
 * No page is read ahead while the previous filter's rows stand in as placeholders: the end being in view
 * is then a fact about the old list.
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
