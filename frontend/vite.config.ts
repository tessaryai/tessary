// SPDX-License-Identifier: Apache-2.0
import { defineConfig } from "vitest/config";
import react from "@vitejs/plugin-react";
import tailwindcss from "@tailwindcss/vite";

export default defineConfig({
  plugins: [react(), tailwindcss()],
  build: {
    // Heavy vendor libraries get their own chunks so every chunk stays under Vite's 500 kB advisory.
    rollupOptions: {
      output: {
        manualChunks(id: string) {
          if (!id.includes("node_modules")) return undefined;
          if (/[\\/]node_modules[\\/](react|react-dom|react-router|react-router-dom|scheduler)[\\/]/.test(id))
            return "vendor-react";
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
    // Caddy proxies /api in prod.
    proxy: {
      "/api": {
        target: "http://localhost:8080",
        changeOrigin: true,
      },
    },
  },
  test: {
    environment: "jsdom",
    pool: "vmThreads",
    setupFiles: ["./src/test/setup.ts"],
    coverage: {
      provider: "v8",
      include: ["src/**/*.{ts,tsx}"],
      exclude: ["src/**/*.test.{ts,tsx}", "src/test/**", "src/**/*.d.ts", "src/main.tsx"],
      reporter: ["text-summary", "json-summary", "json", "html"],
      thresholds: { lines: 96.8 },
    },
  },
});
