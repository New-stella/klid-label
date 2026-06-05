import { NoticeSearchField, type NoticeListParams } from './types';

const PAGE_SIZE = 20;

/** 검색 필드 화이트리스트 가드 — BE SearchField enum 외 값은 거부(타입 단언 제거). */
export function toNoticeSearchField(value: string): NoticeSearchField | undefined {
  return (Object.values(NoticeSearchField) as string[]).includes(value)
    ? (value as NoticeSearchField)
    : undefined;
}

/**
 * URL searchParams → NoticeListParams 변환.
 * 보안: 모든 입력은 string. 숫자 필드는 Number.parseInt 후 NaN 체크, 검색 필드는 화이트리스트 가드.
 */
export function parseNoticeListParams(params: URLSearchParams): NoticeListParams {
  const next: NoticeListParams = { page: 0, size: PAGE_SIZE };

  const page = params.get('page');
  if (page) {
    const n = Number.parseInt(page, 10);
    if (Number.isFinite(n) && n >= 0) next.page = n;
  }

  const keyword = params.get('keyword');
  if (keyword) {
    next.keyword = keyword.slice(0, 100);
    const field = toNoticeSearchField(params.get('field') ?? '');
    next.field = field ?? NoticeSearchField.ALL;
  }

  return next;
}

/**
 * NoticeListParams → URLSearchParams.
 * 기본값(page 0, 검색어 없음)은 제외해 URL을 깔끔하게 유지.
 */
export function noticeListParamsToSearchParams(params: NoticeListParams): URLSearchParams {
  const sp = new URLSearchParams();
  if (params.page) sp.set('page', String(params.page));
  if (params.keyword) {
    sp.set('keyword', params.keyword);
    sp.set('field', params.field ?? NoticeSearchField.ALL);
  }
  return sp;
}
