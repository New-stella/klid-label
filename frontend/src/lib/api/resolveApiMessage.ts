import { ApiError } from './errors';

/** 서버 안내문을 그대로 보여줘도 되는(내부 정보가 없는) 상태 코드. */
const USER_FACING_STATUSES = new Set([400, 409, 412]);

/**
 * 400/409/412 응답의 사용자 메시지(userMessage)를 화면 문구로 매핑한다. 그 외(401/403/5xx 등)나
 * ApiError 가 아닌 예외는 내부 정보 노출을 막기 위해 항상 fallback 문구로 통일한다.
 *
 * - 412(PRECONDITION_FAILED)는 "비식별 재처리 대기 중인 영상은 …" 처럼 사용자가 다음 행동을 정할 수
 *   있는 안내문을 담는다. 노출하지 않으면 일반 오류 문구로 대체돼 사용자가 이유를 알 수 없다.
 *
 * 관리 화면(라벨 마스터 / 라벨 속성 정의)에서 공유하는 헬퍼.
 */
export function resolveApiMessage(err: unknown, fallback: string): string {
  if (err instanceof ApiError && USER_FACING_STATUSES.has(err.status)) {
    return err.userMessage || fallback;
  }
  return fallback;
}
