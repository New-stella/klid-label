// 화면ID: KLID-AT-SC-030 — 게시판(공지) 목록
import { useMemo, useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { Pin, Plus, RotateCcw, Search } from 'lucide-react';

import { Button } from '@/components/common/Button';
import { EmptyState } from '@/components/common/EmptyState';
import { ErrorState } from '@/components/common/ErrorState';
import { Pagination } from '@/components/common/Pagination';
import { Skeleton } from '@/components/common/Skeleton';
import { useNotices } from '@/features/notice/hooks/useNotices';
import { KRDS_FOCUS } from '@/lib/focusRing';
import {
  noticeListParamsToSearchParams,
  parseNoticeListParams,
  toNoticeSearchField,
} from '@/features/notice/parseNoticeListParams';
import { NoticePubStatus, NoticeSearchField } from '@/features/notice/types';
import { cn } from '@/lib/cn';
import { Role } from '@/lib/api/types';
import { useAuthStore } from '@/stores/useAuthStore';

const PAGE_SIZE = 20;

const FIELD_OPTIONS: { value: NoticeSearchField; label: string }[] = [
  { value: NoticeSearchField.ALL, label: '제목+내용' },
  { value: NoticeSearchField.TITLE, label: '제목' },
  { value: NoticeSearchField.CONTENT, label: '내용' },
];

/**
 * 표 헤더 셀 클래스 — 모든 `<th>` 가 이 한 값을 공유한다(폭 지정만 호출부에서 덧붙인다).
 *
 * 글자색 하한은 `gray-600` 이다 — 헤더 배경이 secondary-50(#EEF2F7)이라 gray-500 은
 * 그 위에서 4.01:1 로 AA(4.5:1) 미달이다(gray-600 은 5.60:1).
 *
 * ⚠ 굵기는 `text-table-header` step(600)이 단독으로 정한다 — 별도 굵기 클래스를 겹치지
 * 않는다. 또 이 클래스는 반드시 **`<th>` 에 직접** 건다(`<tr>` 에만 걸면 UA 기본
 * `th { font-weight: bold }`(700)가 상속값을 이긴다).
 */
const TH_CLASS = 'px-4 py-3 text-left text-table-header uppercase tracking-wide text-gray-600';

function formatDate(iso: string | null): string {
  if (!iso) return '-';
  return iso.slice(0, 10);
}

export function NoticeListPage() {
  const navigate = useNavigate();
  const role = useAuthStore((s) => s.claims?.role) ?? Role.WORKER;
  const isReviewer = role === Role.REVIEWER;

  // 검색 조건·페이지는 URL 쿼리에 반영(뒤로가기 유지·URL 공유) — VideoListPage/ReviewListPage 컨벤션.
  const [searchParams, setSearchParams] = useSearchParams();
  const params = useMemo(() => parseNoticeListParams(searchParams), [searchParams]);
  const page = params.page ?? 0;
  const field = params.field ?? NoticeSearchField.ALL;

  // 입력 중 값(keywordInput) 과 적용된 검색어(URL keyword) 분리 — 검색 버튼/Enter 시에만 조회.
  const [keywordInput, setKeywordInput] = useState(params.keyword ?? '');
  const [fieldInput, setFieldInput] = useState<NoticeSearchField>(field);

  const { data, isLoading, error } = useNotices(params);

  const notices = data?.content ?? [];
  const totalElements = data?.totalElements ?? 0;

  const applySearch = () => {
    const trimmed = keywordInput.trim();
    const sp = noticeListParamsToSearchParams(
      trimmed
        ? { page: 0, size: PAGE_SIZE, field: fieldInput, keyword: trimmed }
        : { page: 0, size: PAGE_SIZE },
    );
    setSearchParams(sp, { replace: false });
  };

  /**
   * 검색 필터 초기화 — 입력값과 URL 을 함께 되돌린다(사양 SCREEN-030).
   * 입력만 비우면 URL 의 keyword 가 남아 조회 조건은 그대로인 어긋난 화면이 된다.
   */
  const handleResetSearch = () => {
    setKeywordInput('');
    setFieldInput(NoticeSearchField.ALL);
    setSearchParams(
      noticeListParamsToSearchParams({ page: 0, size: PAGE_SIZE }),
      { replace: false },
    );
  };

  /** 필터가 하나라도 활성인가 — 초기화 버튼은 그때만 노출한다(공용 필터바 컨벤션). */
  const isFilterActive =
    !!params.keyword || !!keywordInput || fieldInput !== NoticeSearchField.ALL;

  const handlePageChange = (next: number) => {
    const sp = noticeListParamsToSearchParams({ ...params, page: next });
    setSearchParams(sp, { replace: false });
  };

  return (
    <div className="space-y-4">
      {/* Header */}
      <div className="flex items-center justify-between">
        <div className="flex items-center gap-3">
          <h1 className="text-title-lg font-bold text-gray-900">게시판</h1>
          <span className="text-caption text-gray-600">공지사항</span>
        </div>
        {/* 작성은 모달이 아니라 전용 화면으로 이동한다(사양 SCREEN-030 헤더 note).
            모달이면 작성 화면에 직접 진입할 URL 이 없어 북마크·공유·뒤로가기가 성립하지 않는다. */}
        {isReviewer && (
          <Button
            variant="primary"
            size="sm"
            leftIcon={Plus}
            onClick={() => navigate('/notice/new')}
          >
            새 공지 작성
          </Button>
        )}
      </div>

      {/* Search */}
      <form
        className="flex flex-wrap items-center gap-2 rounded-lg border border-gray-200 bg-white p-3 shadow-sm"
        onSubmit={(e) => {
          e.preventDefault();
          applySearch();
        }}
      >
        <label htmlFor="notice-search-field" className="sr-only">
          검색 필드
        </label>
        <select
          id="notice-search-field"
          value={fieldInput}
          onChange={(e) =>
            setFieldInput(toNoticeSearchField(e.target.value) ?? NoticeSearchField.ALL)
          }
          className={`text-body-md border border-gray-300 rounded-lg px-3 py-2 bg-white ${KRDS_FOCUS}`}
        >
          {FIELD_OPTIONS.map((o) => (
            <option key={o.value} value={o.value}>
              {o.label}
            </option>
          ))}
        </select>
        <label htmlFor="notice-search-keyword" className="sr-only">
          검색어
        </label>
        <input
          id="notice-search-keyword"
          type="text"
          value={keywordInput}
          onChange={(e) => setKeywordInput(e.target.value)}
          placeholder="검색어를 입력하세요"
          maxLength={100}
          className={`flex-1 min-w-[180px] text-body-md border border-gray-300 rounded-lg px-3 py-2 ${KRDS_FOCUS}`}
        />
        <Button type="submit" variant="secondary" size="sm" leftIcon={Search}>
          검색
        </Button>
        {isFilterActive && (
          <Button
            type="button"
            variant="outline"
            size="sm"
            leftIcon={RotateCcw}
            onClick={handleResetSearch}
          >
            초기화
          </Button>
        )}
      </form>

      {error && <ErrorState title="공지 목록을 불러올 수 없습니다" />}

      {/* Table */}
      <div className="overflow-hidden rounded-lg border border-gray-200 bg-white shadow-sm">
        <div className="overflow-x-auto">
          <table className="w-full text-body-md">
            <thead>
              {/* ★번호(순번) 컬럼은 두지 않는다 — 제목/상태/등록일 3열 구성이다(사양 SCREEN-030).
                  구 구현은 이 자리에 순번도 아닌 DB PK(n.id)를 그대로 노출하고 있었다(내부 식별자 유출). */}
              {/* 헤더 배경은 secondary 스케일 최옅단(DS-001 do_rules) — 페이지 배경과 같은
                  회색을 쓰면 열 구조가 먼저 읽히지 않는다. */}
              <tr className="border-b border-gray-200 bg-secondary-50">
                <th className={TH_CLASS}>제목</th>
                {isReviewer && <th className={cn('w-24', TH_CLASS)}>상태</th>}
                <th className={cn('w-32', TH_CLASS)}>등록일</th>
              </tr>
            </thead>
            <tbody>
              {isLoading ? (
                Array.from({ length: 5 }).map((_, i) => (
                  <tr key={i} className="border-b border-gray-100">
                    {/* 스켈레톤 칸수는 헤더 칸수(REVIEWER 3 / WORKER 2)와 일치해야 한다. */}
                    {Array.from({ length: isReviewer ? 3 : 2 }).map((__, j) => (
                      <td key={j} className="px-4 py-3">
                        <Skeleton height={16} />
                      </td>
                    ))}
                  </tr>
                ))
              ) : notices.length === 0 ? (
                <tr>
                  <td colSpan={isReviewer ? 3 : 2} className="px-3 py-12">
                    <EmptyState message="등록된 공지가 없습니다" />
                  </td>
                </tr>
              ) : (
                notices.map((n) => (
                  <tr
                    key={n.id}
                    onClick={() => navigate(`/notice/${n.id}`)}
                    className={cn(
                      'cursor-pointer border-b border-gray-100 transition-colors hover:bg-rowHover',
                      // KRDS 예외: 고정 pinned amber 는 강조 accent(상태 아님) — 토큰 획일화 제외(의도적 유지).
                      n.pinned && 'bg-amber-50/40',
                    )}
                  >
                    <td className="px-4 py-3">
                      <div className="flex items-center gap-2">
                        {n.pinned && (
                          <span
                            className="inline-flex items-center gap-1 rounded bg-amber-100 px-1.5 py-0.5 text-label font-semibold text-amber-700"
                            aria-label="상단 고정"
                          >
                            <Pin size={11} aria-hidden />
                            고정
                          </span>
                        )}
                        <button
                          type="button"
                          onClick={(e) => {
                            e.stopPropagation();
                            navigate(`/notice/${n.id}`);
                          }}
                          className="truncate max-w-[480px] text-left text-body-md font-medium text-gray-800 hover:text-primary-700 hover:underline"
                        >
                          {n.title}
                        </button>
                      </div>
                    </td>
                    {isReviewer && (
                      <td className="px-4 py-3">
                        <span
                          className={cn(
                            'inline-flex items-center rounded-full px-2 py-0.5 text-label font-semibold',
                            n.pubStatus === NoticePubStatus.PUBLISHED
                              ? 'bg-success/10 text-success-700'
                              : 'bg-gray-100 text-gray-600',
                          )}
                        >
                          {n.pubStatus === NoticePubStatus.PUBLISHED
                            ? '발행'
                            : '작성중'}
                        </span>
                      </td>
                    )}
                    <td className="px-4 py-3 text-body-md text-gray-600">
                      {formatDate(n.regDt)}
                    </td>
                  </tr>
                ))
              )}
            </tbody>
          </table>
        </div>
      </div>

      {/* Pagination */}
      {totalElements > 0 && (
        <Pagination
          page={page}
          totalPages={data?.totalPages ?? 0}
          onChange={handlePageChange}
        />
      )}

    </div>
  );
}
