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
 * The recharts-backed charts are deliberately not re-exported here.
 *
 * Everything in the app imports from this barrel, so a static re-export would put recharts
 * (`vendor-charts`, 374 kB raw / 108 kB gzipped) into the eager path of every surface, including
 * pages that draw no charts at all. Import the module directly where you need one:
 *
 *   import { StackedBarChart } from "../../ui/charts/StackedBarChart";
 *
 * `VolumeBars` stays exported: it is hand-drawn and pulls no charting library.
 *
 * Nothing in this tree currently renders either chart, and `scripts/check-frontend.sh` asserts
 * that `vendor-charts` stays out of the build. They stay here, unused, rather than moving or
 * being deleted: they are generic UI, moving them would duplicate the `recharts` dependency and
 * the load-bearing `es-toolkit: 1.46.0` pin into a second package, and `tsc` still type-checks
 * them here so they cannot rot silently.
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
