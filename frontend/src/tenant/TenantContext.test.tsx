// SPDX-License-Identifier: Apache-2.0
/*
 * The tenant context. The bug worth catching: a page rendered outside its provider reading an undefined
 * org and project and calling the API with them, rather than failing loudly.
 */
import { renderHook } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { useTenant } from "./TenantContext";

describe("useTenant", () => {
  it("refuses to run outside its provider", () => {
    vi.spyOn(console, "error").mockImplementation(() => {});

    expect(() => renderHook(() => useTenant())).toThrow("useTenant must be used inside <TenantProvider>");
  });
});
