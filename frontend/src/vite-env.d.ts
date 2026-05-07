/// <reference types="vite/client" />

interface ImportMetaEnv {
  readonly VITE_API_BASE_URL: string;
  readonly VITE_TOKEN_INGRESS: 'url' | 'cookie';
  readonly VITE_CONTROL_LOGIN_URL: string;
  readonly VITE_PORTAL_LOGIN_URL: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}
