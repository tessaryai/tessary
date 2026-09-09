// SPDX-License-Identifier: Apache-2.0
import { fileURLToPath } from "node:url";
// Imported from 'vitest/config', not plain 'vite': that re-export augments Vite's own
// UserConfig type with the `test` key below, so the whole file (including `test`) type-checks
// under the same `tsc` pass `lint`/`build` already run, no separate vitest.config.ts and no
// second tsconfig to keep in sync.
import { defineConfig } from "vitest/config";
import react from "@vitejs/plugin-react";
import tailwindcss from "@tailwindcss/vite";

export default defineConfig({
  plugins: [react(), tailwindcss()],
  resolve: {
    alias: {
      // `@paid` resolves inside src/, to `src/paid/index.ts`, whose every export is empty in this
      // build. The alias stays pinned under `frontend/src` on purpose: `scripts/check-open-boundary.sh`
      // fails the build if it points anywhere else, so it can't be silently repointed outward.
      "@paid": fileURLToPath(new URL("./src/paid/index.ts", import.meta.url)),
    },
  },
  build: {
    // The app is route-split (App.tsx lazy-loads each view), so the entry chunk
    // stays small and each view loads on demand. Pull the heavy, rarely-changing
    // vendor libraries into their own chunks too, so a view that imports (say)
    // recharts or the JSON viewer doesn't drag the rest of the app along, and
    // those bundles cache independently across deploys. Keeps every emitted
    // chunk under Vite's 500 kB advisory.
    rollupOptions: {
      output: {
        manualChunks(id: string) {
          if (!id.includes("node_modules")) return undefined;
          if (/[\\/]node_modules[\\/](react|react-dom|react-router|react-router-dom|scheduler)[\\/]/.test(id))
            return "vendor-react";
          if (/[\\/]node_modules[\\/](recharts|d3-|victory-|internmap|decimal\.js)/.test(id)) return "vendor-charts";
          if (/[\\/]node_modules[\\/](react-markdown|remark-|rehype-|micromark|mdast-|hast-|unist-|unified|vfile)/.test(id))
            return "vendor-markdown";
          if (/[\\/]node_modules[\\/]@uiw[\\/]react-json-view/.test(id)) return "vendor-jsonview";
          return undefined;
        },
      },
    },
  },
  server: {
    port: 5173,
    // Direct browser /api/* calls to the Spring Boot backend during dev.
    // In prod Caddy handles this; in dev we proxy here so the frontend can
    // talk to the backend without CORS gymnastics.
    proxy: {
      "/api": {
        target: "http://localhost:8080",
        changeOrigin: true,
      },
    },
  },
  // The route render-smoke test (src/routeManifest.smoke.test.tsx) needs a DOM, jsdom, not
  // node, and the handful of browser globals views reach for (IntersectionObserver,
  // ResizeObserver, scrollIntoView) that jsdom itself doesn't implement; setupFiles stubs those
  // once for every test file rather than per-test.
  test: {
    environment: "jsdom",
    setupFiles: ["./src/test/setup.ts"],
  },
});
