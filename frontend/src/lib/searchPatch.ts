// SPDX-License-Identifier: Apache-2.0
import type { SetURLSearchParams } from "react-router-dom";

/** A writer that sets each key given, or deletes it when given null, and keeps every other key of `params`. */
export const searchPatch =
  (params: URLSearchParams, setParams: SetURLSearchParams, replace = false) =>
  (kv: Record<string, string | null>) => {
    const next = new URLSearchParams(params);
    for (const [k, v] of Object.entries(kv)) {
      if (v === null) next.delete(k);
      else next.set(k, v);
    }
    setParams(next, { replace });
  };
