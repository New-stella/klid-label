import path from 'node:path';

import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import { federation } from '@module-federation/vite';

// [@design INT-013]
// ★ 계약값은 `src/lib/remoteMountContract` 가 단일 진실원이다 — 여기에 문자열을 다시 적지 않는다.
//   그 파일이 `remoteMount.ts` 에서 갈라져 나온 이유가 바로 이 import 다: 이 설정 파일은
//   브라우저가 아니라 Node 에서 평가되는데 `remoteMount.ts` 는 `buildChannel` 을 거쳐
//   최상위 `import.meta.env` 에 닿아 Node 에서 깨진다. 사유 전문은 그 파일 헤더에 있다.
import {
  REMOTE_BUNDLE_BASE_PATH,
  REMOTE_ENTRY_FILE_NAME,
  REMOTE_EXPOSED_MODULE_NAME,
  REMOTE_NAME,
} from './src/lib/remoteMountContract';

// ★ 빌드 채널 — 포털 채널일 때만 Module Federation 을 배선한다.
//   `src/lib/buildChannel` 의 판정과 «같은 키»(VITE_BUILD_CHANNEL)를 읽는다. 다만 그쪽은
//   브라우저에서 `import.meta.env` 를, 여기는 Node 에서 `process.env` 를 읽는다 — Vite 가
//   빌드 시점에 전자를 후자에서 채우므로 같은 값이다.
//   ⚠ 관제 채널에 federation 을 걸지 않는 이유: 그 산출물은 독립 앱이라 Host 가 없고,
//     플러그인이 청크 구성과 런타임을 바꿔 지금 도는 배포본의 형태가 달라진다.
const IS_PORTAL_BUILD = process.env.VITE_BUILD_CHANNEL === 'portal';

// ★ 서버 없이 띄우는 시연판(넷리파이) — 포털 채널 화면을 «단독 문서»로 굽는다.
//   Host 가 없으므로 Module Federation 을 걸지 않고 자산 base 도 루트('/')다.
//   가짜 응답·가짜 로그인은 진입점(main.tsx)이 같은 키를 읽어 세운다(`demo/installDemo`).
const IS_DEMO_BUILD = process.env.VITE_DEMO_STANDALONE === 'true';

// 컨테이너/네트워크 배포 대응 환경변수 (미설정 시 로컬 개발 기본값 보존)
//   - BACKEND_ORIGIN  : 프록시 대상 BE 오리진 (컨테이너: http://klid-backend:8080)
//   - HMR_CLIENT_PORT : HMR 클라이언트가 접속할 외부 매핑 포트 (컨테이너: 13000)
const backendOrigin = process.env.BACKEND_ORIGIN || 'http://localhost:8080';
const hmrClientPort = process.env.HMR_CLIENT_PORT ? Number(process.env.HMR_CLIENT_PORT) : undefined;

const proxyTarget = {
  target: backendOrigin,
  changeOrigin: true,
};

// 포털이 저작도구를 iframe 으로 띄우는 출처 — 이 출처에만 액자 안에 들어가는 것을 허락한다.
const portalFrameAncestors = process.env.PORTAL_FRAME_ANCESTORS || 'http://localhost:5174 http://127.0.0.1:5174';

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
//   ★★ 포털 채널에서는 이 값을 «환경변수로 정하지 않는다» — 계약값(/label-remote/)으로 굳는다.
//     Host 가 그 경로에서 remoteEntry.js 와 청크를 찾기 때문이다. 다른 값을 주면 진입 파일은
//     찾아지는데 그것이 참조하는 청크만 404 가 되어 «화면이 절반만 뜨는» 형태로 드러난다.
//     그래서 어긋난 값이 오면 조용히 무시하지 않고 «빌드를 실패»시킨다(fail-closed).
//     ⚠ 실제로 반입 잡이 포털 채널에 VITE_BASE_PATH=/author/ 를 주고 있었다. 그 조합은
//       자산만 /author/ 로 가고 라우터 기준 경로는 /workspace/authoring 이라 어떤 화면도 안 걸린다.
//     ★ 이 고정은 «빌드에만» 건다. dev 서버까지 /label-remote/ 로 올리면 단독 개발 진입이
//       깨진다 — 그때 주소는 마운트 경로(/workspace/authoring/...)여야 하는데 Vite 가 앱을
//       /label-remote/ 아래에 서빙해 둘이 양립하지 않는다(devHostStub 로 Host 없이 띄우는 경로).
function resolveBasePath(command: 'build' | 'serve'): string {
  const given = process.env.VITE_BASE_PATH;
  if (IS_DEMO_BUILD) return '/';
  if (!IS_PORTAL_BUILD || command !== 'build') return given ?? '/';
  if (given !== undefined && given !== REMOTE_BUNDLE_BASE_PATH) {
    throw new Error(
      `[klid] 포털 채널 빌드의 자산 base 는 계약값 '${REMOTE_BUNDLE_BASE_PATH}' 로 고정입니다.\n` +
        `  받은 값 : VITE_BASE_PATH=${given}\n` +
        `  · 포털 Host 가 그 경로에서 ${REMOTE_ENTRY_FILE_NAME} 와 청크를 찾습니다.\n` +
        `  · 포털 채널 빌드에서는 이 변수를 «주지 마세요». 관제 채널에서만 씁니다.`,
    );
  }
  return REMOTE_BUNDLE_BASE_PATH;
}

