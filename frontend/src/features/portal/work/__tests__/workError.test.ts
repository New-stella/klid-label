// 포털 작업 창구 실패 안내 — ★「없다」와 「남의 것이다」를 <b>가르지 않는다</b>.
//
// @design SCREEN-029
// @design API-234
// @design AC-1068
import { describe, expect, it } from 'vitest';

import { ApiError } from '@/lib/api/errors';

import {
  isPortalUnavailableError,
  PORTAL_WORK_ERROR_DEIDENT,
  PORTAL_WORK_ERROR_GENERIC,
  PORTAL_WORK_ERROR_UNAVAILABLE,
  portalWorkErrorMessage,
} from '../workError';

function err(status: number) {
  return ApiError.fromStatus(status);
}

describe('실재 여부 비노출', () => {
  /*
   * 창구는 본인 작업 대상이 아닌 요청에 실재 여부가 드러나지 않는 응답을 준다. 화면이 그것을
   * 「없습니다」와 「권한이 없습니다」로 나누면 그 감춤이 <b>화면 층에서 그대로 풀린다</b> —
   * 그 구분 자체가 남의 저작물이 있는지 알아내는 수단이 된다.
   */
  it('남의_것을_물었을_때와_없는_것을_물었을_때의_문구가_같다', () => {
    expect(portalWorkErrorMessage(err(403))).toBe(portalWorkErrorMessage(err(404)));
    expect(portalWorkErrorMessage(err(403))).toBe(PORTAL_WORK_ERROR_UNAVAILABLE);
  });

  it('그_문구가_어느_쪽인지_알려_주는_낱말을_담지_않는다', () => {
    // 「없습니다」·「존재하지」 류가 들어가면 404 만 그 문구를 받는 것으로 읽혀 구분이 되살아난다.
    expect(PORTAL_WORK_ERROR_UNAVAILABLE).not.toMatch(/없는|존재하지|삭제/);
  });

  /*
   * 문구 축과 <b>화면 갈림 축</b>은 다르다 — 문구가 같아도 라벨 축처럼 <b>화면 자체</b>를 가르면
   * 구분이 되살아난다. 그 판정을 화면마다 다시 쓰지 않도록 여기서 소유하며, 세 축(라벨·메타·
   * 이벤트 어노테이션)이 이 함수 하나를 쓴다.
   */
  it('403과_404를_같은_판정으로_모은다', () => {
    expect(isPortalUnavailableError(err(403))).toBe(true);
    expect(isPortalUnavailableError(err(404))).toBe(true);
    // 상태코드가 없고 오류코드만 있는 형태도 같게 판정한다(응답 본문만 받는 경로).
    expect(isPortalUnavailableError({ errorCode: 'FORBIDDEN' })).toBe(true);
    expect(isPortalUnavailableError({ errorCode: 'NOT_FOUND' })).toBe(true);
  });

  it('사유가_다른_거부까지_끌어오지_않는다', () => {
    // 412·400·500 은 실재 여부 오라클이 아니다 — 여기 섞으면 「해소하면 된다」가 사라진다.
    expect(isPortalUnavailableError(err(412))).toBe(false);
    expect(isPortalUnavailableError(err(400))).toBe(false);
    expect(isPortalUnavailableError(err(500))).toBe(false);
    expect(isPortalUnavailableError(new Error('network'))).toBe(false);
    expect(isPortalUnavailableError(undefined)).toBe(false);
  });
});

describe('그 밖의 거부', () => {
  it('비식별_누락_신고_구간은_형제_창구와_같은_문구다', () => {
    expect(portalWorkErrorMessage(err(412))).toBe(PORTAL_WORK_ERROR_DEIDENT);
    // 실재 여부 문구와 섞이면 안 된다 — 이건 「지금은 안 되지만 해소되면 된다」는 다른 사실이다.
    expect(portalWorkErrorMessage(err(412))).not.toBe(PORTAL_WORK_ERROR_UNAVAILABLE);
  });

  it('모르는_실패는_상태코드를_해석하지_않고_사실만_알린다', () => {
    expect(portalWorkErrorMessage(err(500))).toBe(PORTAL_WORK_ERROR_GENERIC);
    expect(portalWorkErrorMessage(new Error('network'))).toBe(PORTAL_WORK_ERROR_GENERIC);
  });
});
