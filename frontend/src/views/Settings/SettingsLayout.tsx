// SPDX-License-Identifier: Apache-2.0
// The rail is derived from SETTINGS_GROUPS in shell/nav.tsx so it cannot drift from ⌘K.
import { Outlet, useLocation, useNavigate } from "react-router-dom";
import { useTenant } from "../../tenant/TenantContext";
import { useNavigation } from "../../shell/useNavigation";
import { cn } from "../../ui";

export function SettingsLayout() {
  const { orgSlug, projectSlug } = useTenant();
  const nav = useNavigate();
  const location = useLocation();
  const { settingsGroups } = useNavigation();
  const base = `/orgs/${orgSlug}/projects/${projectSlug}/settings`;

  return (
    <div className="h-full flex flex-col">
      <header className="px-8 pt-5 pb-4 border-b border-border">
        <h1 className="text-h2 font-semibold text-fg">Settings</h1>
      </header>
      <div className="flex-1 flex min-h-0">
        <nav className="w-[216px] shrink-0 border-r border-border p-3 flex flex-col gap-4 overflow-y-auto">
          {settingsGroups.map((group) => (
            <div key={group.title} className="flex flex-col gap-0.5">
              <div className="px-2.5 pb-1 text-label uppercase text-subtle">
                {group.title}
              </div>
              {group.items.map((item) => {
                const href = `${base}/${item.id}`;
                const active = location.pathname === href || location.pathname.startsWith(href + "/");
                return (
                  <button
                    key={item.id}
                    type="button"
                    onClick={() => nav(href)}
                    className={cn(
                      "flex items-center gap-2 w-full text-left px-2.5 py-2 rounded-control text-small cursor-pointer",
                      active ? "bg-selected text-fg" : "text-muted hover:text-fg hover:bg-hover",
                    )}
                    style={{ transitionDuration: "var(--duration-micro)" }}
                  >
                    <item.icon size={14} strokeWidth={1.6} aria-hidden="true" className="shrink-0" />
                    <span className="flex-1 truncate">{item.label}</span>
                  </button>
                );
              })}
            </div>
          ))}
        </nav>
        <div className="flex-1 overflow-y-auto min-w-0">
          <Outlet />
        </div>
      </div>
    </div>
  );
}
