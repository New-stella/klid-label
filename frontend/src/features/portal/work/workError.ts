// 포털 작업 화면(메타·이벤트 어노테이션) 창구 실패 → 사용자 안내 문구의 <b>단일 판정 지점</b>.
//
// <h3>★★ 「없다」와 「있지만 남의 것이다」를 가르지 않는다</h3>
// 창구는 본인 작업 대상이 아닌 요청에 <b>실재 여부가 드러나지 않는 응답</b>을 준다. 화면이 그것을
// 「없습니다」와 「권한이 없습니다」로 나누면 서버가 감춘 것이 <b>화면 층에서 그대로 풀린다</b> —
// 그 구분 자체가 남의 저작물이 있는지 알아내는 수단이 된다. 그래서 403·404 는 <b>같은 문구</b>다.
//
// ⚠ 본인 자산의 <b>진행 상태</b>를 가르는 것(처리 실패 / 준비 중)은 축이 다르다 — 그건 남의 것이
//   드러나지 않으므로 갈라도 된다. 이 파일의 규칙을 그쪽에 적용하지 말 것.
//
// 보안(CWE-209): 문구는 <b>상태코드로만</b> 만든다. 서버 메시지·내부 경로를 그대로 싣지 않는다.

// @design SCREEN-029 @design API-234 @design API-235 @design API-237 @design AC-1068

import { ApiError } from '@/lib/api/errors';

/**
 * 본인 작업 대상이 아니거나 대상이 없다 — <b>403·404 공통</b>.
 *
 * ★이 상수를 둘로 쪼개지 말 것. 쪼개는 순간 화면이 실재 여부를 알려 준다.
 */
export const PORTAL_WORK_ERROR_UNAVAILABLE =
  '요청한 작업 대상을 이용할 수 없습니다. 본인 작업 대상인지 확인해 주세요.';

/** 비식별 누락 신고가 열린 원천 영상이라 거부됐다 — 형제 창구와 같은 문구다. */
export const PORTAL_WORK_ERROR_DEIDENT =
  '비식별 재처리 대기 중인 영상입니다. 재비식별 완료 후 다시 시도해 주세요.';

/** 값이 규격을 벗어났다(표시 전용 항목 수정 시도·폭 초과 등). */
export const PORTAL_WORK_ERROR_INVALID = '저장할 수 없는 값이 있습니다. 입력을 확인해 주세요.';

/** 그 밖의 실패. */
export const PORTAL_WORK_ERROR_GENERIC = '요청을 처리하지 못했습니다. 잠시 후 다시 시도해 주세요.';

/** 상태코드·오류코드를 형태에 상관없이 꺼낸다(ApiError 가 아닌 오류도 안전하게 다룬다). */
function statusOf(error: unknown): { status?: number; errorCode?: string } {
  if (error instanceof ApiError) return { status: error.status, errorCode: error.errorCode };
  if (typeof error === 'object' && error !== null) {
    const e = error as { status?: unknown; errorCode?: unknown };
    return {
      status: typeof e.status === 'number' ? e.status : undefined,
      errorCode: typeof e.errorCode === 'string' ? e.errorCode : undefined,
    };
  }
  return {};
}

/**
 * <b>실재 여부를 감춰야 하는 거부인가</b> — 403·404 공통.
 *
 * <h3>★이 판정을 화면마다 다시 쓰지 말 것</h3>
 * 한 화면이 두 상태를 <b>다른 화면·다른 문구</b>로 가르면 서버가 감춘 것이 그 자리에서 그대로
 * 풀린다. 백엔드는 실제로 「미노출·미승인」에 403 을, 「대상 없음」에 404 를 내므로, 가르는 화면은
 * 그 자체가 <b>「남의 저작물이 실재하는가」를 알아내는 오라클</b>이 된다. 세 축(라벨·메타·이벤트
 * 어노테이션) 전부가 이 판정 하나를 쓴다.
 *
 * ⚠ 내부 채널은 이 규칙의 대상이 아니다 — 그쪽은 남의 저작물을 감출 이유가 없다.
 */
export function isPortalUnavailableError(error: unknown): boolean {
  const { status, errorCode } = statusOf(error);
  return status === 403 || status === 404 || errorCode === 'FORBIDDEN' || errorCode === 'NOT_FOUND';
}

/** 창구 실패 → 안내 문구. */
export function portalWorkErrorMessage(error: unknown): string {
  const { status } = statusOf(error);
  switch (status) {
    case 412:
      return PORTAL_WORK_ERROR_DEIDENT;
    case 400:
      return PORTAL_WORK_ERROR_INVALID;
    // ★403 과 404 는 한 문구다 — 가르면 실재 여부가 드러난다.
    case 403:
    case 404:
      return PORTAL_WORK_ERROR_UNAVAILABLE;
    default:
      return PORTAL_WORK_ERROR_GENERIC;
  }
}
