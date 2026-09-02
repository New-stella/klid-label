/**
 * 포털 업로드 영상 마킹 — 창구 계약 타입. [@design API-238] [@design API-239] [@design API-240]
 * [@design API-241]
 *
 * <h3>지점 표현을 새로 만들지 않는다</h3>
 * 저장은 <b>기존 마킹 원장</b>을 그대로 쓰기로 확정됐고, 지점 1건의 모양(프레임 번호 + 표시용
 * 시각)도 그 원장의 것이다. 그래서 여기서 다시 정의하지 않고 마킹 도메인의 타입을 **참조**한다 —
 * 같은 모양을 두 곳에 적으면 한쪽만 바뀌었을 때 조용히 어긋난다.
 *
 * ⚠ 타입 전용 import 라 산출물에 코드가 실리지 않는다(관제 채널 화면을 끌어오지 않는다).
 */
import type { MarkItem } from '@/features/marking/types';

export type { MarkItem };

/** 마킹 방식 — 창구가 받는 두 값뿐이다(그 밖은 400). */
export const PortalMarkingMode = {
  AUTO: 'AUTO',
  MANUAL: 'MANUAL',
} as const;
export type PortalMarkingMode = (typeof PortalMarkingMode)[keyof typeof PortalMarkingMode];

/**
 * 단기 서명 재생 주소(API-239 응답).
 *
 * `expiresAt` 은 에포크 <b>초</b>다(밀리초가 아니다). 화면이 만료 직전 재발급을 판단하는 값이며,
 * 재생이 끊긴 뒤 다시 받는 것이 정상 동선이라 횟수 제한이 없다.
 */
export interface PortalStreamUrl {
  url: string;
  expiresAt: number;
  ttlSeconds: number;
}

/**
 * 마킹 저장 요청 본문(API-240).
 *
 * ⚠ 대상 영상은 <b>경로</b>가 정하므로 본문에 다시 싣지 않는다.
 * ⚠ 자동은 `marks` 를, 수동은 `interval` 을 쓰지 않는다 — 조립은 {@link buildMarkingSaveRequest}
 *   한 곳이 한다(호출부가 직접 만들지 말 것).
 */
export interface PortalMarkingSaveRequest {
  mode: PortalMarkingMode;
  interval?: number;
  marks?: MarkItem[];
}

/**
 * 마킹 저장 응답(API-240, 201).
 *
 * ★ 이 응답이 확정적으로 말하는 것은 <b>저장했고 추출이 시작되도록 상태를 넘겼다</b>는 사실이며
 *   프레임이 준비됐다는 뜻이 아니다(`uldSttsCd` 는 `PROCESSING`).
 * ★ `truncated`·`requestedMarkCount` 는 <b>절단이 조용히 일어나지 않게</b> 하려고 있는 값이다.
 *   `markCount` 만 보면 그 수가 내가 보낸 수인지 잘린 수인지 구분할 수 없다.
 */
export interface PortalMarkingSaveResult {
  markingSn: number;
  uldSn: number;
  mode: PortalMarkingMode;
  interval: number | null;
  marks: MarkItem[];
  markCount: number;
  requestedMarkCount: number;
  truncated: boolean;
  uldSttsCd: string;
  regDt: string;
}

/** 저장된 마킹 1건(API-241). */
export interface PortalMarkingListItem {
  markingSn: number;
  mode: string;
  interval: number | null;
  marks: MarkItem[];
  markCount: number;
  regDt: string;
}

/**
 * 저장된 마킹 목록(API-241).
 *
 * 저장 시각 내림차순이며 저장된 것이 없으면 <b>빈 목록</b>이다 — 없다는 것도 화면이 알아야 하는
 * 사실이라 오류로 오지 않는다.
 */
export interface PortalMarkingList {
  uldSn: number;
  markings: PortalMarkingListItem[];
}
