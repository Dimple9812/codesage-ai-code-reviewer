/// <reference types="vitest/config" />
import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";
import tailwindcss from "@tailwindcss/vite";

// In development the Vite server proxies API and OAuth routes to Spring Boot so the
// browser sees a single origin (session cookie + CSRF cookie work without CORS).
const backend = process.env.VITE_BACKEND_URL ?? "http://localhost:8080";
const proxied = { target: backend, changeOrigin: false, xfwd: true };

export default defineConfig({
  plugins: [react(), tailwindcss()],
  server: {
    port: 5173,
    proxy: {
      "/api": proxied,
      "/oauth2": proxied,
      "/login/oauth2": proxied,
      "/swagger-ui": proxied,
      "/v3/api-docs": proxied,
      "/actuator": proxied,
    },
  },
  build: {
    chunkSizeWarningLimit: 4000,
  },
  test: {
    environment: "jsdom",
    globals: true,
    setupFiles: ["./src/test/setup.ts"],
    css: false,
  },
});
