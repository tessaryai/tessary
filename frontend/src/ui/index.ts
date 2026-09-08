// SPDX-License-Identifier: Apache-2.0
export { cn } from "./cn";
export { Spinner, Skeleton } from "./Spinner";
export { useFocusTrap } from "./useFocusTrap";
export { useEscToClose } from "./escStack";
export { Divider } from "./Divider";
export { Badge } from "./Badge";
export type { BadgeTone } from "./Badge";
export { StatusPill } from "./StatusPill";
export type { Status } from "./StatusPill";
export { ProgressBar } from "./ProgressBar";
export { Button, IconButton } from "./Button";
export type { ButtonVariant, ButtonSize } from "./Button";
export { CopyButton, useCopy, writeClipboard } from "./CopyButton";
export { Input, Textarea, Select } from "./Input";
export { TimeRangePicker } from "./TimeRangePicker";
export { formatBound, formatRange, localToIso, isoToLocal } from "./timeRange";
export type { TimeRange, RelUnit } from "./timeRange";
export { Field } from "./Field";
export { Card, CardHeader, CardBody, CardTitle, CardSubtitle, Surface } from "./Card";
export { EmptyState } from "./EmptyState";
export { QueryState, LoadingRow, ErrorNote, TableSkeleton } from "./QueryState";
export { Tabs } from "./Tabs";
export type { Tab } from "./Tabs";
export { Collapsible } from "./Collapsible";
export { Tooltip } from "./Tooltip";
export { Modal } from "./Modal";
export { Rail } from "./Rail";
export type { RailProps } from "./Rail";
export { ToastProvider, useToast } from "./Toast";
export { Table, THead, TBody, TR, TH, TD } from "./Table";
export { PageHeader, PageBody, Section, Breadcrumb } from "./Page";
export type { Crumb } from "./Page";
export { ConceptPopover } from "./ConceptPopover";
export { DensityProvider, useDensity } from "./density";
export type { Density } from "./density";
export { Toggle } from "./Toggle";
export { VolumeBars } from "./charts/VolumeBars";
export type { VolumeBucket } from "./charts/VolumeBars";
/*
 * The recharts-backed charts are deliberately NOT re-exported here.
 *
 * Everything in the app imports something from this barrel, so a static re-export of these two put
 * recharts — `vendor-charts`, 374 kB raw / 108 kB gzipped — into the eager path of every surface,
 * including Traces and Settings pages that draw nothing. Exactly one file in the whole product
 * actually renders one of them, the Usage screen, and it imports from the module directly, so the
 * chunk is pulled only where a chart is on screen.
 *
 *   import { StackedBarChart } from "../../ui/charts/StackedBarChart";
 *
 * `VolumeBars` stays exported: it is hand-drawn and pulls no charting library.
 *
 * <h2>Both files are UNUSED in this edition, and stay</h2>
 * Since #846 the Usage screen is a paid surface and lives in the overlay, so nothing in this tree
 * renders either chart and the open build emits no `vendor-charts` at all — `scripts/check-frontend.sh`
 * asserts exactly that. `TrendChart` already had no consumer before the move. They stay here rather
 * than moving WITH their one caller, and rather than being deleted, for three reasons: they are
 * generic UI with no paid concept in them, moving them would duplicate the `recharts` dependency and
 * the load-bearing `es-toolkit: 1.46.0` pin into a second package, and `tsc` still type-checks them
 * here so they cannot rot silently. Deleting an unused component is spring-cleaning, not this split.
 */
export { SegmentedControl } from "./SegmentedControl";
export type { Segment } from "./SegmentedControl";
export {
  chartTheme,
  seriesColor,
  chartSeriesColors,
  chartStatusColors,
  CHART_SERIES_COUNT,
  CHART_SERIES_OTHER,
} from "./charts/chartTheme";