export default defineConfig(({ command }) => ({
  base: resolveBasePath(command),
  plugins: [
    react(),
    // [@design INT-013] 포털 Host 가 런타임에 결합할 Remote 진입점을 만든다.
    //   Host 는 `authoring/PortalApp` 으로 우리를 가져간다 — 원격 모듈명과 노출 모듈명이
    //   «함께» 그 지정자를 이루므로 둘 중 하나만 바뀌어도 Host 가 우리를 못 찾는다.
    //   ⚠ dev 서버에는 걸지 않는다 — 단독 개발(devHostStub)은 Host 없이 뜨는 경로이고,
    //     Remote 배선은 산출물의 성질이라 그때 필요하지 않다.
    ...(IS_PORTAL_BUILD && !IS_DEMO_BUILD && command === 'build'
      ? [
          federation({
            name: REMOTE_NAME,
            filename: REMOTE_ENTRY_FILE_NAME,
            // 키는 «Host 가 부르는 이름», 값은 «우리 파일 경로» — 둘이 다른 것은 의도다.
            exposes: { [REMOTE_EXPOSED_MODULE_NAME]: './src/remote/AuthoringRemote.tsx' },
            // ★ react/react-dom 은 반드시 «한 벌»이어야 한다. 두 벌이 되면 Host 트리 안에서
            //   훅과 컨텍스트가 갈려 런타임에만 드러나는 형태로 깨진다.
            //   ⚠ react-router-dom 은 «공유하지 않는다» — 우리는 자체 라우터를 마운트 경로
            //     기준으로 갖고, Host 도 자기 라우터를 갖는다. 공유하면 두 라우터가 한 인스턴스를
            //     두고 다툰다.
            shared: {
              react: { singleton: true },
              'react-dom': { singleton: true },
            },
            // ★ 우리 전역 스타일·폰트를 노출 모듈에 함께 싣는다. Host 는 우리 CSS 를 모르므로
            //   이걸 끄면 «스타일 없는 화면»이 뜬다(진입점이 styles/bootstrap 을 import 하는 이유).
            bundleAllCSS: true,
            // Host 가 원격을 찾을 때 쓰는 표준 기술서. 있으면 Host 배선이 쉬워지고 비용은 없다.
            manifest: true,
            // 라우트가 많아 모듈 파싱이 기본 10초를 넘길 수 있다. 「마지막 활동 이후」 기준으로 둔다.
            moduleParseIdleTimeout: 30,
          }),
        ]
      : []),
  ],
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
      // 포털 채널은 포털 iframe 안에 들어가야 하므로 액자 막기를 풀고 아래 frame-ancestors 로 출처를 좁힌다
      ...(IS_PORTAL_BUILD ? {} : { 'X-Frame-Options': 'DENY' }),
      // connect-src 는 동일 출처 프록시(/api,/v1)를 쓰므로 'self' 로 충분.
      // BE 오리진은 프록시를 경유하니 명시 불필요. ws/wss 는 HMR 용으로 유지.
      'Content-Security-Policy':
        "default-src 'self'; script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline'; img-src 'self' data: blob:; connect-src 'self' ws: wss:; font-src 'self' data:; object-src 'none'; base-uri 'self'; frame-ancestors " +
        (IS_PORTAL_BUILD ? `'self' ${portalFrameAncestors}` : "'none'"),
    },
    proxy: {
      '/api': proxyTarget,
      '/v1': proxyTarget,
      '/actuator': proxyTarget,
    },
  },
}));
