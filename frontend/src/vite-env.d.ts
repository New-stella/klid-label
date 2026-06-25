/// <reference types="vite/client" />

interface ImportMetaEnv {
  readonly VITE_API_BASE_URL: string;
  readonly VITE_TOKEN_INGRESS: 'url' | 'cookie';
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
