import { isStageBundle, type VideoListParams } from './types';

/**
 * URL searchParams → VideoListParams 변환.
 * 보안: 모든 입력은 string. 숫자 필드는 Number.parseInt 후 NaN 체크.
 */
export function parseVideoListParams(params: URLSearchParams): VideoListParams {
  const next: VideoListParams = {};

  const page = params.get('page');
  if (page) {
    const n = Number.parseInt(page, 10);
    if (Number.isFinite(n) && n >= 0) next.page = n;
  } else {
    next.page = 0;
  }

  const size = params.get('size');
  next.size = 20;
  if (size) {
    const n = Number.parseInt(size, 10);
    if (Number.isFinite(n) && n > 0 && n <= 100) next.size = n;
  }

  const sort = params.get('sort');
  if (sort) next.sort = sort;

  const keyword = params.get('cctvNameKeyword');
  if (keyword) next.cctvNameKeyword = keyword.slice(0, 100);

  const eventType = params.get('eventTypeCd');
  if (eventType) next.eventTypeCd = eventType;

  const localGov = params.get('localGovId');
  if (localGov) {
    const n = Number.parseInt(localGov, 10);
    if (Number.isFinite(n) && n > 0) next.localGovId = n;
  }

  const from = params.get('from');
  if (from && /^\d{4}-\d{2}-\d{2}$/.test(from)) next.from = from;

  const to = params.get('to');
  if (to && /^\d{4}-\d{2}-\d{2}$/.test(to)) next.to = to;

  const dataSttsCd = params.get('dataSttsCd');
  if (dataSttsCd) next.dataSttsCd = dataSttsCd.slice(0, 50);

  // [@design SCREEN-008] [@design ADR-050] 시계열 건너뜀 필터.
  //   ⚠ 화이트리스트 교집합만 통과시킨다 — URL 은 사용자가 손으로 쓸 수 있는 입력이고 이 값은
  //     그대로 조회 파라미터가 된다. 미지의 문자열은 **버린다**(빈 값으로 접지 않는다 — 아래
  //     직렬화가 undefined 를 파라미터에서 제외하므로 "필터 없음"과 같은 상태가 된다).
  const skippedStage = params.get('skippedStage');
  if (skippedStage && isStageBundle(skippedStage)) next.skippedStage = skippedStage;

  // [@design SCREEN-008] [@design ADR-050] 작업 묶음 **실패** 필터 — 건너뜀 필터와 **다른 축**이다.
  //   판정 규칙(화이트리스트 교집합·미지 값 폐기)은 같으므로 같은 검증기를 쓴다.
  const failedStage = params.get('failedStage');
  if (failedStage && isStageBundle(failedStage)) next.failedStage = failedStage;

  return next;
}

/**
 * VideoListParams → URLSearchParams.
 * 빈 값/undefined는 제외.
 */
export function videoListParamsToSearchParams(params: VideoListParams): URLSearchParams {
  const sp = new URLSearchParams();
  if (params.page !== undefined && params.page !== 0) sp.set('page', String(params.page));
  if (params.size !== undefined && params.size !== 20) sp.set('size', String(params.size));
  if (params.sort) sp.set('sort', params.sort);
  if (params.cctvNameKeyword) sp.set('cctvNameKeyword', params.cctvNameKeyword);
  if (params.eventTypeCd) sp.set('eventTypeCd', params.eventTypeCd);
  if (params.localGovId !== undefined) sp.set('localGovId', String(params.localGovId));
  if (params.from) sp.set('from', params.from);
  if (params.to) sp.set('to', params.to);
  if (params.dataSttsCd) sp.set('dataSttsCd', params.dataSttsCd);
  // 미선택이면 키 자체를 두지 않는다 — 빈 문자열을 올리면 서버가 값으로 해석할 여지가 생기고,
  // 이 필터를 모르는 기존 북마크의 동작이 달라진다(하위호환).
  if (params.skippedStage) sp.set('skippedStage', params.skippedStage);
  // 위와 같은 이유로 미선택이면 키 자체를 두지 않는다(하위호환) — 두 필터는 동시에 실릴 수 있다.
  if (params.failedStage) sp.set('failedStage', params.failedStage);
  return sp;
}
