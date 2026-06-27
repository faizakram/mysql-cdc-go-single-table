/// <reference types="vitest/config" />
import { defineConfig } from 'vitest/config';
import react from '@vitejs/plugin-react';

export default defineConfig({
  plugins: [react()],
  build: {
    // The antd vendor chunk is intentionally large (~985 kB raw / ~306 kB gzip) and long-cacheable;
    // splitting its internals further isn't worth the request overhead. App code is split per route.
    chunkSizeWarningLimit: 1000,
    rollupOptions: {
      output: {
        // Split heavy, rarely-changing vendor code into its own long-cacheable chunks (#128).
        manualChunks: {
          'antd': ['antd', '@ant-design/icons'],
          'react-vendor': ['react', 'react-dom', 'react-router-dom'],
          'query-vendor': ['@tanstack/react-query', 'axios'],
        },
      },
    },
  },
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
