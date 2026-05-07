/// <reference types="vitest" />
import path from 'node:path';

import { defineConfig } from 'vitest/config';
import react from '@vitejs/plugin-react';

export default defineConfig({
  plugins: [react()],
  resolve: {
    alias: {
      '@': path.resolve(__dirname, './src'),
    },
  },
  test: {
    globals: true,
    environment: 'jsdom',
    setupFiles: ['./src/test/setup.ts'],
    css: false,
    // Playwright E2E 디렉토리 제외 — `npm run e2e`로 별도 실행
    exclude: ['**/node_modules/**', '**/dist/**', 'e2e/**'],
  },
});
