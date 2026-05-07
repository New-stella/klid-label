import type { VideoListParams } from './types';

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
  return sp;
}
