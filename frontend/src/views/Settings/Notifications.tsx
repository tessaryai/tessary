// SPDX-License-Identifier: Apache-2.0
import { useEffect, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useCapabilities } from "../../capabilities/useCapabilities";
import { useProjectApi } from "../../tenant/TenantContext";
import { ApiError } from "../../api/types";
import type { AlertChannel, AlertRule } from "../../api/types";
import {
  Badge,
  Button,
  Field,
  Input,
  PageBody,
  PageHeader,
  Section,
  Select,
  Spinner,
  Toggle,
  useToast,
} from "../../ui";

/**
 * Settings → Notifications — how a case reaches a human when nobody is looking at the app.
 *
 * Two halves, in the order they matter. WHEN we tell you: the case-opened rule, which every project has
 * from the day it is created, plus the cadence and quiet hours that are the partner's to set. WHERE we
 * tell you: the delivery channels, without which the rule notifies nobody.
 *
 * A signed webhook is the route every org has. Slack is a capability of its own (`slack_enabled`, off —
 * it is not part of the launch), so its field appears only for an org that has it, and this page never
 * mentions it otherwise. That ordering is deliberate rather than incidental: alerting has to work for a
 * partner who will never see the Slack option, so the ungated route is the one the page leads with.
 *
 * Deliberately NOT a rule builder. The three launch detectors all reach a human the same way — a case
 * opens — so there is one rule and two dials on it, rather than a per-detector matrix that would ask a
 * partner to understand the detector taxonomy before they can be paged.
 *
 * Quiet hours DEFER, they do not drop. A regression that opens at 3am is delivered when the window
 * reopens, and the message says when the case actually opened. The copy says so, because a partner who
 * believes quiet hours mean "discard" will not use them.
 */

/** Only the cadences worth offering. A free-text seconds field invites 7-second and 3-week policies. */
const CADENCES: { label: string; seconds: number }[] = [
  { label: "As soon as a case opens", seconds: 0 },
  { label: "At most every 15 minutes", seconds: 900 },
  { label: "At most hourly", seconds: 3600 },
  { label: "At most every 4 hours", seconds: 14_400 },
  { label: "At most daily", seconds: 86_400 },
];

const CASE_OPENED = "case_opened";

