import { ApiError } from './errors';

/**
 * 400/409 응답의 사용자 메시지(userMessage)를 화면 문구로 매핑한다. 그 외(401/403/5xx 등)나
 * ApiError 가 아닌 예외는 내부 정보 노출을 막기 위해 항상 fallback 문구로 통일한다.
 *
 * 관리 화면(라벨 마스터 / 라벨 속성 정의)에서 공유하는 헬퍼.
 */
export function resolveApiMessage(err: unknown, fallback: string): string {
  if (err instanceof ApiError && (err.status === 400 || err.status === 409)) {
    return err.userMessage || fallback;
  }
  return fallback;
}
