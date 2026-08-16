/// <reference types="vite/client" />

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
  // [개발/검수 전용] prod 빌드에서도 /dev/autolabel-test 노출 토글 (폐쇄망 bring-up). 'true' 일 때만 노출, 기본 미설정.
  readonly VITE_DEV_UPLOAD_ENABLED?: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}