export function Notifications() {
  const api = useProjectApi();
  const qc = useQueryClient();
  const toast = useToast();
  const { isEnabled } = useCapabilities();

  const rulesQ = useQuery({ queryKey: ["alert-rules", api.base], queryFn: api.listAlertRules });
  const channelsQ = useQuery({ queryKey: ["alert-channels", api.base], queryFn: api.listAlertChannels });

  const rule: AlertRule | undefined = rulesQ.data?.find((r) => r.rule_type === CASE_OPENED);

  const [cadence, setCadence] = useState(0);
  const [quietFrom, setQuietFrom] = useState("");
  const [quietTo, setQuietTo] = useState("");
  const [zone, setZone] = useState(browserZone());
  const [webhook, setWebhook] = useState("");
  const [slackUrl, setSlackUrl] = useState("");

  // The rule is the source of truth; local state mirrors it once the read settles and after each save.
  useEffect(() => {
    if (!rule?.policy) return;
    setCadence(rule.policy.cadence_seconds);
    setQuietFrom(rule.policy.quiet_from ?? "");
    setQuietTo(rule.policy.quiet_to ?? "");
    setZone(rule.policy.quiet_zone);
  }, [rule?.policy]);

  const invalidateRules = () => qc.invalidateQueries({ queryKey: ["alert-rules", api.base] });
  const invalidateChannels = () => qc.invalidateQueries({ queryKey: ["alert-channels", api.base] });

  const savePolicy = useMutation({
    mutationFn: () =>
      api.upsertAlertRule({
        rule_type: CASE_OPENED,
        name: rule?.name ?? "A case opens",
        policy: {
          cadence_seconds: cadence,
          // Both ends or neither: half a window would be read as no window, and a partner who set only
          // a start would think they were covered.
          quiet_from: quietFrom && quietTo ? quietFrom : null,
          quiet_to: quietFrom && quietTo ? quietTo : null,
          quiet_zone: zone,
        },
      }),
    onSuccess: () => {
      invalidateRules();
      toast.success("Notification policy saved");
    },
    onError: (err) => toast.error("Could not save notification policy", (err as ApiError).message),
  });

  const toggleRule = useMutation({
    mutationFn: (next: boolean) => api.setAlertRuleEnabled(rule?.id ?? "", next),
    onSuccess: () => invalidateRules(),
    onError: (err) => toast.error("Could not update rule", (err as ApiError).message),
  });

  const addWebhook = useMutation({
    mutationFn: () =>
      api.createAlertChannel({
        kind: "webhook",
        name: "Webhook",
        enabled: true,
        config: { url: webhook.trim() },
      }),
    onSuccess: () => {
      setWebhook("");
      invalidateChannels();
      toast.success("Webhook added");
    },
    onError: (err) => toast.error("Could not add webhook", (err as ApiError).message),
  });

  const addSlack = useMutation({
    mutationFn: () =>
      api.createAlertChannel({
        kind: "slack",
        name: "Slack",
        enabled: true,
        config: { url: slackUrl.trim() },
      }),
    onSuccess: () => {
      setSlackUrl("");
      invalidateChannels();
      toast.success("Slack channel added");
    },
    onError: (err) => toast.error("Could not add Slack channel", (err as ApiError).message),
  });

  const removeChannel = useMutation({
    mutationFn: (id: string) => api.deleteAlertChannel(id),
    onSuccess: () => {
      invalidateChannels();
      toast.success("Channel removed");
    },
    onError: (err) => toast.error("Could not remove channel", (err as ApiError).message),
  });

  const channels: AlertChannel[] = channelsQ.data ?? [];
  const quietSet = !!(quietFrom && quietTo);
  const slackEnabled = isEnabled("slack_enabled");

  return (
    <PageBody size="narrow">
      <PageHeader
        eyebrow="Alerting"
        title="Notifications"
        subtitle="A case opening is the one thing worth interrupting someone for, and every classifier reaches a human the same way. Set when you are willing to hear about it, and where."
      />

      <Section title="When a case opens">
        {rulesQ.isLoading ? (
          <Loading />
        ) : !rule ? (
          <p className="text-small text-muted">
            This project has no case-opened rule yet. It is created with the project; if this persists,
            the alerting capability may be off for your organization.
          </p>
        ) : (
          <div className="flex flex-col gap-5">
            <div className="flex items-start gap-3">
              <Toggle
                checked={rule.enabled}
                onChange={(next) => toggleRule.mutate(next)}
                aria-label="Notify when a case opens"
              />
              <div>
                <p className="text-small text-fg">Notify when a case opens</p>
                <p className="mt-0.5 text-label text-muted">
                  Every case, from every classifier. A case already means a triaged deviation, so
                  there is nothing further to filter.
                </p>
              </div>
            </div>

            <Field
              label="Cadence"
              hint="How closely together notifications may arrive. Cases that open in between are batched into the next one, never dropped."
            >
              {(p) => (
                <Select {...p} value={String(cadence)} onChange={(e) => setCadence(Number(e.target.value))}>
                  {CADENCES.map((c) => (
                    <option key={c.seconds} value={c.seconds}>
                      {c.label}
                    </option>
                  ))}
                </Select>
              )}
            </Field>

            <div>
              <p className="text-small text-fg">Quiet hours</p>
              <p className="mt-0.5 mb-2 text-label text-muted">
                Nothing is delivered inside this window, and nothing is lost: whatever opened is sent when
                the window reopens, stamped with when the case actually opened. Leave both blank for none.
              </p>
              <div className="flex items-end gap-3">
                <Field label="From">
                  {(p) => (
                    <Input {...p} type="time" value={quietFrom} onChange={(e) => setQuietFrom(e.target.value)} />
                  )}
                </Field>
                <Field label="To">
                  {(p) => <Input {...p} type="time" value={quietTo} onChange={(e) => setQuietTo(e.target.value)} />}
                </Field>
                <Field label="Time zone">
                  {(p) => (
                    <Input {...p} value={zone} onChange={(e) => setZone(e.target.value)} placeholder="Europe/London" />
                  )}
                </Field>
              </div>
              {quietSet && quietFrom > quietTo && (
                <p className="mt-2 text-label text-muted">This window crosses midnight, which is fine.</p>
              )}
            </div>

            <div>
              <Button variant="primary" onClick={() => savePolicy.mutate()} disabled={savePolicy.isPending}>
                {savePolicy.isPending ? "Saving…" : "Save changes"}
              </Button>
            </div>
          </div>
        )}
      </Section>

      <Section title="Where it goes">
        <p className="mb-3 text-small text-muted">
          Until there is a destination here, the rule above notifies nobody.
        </p>
        {channelsQ.isLoading ? (
          <Loading />
        ) : (
          <>
            {channels.length > 0 && (
              <div className="mb-4 overflow-hidden rounded-card border border-border">
                {channels.map((ch) => (
                  <div
                    key={ch.id}
                    className="flex items-center gap-3 px-4 py-3 border-b border-border last:border-b-0">
                    <span className="flex-1 min-w-0 text-small text-fg truncate">{ch.name}</span>
                    <Badge tone="neutral">{ch.kind}</Badge>
                    <Badge tone={ch.enabled ? "success" : "neutral"}>{ch.enabled ? "Enabled" : "Disabled"}</Badge>
                    <button
                      type="button"
                      onClick={() => removeChannel.mutate(ch.id)}
                      className="text-label text-accent hover:text-accent-hover">
                      Remove
                    </button>
                  </div>
                ))}
              </div>
            )}
            <div className="flex items-end gap-3">
              <Field
                label="Webhook URL"
                hint="Tessary posts a signed JSON payload for every case. Works with anything that accepts a webhook."
                className="flex-1">
                {(p) => (
                  <Input
                    {...p}
                    value={webhook}
                    onChange={(e) => setWebhook(e.target.value)}
                    placeholder="https://example.com/hooks/tessary"
                  />
                )}
              </Field>
              <Button
                variant="secondary"
                onClick={() => addWebhook.mutate()}
                disabled={webhook.trim().length === 0 || addWebhook.isPending}
              >
                {addWebhook.isPending ? "Adding…" : "Add webhook"}
              </Button>
            </div>

            {/* Slack is its own capability and off by default. An org without it never sees the field —
                and never sees a disabled one either, which would be a mention of something it cannot have. */}
            {slackEnabled && (
              <div className="mt-4 flex items-end gap-3">
                <Field label="Slack incoming webhook" hint="hooks.slack.com/services/…" className="flex-1">
                  {(p) => (
                    <Input
                      {...p}
                      value={slackUrl}
                      onChange={(e) => setSlackUrl(e.target.value)}
                      placeholder="https://hooks.slack.com/services/T000/B000/XXXX"
                    />
                  )}
                </Field>
                <Button
                  variant="secondary"
                  onClick={() => addSlack.mutate()}
                  disabled={slackUrl.trim().length === 0 || addSlack.isPending}
                >
                  {addSlack.isPending ? "Adding…" : "Add Slack channel"}
                </Button>
              </div>
            )}
          </>
        )}
      </Section>
    </PageBody>
  );
}

function Loading() {
  return (
    <div className="flex items-center gap-2 py-8 text-small text-muted">
      <Spinner size="sm" />
      Loading…
    </div>
  );
}

/** The browser's own zone as the starting guess — right far more often than UTC is. */
function browserZone(): string {
  try {
    return Intl.DateTimeFormat().resolvedOptions().timeZone || "UTC";
  } catch {
    return "UTC";
  }
}
