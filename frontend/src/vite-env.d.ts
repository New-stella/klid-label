/// <reference types="vite/client" />

// ★ 이 선언들은 <빌드 시점> 값이다. 배포 형상에서는 같은 이름의 값이 런타임 설정
//   (`lib/runtimeConfig` — 문서 루트의 klid-config.js)으로 덮인다. 앱 코드는 `import.meta.env`
//   를 직접 읽지 말고 `resolveConfig()` 를 쓴다 — 직접 읽는 곳은 그 값만 조용히 재빌드가
//   필요한 상태로 남는다.

interface ImportMetaEnv {
  readonly VITE_API_BASE_URL: string;
  // 토큰 인계 채널 전략. 값 집합의 단일 진실원은 `features/auth/tokenIngress` 의
  // `IngressStrategy` 이며 여기서는 참조만 한다 (사본을 두면 두 번째 진실원이 된다).
  // 미설정 시 `DEFAULT_INGRESS_STRATEGY`('localStorage') 로 해석된다.
  readonly VITE_TOKEN_INGRESS?: import('./features/auth/tokenIngress').IngressStrategy;
  readonly VITE_CONTROL_LOGIN_URL: string;
  readonly VITE_PORTAL_LOGIN_URL: string;
  // [개발/검수 전용] prod 빌드에서도 /dev/login 노출 토글 (폐쇄망 bring-up). 'true' 일 때만 노출, 기본 미설정.
  readonly VITE_DEV_LOGIN_ENABLED?: string;
  // prod 빌드에서도 /admin/uploads 노출 토글 (폐쇄망 bring-up). 'true' 일 때만 노출, 기본 미설정.
  readonly VITE_DEV_UPLOAD_ENABLED?: string;
  // 빌드 채널('control'|'portal') — 이 산출물이 어느 채널용인지. 값 집합의 단일 진실원은
  // `lib/buildChannel`의 `BuildChannel`이며 여기서는 참조만 한다(사본을 두면 두 번째 진실원이 된다).
  // 미설정 시 `DEFAULT_BUILD_CHANNEL`('control')로 해석된다 — 지금 동작(자체 셸 포함 독립 앱) 유지.
  readonly VITE_BUILD_CHANNEL?: import('./lib/buildChannel').BuildChannel;
  /** 'true' 면 서버 없이 띄우는 시연판 — 가짜 응답·가짜 로그인을 세운다(`demo/installDemo`) */
  readonly VITE_DEMO_STANDALONE?: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}
