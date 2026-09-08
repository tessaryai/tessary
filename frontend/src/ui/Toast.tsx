// SPDX-License-Identifier: Apache-2.0
import type { ReactNode } from "react";
import { createContext, useCallback, useContext, useEffect, useRef, useState } from "react";
import { createPortal } from "react-dom";
import { CheckCircle2, Info, X, XCircle } from "lucide-react";
import { cn } from "./cn";

export type ToastKind = "success" | "error" | "info";
export type Toast = { id: number; kind: ToastKind; title: string; body?: string; ttlMs: number };

type ToastInput = Omit<Toast, "id" | "ttlMs"> & { ttlMs?: number };

type Ctx = {
  push: (t: ToastInput) => void;
  success: (title: string, body?: string) => void;
  error: (title: string, body?: string) => void;
  info: (title: string, body?: string) => void;
};

const ToastCtx = createContext<Ctx | null>(null);

export function useToast(): Ctx {
  const ctx = useContext(ToastCtx);
  if (!ctx) throw new Error("useToast must be used inside <ToastProvider>");
  return ctx;
}

const KIND_STYLE: Record<ToastKind, { ring: string; icon: ReactNode }> = {
  success: { ring: "border-l-success", icon: <CheckCircle2 size={14} strokeWidth={1.75} aria-hidden="true" /> },
  error: { ring: "border-l-error", icon: <XCircle size={14} strokeWidth={1.75} aria-hidden="true" /> },
  info: { ring: "border-l-info", icon: <Info size={14} strokeWidth={1.75} aria-hidden="true" /> },
};

const KIND_TEXT: Record<ToastKind, string> = {
  success: "text-success",
  error: "text-error",
  info: "text-info",
};

export function ToastProvider({ children }: { children: ReactNode }) {
  const [toasts, setToasts] = useState<Toast[]>([]);
  const seq = useRef(0);

  const dismiss = useCallback((id: number) => {
    setToasts((cur) => cur.filter((t) => t.id !== id));
  }, []);

  const push = useCallback(
    (t: ToastInput) => {
      const id = ++seq.current;
      const ttl = t.ttlMs ?? 5000;
      setToasts((cur) => [...cur, { ...t, id, ttlMs: ttl }]);
      window.setTimeout(() => dismiss(id), ttl);
    },
    [dismiss],
  );

  const ctx: Ctx = {
    push,
    success: (title, body) => push({ kind: "success", title, body }),
    error: (title, body) => push({ kind: "error", title, body }),
    info: (title, body) => push({ kind: "info", title, body }),
  };

  return (
    <ToastCtx.Provider value={ctx}>
      {children}
      <ToastViewport toasts={toasts} onDismiss={dismiss} />
    </ToastCtx.Provider>
  );
}

function ToastViewport({ toasts, onDismiss }: { toasts: Toast[]; onDismiss: (id: number) => void }) {
  const [mounted, setMounted] = useState(false);
  useEffect(() => setMounted(true), []);
  if (!mounted) return null;
  return createPortal(
    <div className="fixed bottom-4 right-4 z-[100] flex flex-col gap-2 pointer-events-none">
      {toasts.map((t) => {
        const k = KIND_STYLE[t.kind];
        return (
          <div
            key={t.id}
            role="status"
            className={cn(
              "pointer-events-auto min-w-[280px] max-w-[400px] rounded-card bg-overlay border-l-2 border border-border-strong",
              "px-3.5 py-3 flex items-start gap-2.5",
              k.ring,
            )}
            style={{ boxShadow: "var(--shadow-md)" }}
          >
            <span className={cn("mt-0.5", KIND_TEXT[t.kind])}>{k.icon}</span>
            <div className="flex-1 min-w-0">
              <div className="text-small font-medium text-fg">{t.title}</div>
              {t.body && <div className="text-small text-muted mt-0.5 break-words">{t.body}</div>}
            </div>
            <button
              onClick={() => onDismiss(t.id)}
              aria-label="Dismiss"
              className="text-subtle hover:text-fg transition-colors"
              style={{ transitionDuration: "var(--duration-micro)" }}
            >
              <X size={12} strokeWidth={1.75} aria-hidden="true" />
            </button>
          </div>
        );
      })}
    </div>,
    document.body,
  );
}
