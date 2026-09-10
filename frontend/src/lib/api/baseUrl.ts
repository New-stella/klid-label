import { resolveConfig } from '@/lib/runtimeConfig';

/**
 * 저작도구 API 의 base URL — **요청 클라이언트가 둘이어도 해석은 한 곳**이다.
 *
 * 일반 요청(`lib/api/client` 의 `apiClient`)과 관제 세션 중계 요청(`features/auth/controlSession`)은
 * 인터셉터를 공유하면 안 되어 axios 인스턴스를 따로 두지만, **같은 서버의 같은 접두**를 불러야 한다.
 * 해석을 각자 하면 한쪽만 설정 키·기본값이 바뀌어 중계 요청만 엉뚱한 곳으로 나간다.
 *
 * 보안: 설정에서만 로드한다(사용자 입력 금지). 해석 순서는 런타임(`klid-config.js`) → 빌드 → 기본값.
 */
export function resolveApiBaseUrl(): string {
  return resolveConfig('VITE_API_BASE_URL') ?? '/api/v1';
}
