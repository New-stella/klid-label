/// <reference types="vitest" />
import path from 'node:path';

import { defineConfig } from 'vitest/config';
import react from '@vitejs/plugin-react';

// 포털 부품 연결 — 화면 서버 설정(vite.config.ts)과 같은 연결을 둔다. 근거는 그 파일 주석.
const portalRepoDir = process.env.KLID_PORTAL_DIR || path.resolve(__dirname, '../../KLID_Portal');

export default defineConfig({
  plugins: [react()],
  resolve: {
    alias: {
      '@': path.resolve(__dirname, './src'),
      '@portal': path.join(portalRepoDir, 'src'),
      'krds-react': path.join(portalRepoDir, 'node_modules/krds-react'),
    },
    dedupe: ['react', 'react-dom'],
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
