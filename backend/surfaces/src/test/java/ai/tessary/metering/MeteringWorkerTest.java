// SPDX-License-Identifier: Apache-2.0
package ai.tessary.metering;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ai.tessary.config.MeteringProperties;
import ai.tessary.config.TraceMdcBridge;
import ai.tessary.usage.MetricRollupJobRepository;
import ai.tessary.usage.MetricRollupJobRow;
import ai.tessary.usage.MetricRollupRepository;
import ai.tessary.usage.MetricRollupRow;
import ai.tessary.usage.UsageUnit;
import io.micrometer.tracing.Tracer;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * The metering heartbeat's failure isolation: each step's failure costs only that step, since the next heartbeat is
 * the only retry. The SQL is {@code MeteringIntegrationTest}'s; "marked done" is asserted because an undone job is
 * what lease-and-reclaim relies on.
 */
@ExtendWith(MockitoExtension.class)
class MeteringWorkerTest {

    @Mock
    MetricRollupJobRepository jobs;

    @Mock
    MetricRollupRepository rollups;

    private MeteringWorker worker() {
        return new MeteringWorker(jobs, rollups, new MeteringProperties(), new TraceMdcBridge(Tracer.NOOP));
    }

    private static MetricRollupJobRow job(String id, String projectId) {
        return new MetricRollupJobRow(id, "org-1", projectId, "2026-09-01T10:00:00Z", MeteringWorker.BUCKET_HOUR);
    }

    /** A scheduling failure still claims and meters queued jobs. */
    @Test
    void aSchedulingFailureStillMetersTheJobsAlreadyQueued() {
        when(jobs.scheduleDueBuckets(anyString(), anyString())).thenThrow(new IllegalStateException("db blip"));
        when(jobs.claimBatch(anyInt(), anyLong())).thenReturn(List.of(job("job-1", "p1")));

        worker().tick();

        verify(jobs).markDone("job-1");
    }

    /** A claim failure ends the tick quietly. */
    @Test
    void aClaimFailureEndsTheTickWithoutMeteringOrThrowing() {
        when(jobs.scheduleDueBuckets(anyString(), anyString())).thenReturn(1);
        when(jobs.claimBatch(anyInt(), anyLong())).thenThrow(new IllegalStateException("lock timeout"));

        assertDoesNotThrow(() -> worker().tick());

        verify(jobs, never()).markDone(any());
        verifyNoInteractions(rollups);
    }

    /** A failed job stays claimed (its lease expires for a later re-meter) and the next job still meters. */
    @Test
    void aFailedAggregationLeavesThatJobClaimedAndMetersTheRest() {
        when(jobs.scheduleDueBuckets(anyString(), anyString())).thenReturn(0);
        when(jobs.claimBatch(anyInt(), anyLong())).thenReturn(List.of(job("job-1", "p1"), job("job-2", "p2")));
        when(rollups.countIngestedSpans(anyString(), anyString(), anyString()))
                .thenThrow(new IllegalStateException("statement timeout"))
                .thenReturn(4L);

        worker().tick();

        verify(jobs, never()).markDone("job-1");
        verify(jobs).markDone("job-2");
    }

    /**
     * {@link UsageUnit#metered()} must match what the worker writes: an omitted unit is billed nowhere, and an
     * unwritten one reads as a permanent zero.
     */
    @Test
    void theWorkerWritesExactlyTheMeteredUnits() {
        worker().meterOne(job("job-1", "p1"));

        ArgumentCaptor<MetricRollupRow> written = ArgumentCaptor.forClass(MetricRollupRow.class);
        verify(rollups, atLeastOnce()).upsert(written.capture());
        assertEquals(
                Arrays.stream(UsageUnit.metered()).map(UsageUnit::wire).collect(Collectors.toSet()),
                written.getAllValues().stream().map(MetricRollupRow::metric).collect(Collectors.toSet()));
        assertEquals(UsageUnit.metered().length, written.getAllValues().size(), "one row per unit");
    }
}
