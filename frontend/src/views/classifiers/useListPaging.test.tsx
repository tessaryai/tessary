// SPDX-License-Identifier: Apache-2.0
/*
 * Infinite scroll for the filtered session and answer lists. The bugs worth catching: a page read while
 * one is already being read, a page read ahead off the previous filter's placeholder rows, and a filter
 * switch that leaves the new list scrolled to where the old one was.
 */
import { act, cleanup, render } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { useListPaging } from "./useListPaging";

let observed: { callback: IntersectionObserverCallback; target: Element | null; disconnected: boolean }[] = [];

class RecordingObserver {
  entry: (typeof observed)[number];
  constructor(callback: IntersectionObserverCallback) {
    this.entry = { callback, target: null, disconnected: false };
    observed.push(this.entry);
  }
  observe(target: Element) {
    this.entry.target = target;
  }
  disconnect() {
    this.entry.disconnected = true;
  }
}

const original = window.IntersectionObserver;
beforeEach(() => {
  observed = [];
  window.IntersectionObserver = RecordingObserver as unknown as typeof IntersectionObserver;
});
afterEach(() => {
  cleanup();
  window.IntersectionObserver = original;
});

type Props = { filterKey: string; hasNextPage: boolean; isPlaceholderData?: boolean; isFetchingNextPage?: boolean };
const fetchNextPage = vi.fn(async () => undefined);

function List({ filterKey, hasNextPage, isPlaceholderData = false, isFetchingNextPage = false }: Props) {
  const { list, end } = useListPaging(filterKey, { hasNextPage, isPlaceholderData, isFetchingNextPage, fetchNextPage });
  return (
    <ul ref={list} data-testid="list">
      <li>row</li>
      <li ref={end}>end</li>
    </ul>
  );
}

const scrolledIn = (isIntersecting: boolean) =>
  act(() => observed.at(-1)!.callback([{ isIntersecting } as IntersectionObserverEntry], {} as IntersectionObserver));

describe("useListPaging", () => {
  it("reads the next page when the end scrolls into view, and not before", () => {
    render(<List filterKey="all" hasNextPage />);

    expect(observed.at(-1)!.target!.textContent).toBe("end");
    scrolledIn(false);
    expect(fetchNextPage).not.toHaveBeenCalled();
    scrolledIn(true);
    expect(fetchNextPage).toHaveBeenCalledTimes(1);
  });

  it("does not read a page while one is being read", () => {
    render(<List filterKey="all" hasNextPage isFetchingNextPage />);

    scrolledIn(true);

    expect(fetchNextPage).not.toHaveBeenCalled();
    fetchNextPage.mockClear();
  });

  it("watches nothing with no next page, or while the last filter's rows stand in", () => {
    const { rerender } = render(<List filterKey="all" hasNextPage={false} />);
    expect(observed).toHaveLength(0);

    rerender(<List filterKey="cause-1" hasNextPage isPlaceholderData />);
    expect(observed).toHaveLength(0);

    rerender(<List filterKey="cause-1" hasNextPage />);
    expect(observed).toHaveLength(1);
    rerender(<List filterKey="cause-1" hasNextPage={false} />);
    expect(observed[0].disconnected).toBe(true);
  });

  it("returns the list to its top when the filter changes", () => {
    const { getByTestId, rerender } = render(<List filterKey="all" hasNextPage={false} />);
    const list = getByTestId("list");
    list.scrollTop = 480;

    rerender(<List filterKey="all" hasNextPage={false} />);
    expect(list.scrollTop).toBe(480);
    rerender(<List filterKey="cause-1" hasNextPage={false} />);
    expect(list.scrollTop).toBe(0);
  });
});
