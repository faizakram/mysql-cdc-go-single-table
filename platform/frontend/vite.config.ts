/// <reference types="vitest/config" />
import { defineConfig } from 'vitest/config';
import react from '@vitejs/plugin-react';

export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      // Dev proxy so the SPA can call the backend without CORS friction.
      '/api': { target: 'http://localhost:8090', changeOrigin: true },
    },
  },
  test: {
    globals: true,
    environment: 'jsdom',
    setupFiles: './src/test/setup.ts',
    css: false,
    // Playwright specs live in e2e/ and run under their own runner.
    exclude: ['e2e/**', 'node_modules/**', 'dist/**'],
  },
});
