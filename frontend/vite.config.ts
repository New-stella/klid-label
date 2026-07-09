import path from 'node:path';

import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// 컨테이너/네트워크 배포 대응 환경변수 (미설정 시 로컬 개발 기본값 보존)
//   - BACKEND_ORIGIN  : 프록시 대상 BE 오리진 (컨테이너: http://klid-backend:8080)
//   - HMR_CLIENT_PORT : HMR 클라이언트가 접속할 외부 매핑 포트 (컨테이너: 13000)
const backendOrigin = process.env.BACKEND_ORIGIN || 'http://localhost:8080';
const hmrClientPort = process.env.HMR_CLIENT_PORT
  ? Number(process.env.HMR_CLIENT_PORT)
  : undefined;

const proxyTarget = {
  target: backendOrigin,
  changeOrigin: true,
};

export default defineConfig({
  plugins: [react()],
  resolve: {
    alias: {
      '@': path.resolve(__dirname, './src'),
    },
  },
  // lazy 라우트에서만 import 되는 무거운 의존성을 dev 서버 기동 시 미리 prebundle 해
  // 런타임 재최적화(optimized-deps 재생성 → `?v=` 해시 교체)를 줄인다. 재최적화 순간
  // 발생하던 `Failed to fetch dynamically imported module` 동적 import 실패의 트리거 감소책.
  optimizeDeps: {
    include: [
      'react',
      'react-dom',
      'react-router-dom',
      '@tanstack/react-query',
      'konva',
      'react-konva', // 라벨링 캔버스 — lazy 라우트에서만 import돼 첫 방문 시 재최적화 유발하는 주범
      'recharts', // 통계 차트 — lazy 청크
      'axios',
      'zustand',
      'zod',
      'react-hook-form',
      '@hookform/resolvers/zod',
      'date-fns',
      'dayjs',
      'clsx',
      'tailwind-merge',
      'lucide-react',
    ],
  },
  server: {
    port: 5174,
    strictPort: true,
    host: true,
    // Vite 5.4+ Host 차단 대비 — 컨테이너 매핑 IP 접속 허용
    allowedHosts: ['localhost', '192.168.102.246'],
    // HMR 클라이언트가 외부 매핑 포트로 붙도록 (env 미설정 시 Vite 기본 동작 유지)
    ...(hmrClientPort ? { hmr: { clientPort: hmrClientPort } } : {}),
    headers: {
      'X-Content-Type-Options': 'nosniff',
      'X-Frame-Options': 'DENY',
      // connect-src 는 동일 출처 프록시(/api,/v1)를 쓰므로 'self' 로 충분.
      // BE 오리진은 프록시를 경유하니 명시 불필요. ws/wss 는 HMR 용으로 유지.
      'Content-Security-Policy':
        "default-src 'self'; script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline'; img-src 'self' data: blob:; connect-src 'self' ws: wss:; font-src 'self' data:; object-src 'none'; base-uri 'self'; frame-ancestors 'none'",
    },
    proxy: {
      '/api': proxyTarget,
      '/v1': proxyTarget,
      '/actuator': proxyTarget,
    },
  },
});
