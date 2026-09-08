import path from 'node:path';

import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// 컨테이너/네트워크 배포 대응 환경변수 (미설정 시 로컬 개발 기본값 보존)
//   - BACKEND_ORIGIN  : 프록시 대상 BE 오리진 (미설정 시 개발서버 http://192.168.102.246:13005,
//                       로컬 BE 를 쓰려면 BACKEND_ORIGIN=http://localhost:8080)
//   - HMR_CLIENT_PORT : HMR 클라이언트가 접속할 외부 매핑 포트 (컨테이너: 13000)
const backendOrigin = process.env.BACKEND_ORIGIN || 'http://192.168.102.246:13005';
const hmrClientPort = process.env.HMR_CLIENT_PORT
  ? Number(process.env.HMR_CLIENT_PORT)
  : undefined;

const proxyTarget = {
  target: backendOrigin,
  changeOrigin: true,
};

// [@design INT-013]
/**
 * **부수효과가 없다고 선언하는 모듈** — 아무도 쓰지 않으면 산출물에서 통째로 떨어져 나가게 한다.
 *
 * 번들러의 기본 가정은 「import 된 모듈은 부수효과가 있을 수 있다」다. 그래서 그 모듈의 export 를
 * **아무도 쓰지 않아도** 최상위 문장이 산출물에 남는다. 여기 적힌 셋은 내부(관제) 채널 전용이라
 * 포털 채널 산출물에서는 쓰는 곳이 하나도 없는데도, 그 기본 가정 때문에 그대로 실려 나갔다.
 *
 * ## 무엇이 실려 나갔나 (실측)
 * `lib/routeAccess` 는 내부 채널의 IA 그 자체다 — 메뉴 그룹·항목의 **한글 이름 19건**, 전 내부
 * 경로, 경로별 **역할 허용목록**. 포털 산출물에서 `menu:{group:` 이 19건, `/admin/users` ·
 * `/manage/settings` · `/deident-reports` 가 각각 잡혔다.
 * ⚠ **포털향은 이 시스템에서 외부에 노출되는 유일한 향이다**(관제향은 내부망). 내부 관리 메뉴
 *   이름과 역할 허용목록이 그 산출물에 실리는 것은 대상이 정확히 반대다.
 *
 * ## 왜 셋인가 — 사슬을 끊으려면 사슬 전체가 필요하다
 * 붙잡고 있던 것은 `router/index.tsx` → `AppLayout` → `Lnb` → `routeAccess` 사슬이다. 맨 끝만
 * 선언하면 앞의 둘이 「부수효과가 있을 수 있는 모듈」로 남아 사슬이 끊기지 않는다
 * (**실측: 끝 하나만 선언한 판에서 19건이 그대로였다**). 셋을 함께 선언해야 0이 된다.
 *
 * ## 시도했다가 쓰지 않은 방법 — 되풀이하지 말 것
 * 호출 자리에 「부수효과 없음」 표시(`#__PURE__`)를 붙이는 방법을 먼저 썼다. `routeAccess` 의
 * 최상위 `Object.freeze` 8곳과 `Lnb` 의 최상위 호출 1곳에 붙였는데 **19건 그대로였다.**
 * 그 표시는 *그 호출*을 지울 수 있게 할 뿐 *모듈을 포함할지*를 바꾸지 못한다. 반대로 이 선언만
 * 두고 표시를 전부 떼도 **0건**이라, 표시는 이 결과에 **기여하지 않는다**(절제 실험으로 확인).
 * 그래서 표시는 남기지 않았다 — 효과 없는 장치를 「근거」라고 적어 두면 다음 사람이 그것을
 * 지키느라 진짜 근거를 잃는다.
 *
 * ## 선언이 참인 근거 (늘릴 때도 같은 절차를 밟을 것)
 * 셋 다 최상위에 **선언문만** 있다 — 리터럴을 굳히거나(`Object.freeze`) 조회용 맵을 만들고
 * 컴포넌트를 정의할 뿐, 전역을 건드리거나 등록·구독하는 문장이 없다(직접 읽고 확인했다).
 * ⚠ 목록을 늘릴 때는 그 모듈의 최상위를 **직접 열어** 확인할 것. 거짓으로 올리면 그 모듈의
 *   초기화가 조용히 사라져 런타임에만 드러난다.
 *
 * ## 관제 채널은 그대로다
 * 쓰는 쪽이 있으면 남는다 — 관제 산출물에는 지금과 똑같이 실린다(실측: `menu:{group:` 19건 ·
 * `좌측 메뉴` 1건 · 청크 125개로 변화 없음). **양쪽이 함께 0이 되면 그건 관제향 메뉴가 통째로
 * 사라진 것이라 성공이 아니라 실패다.** 두 수치를 반드시 나란히 볼 것.
 *
 * 회귀 가드: `src/lib/__tests__/routeAccessChannelStripping.test.ts`
 */
const SIDE_EFFECT_FREE_MODULES = [
  '/src/lib/routeAccess.ts',
  '/src/components/layout/Lnb.tsx',
  '/src/components/layout/AppLayout.tsx',
];

// ★ 자산 base 경로 — 앱이 도메인 루트가 아닌 하위 경로에서 열릴 때 필요하다.
//   현장(관제 채널)은 https://www.aicctv.go.kr/label-studio/ 에서 열리므로
//   빌드 시 VITE_BASE_PATH=/label-studio/ 를 준다. 미지정이면 종전대로 '/'.
//   ⚠ 끝의 슬래시가 의미를 가진다 — 없으면 /label-studioassets/... 처럼 붙는다.
//   ⚠ 이 값은 import.meta.env.BASE_URL 로 앱에 노출되고, 라우터 basename 이 그것을 읽는다
//     (lib/remoteMount.resolveRouterBasename). 두 값을 따로 주면 갈린다.
const BASE_PATH = process.env.VITE_BASE_PATH ?? '/';

export default defineConfig({
  base: BASE_PATH,
  plugins: [react()],
  build: {
    rollupOptions: {
      treeshake: {
        moduleSideEffects(id) {
          return !SIDE_EFFECT_FREE_MODULES.some((suffix) => id.endsWith(suffix));
        },
      },
    },
  },
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
