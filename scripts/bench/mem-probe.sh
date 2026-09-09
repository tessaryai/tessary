#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# What the backend container is actually using, separated into the parts that matter.
#
# Why not just read `docker stats`: that number is the cgroup's memory.current, which folds the page
# cache in with the process, and it says nothing about whether the JVM's own committed memory fits
# under the limit. -XX:MaxRAMPercentage=75 puts a 1.5 GiB heap inside a 2 GiB cgroup, and the
# remaining 512 MiB has to cover metaspace, thread stacks, the code cache, GC structures and every
# direct byte buffer the OTLP path allocates. Only NMT splits those out, and the shipped image is a
# JRE with no jcmd, so jcmd comes from a sidecar JDK sharing the backend's PID namespace and /tmp.
#
#   bash mem-probe.sh <tag>
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"
TAG="${1:-probe}"
OUT="results/memory"; mkdir -p "$OUT"

jcmd_in() {
  docker run --rm --pid=container:tessary-backend-1 --user root \
    -v tessary_bench-tmp:/tmp -v "$PWD/$OUT:/out" eclipse-temurin:25-jdk jcmd 1 "$@"
}

{
  echo "=== $(date -u +%FT%TZ)  tag=$TAG"
  echo
  echo "--- cgroup (the ceiling the JVM sized itself against)"
  docker exec tessary-backend-1 sh -c 'echo "memory.max   $(cat /sys/fs/cgroup/memory.max)"
    echo "memory.current $(cat /sys/fs/cgroup/memory.current)"
    echo "memory.peak    $(cat /sys/fs/cgroup/memory.peak 2>/dev/null || echo n/a)"
    grep -E "^(anon|file|slab|sock) " /sys/fs/cgroup/memory.stat
    echo "cpu.max      $(cat /sys/fs/cgroup/cpu.max)"'
  echo
  echo "--- heap as it stands (GC.heap_info does NOT collect first, so this includes floating garbage;"
  echo "    for a live set, read GCHeapSummary after-GC events out of the JFR recording instead)"
  jcmd_in GC.heap_info 2>&1 | tail -n +2
  echo
  echo "--- native memory tracking: committed, by category"
  jcmd_in VM.native_memory summary scale=MB 2>&1 | tail -n +2
} | tee "$OUT/$TAG.txt"

echo
echo "written: $OUT/$TAG.txt"
