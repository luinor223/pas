import { TanStackRouterVite } from "@tanstack/router-plugin/vite";
import react from "@vitejs/plugin-react";
import path from "path";
import { defineConfig } from "vite";

// https://vite.dev/config/
export default defineConfig({
  plugins: [TanStackRouterVite({ target: "react", autoCodeSplitting: true }), react()],
  resolve: {
    alias: {
      "@": path.resolve(import.meta.dirname, "./src"),
    },
  },
  server: {
    port: 3000,
    host: true,
    proxy: {
      "/api": { target: "http://localhost:18080", changeOrigin: true },
      "/docs": { target: "http://localhost:18080", changeOrigin: true },
    },
    watch: process.env.CHOKIDAR_USEPOLLING === "true" ? { usePolling: true, interval: 300 } : undefined,
    hmr: process.env.VITE_HMR_CLIENT_PORT ? { clientPort: Number(process.env.VITE_HMR_CLIENT_PORT) } : undefined,
  },
  preview: {
    port: 3000,
    host: true,
  },
});
