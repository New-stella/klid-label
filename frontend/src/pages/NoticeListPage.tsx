// 화면ID: KLID-AT-SC-030 — 게시판(공지) 목록
import { useMemo, useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { ChevronDown, ChevronRight, Plus, RotateCcw, Search } from 'lucide-react';

import { Badge } from '@/components/common/Badge';
import { Button } from '@/components/common/Button';
import { EmptyState } from '@/components/common/EmptyState';
import { ErrorState } from '@/components/common/ErrorState';
import { PageHeader } from '@/components/common/PageHeader';
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

/** 필터 라벨 — 디자인 토큰 label(14/600) + gray-700(필터 표면 위 AAA). */
const FILTER_LABEL_CLASS = 'text-label text-gray-700';
/**
 * 폼 컨트롤 본체 — 높이 44px(KRDS 터치 타깃) + `rounded-md`(폼 컨트롤 5종 공통 반경).
 * placeholder 는 gray-400 이 아니라 **gray-600** 이다(400 은 흰 배경에서 3.08:1 로 AA 미달).
 */
const FILTER_CONTROL_CLASS =
  'h-11 w-full rounded-md border border-gray-300 bg-white text-body-md text-gray-900 transition-colors hover:border-gray-400';
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
    <div className="flex flex-col gap-6">
      {/* ① 페이지 헤더 — 제목 + 부제 2단(사양 SCREEN-030). '게시판 / 공지사항' 단일 문자열이 아니다. */}
      <PageHeader
        title="게시판"
        description="공지사항을 확인합니다."
        /* 작성은 모달이 아니라 전용 화면으로 이동한다(사양 SCREEN-030 헤더 note).
           모달이면 작성 화면에 직접 진입할 URL 이 없어 북마크·공유·뒤로가기가 성립하지 않는다. */
        actions={
          isReviewer ? (
            <Button
              variant="primary"
              size="md"
              leftIcon={Plus}
              onClick={() => navigate('/notice/new')}
            >
              새 공지 작성
            </Button>
          ) : undefined
        }
      />

      {/* ② 검색 필터 — 라벨을 화면에 노출한다(sr-only 아님). 페이지 배경이 gray-50 이라
          필터 표면은 흰색으로 둔다(디자인의 옅은 회색 표면은 흰 캔버스를 전제한 값이다). */}
      <form
        className="flex flex-wrap items-end gap-3 rounded-lg border border-gray-200 bg-white p-4 shadow-sm"
        onSubmit={(e) => {
          e.preventDefault();
          applySearch();
        }}
      >
        <div className="flex min-w-[150px] basis-[180px] flex-col gap-1">
          <label htmlFor="notice-search-field" className={FILTER_LABEL_CLASS}>
            검색 필드
          </label>
          {/* 화살표는 배경 이미지(data URI) 대신 아이콘으로 얹는다 — data URI 안의 stroke 색은
              토큰을 경유할 수 없어 raw hex 가 된다. 네이티브 select 동작은 그대로 유지. */}
          <div className="relative">
            <select
              id="notice-search-field"
              value={fieldInput}
              onChange={(e) =>
                setFieldInput(toNoticeSearchField(e.target.value) ?? NoticeSearchField.ALL)
              }
              className={cn(FILTER_CONTROL_CLASS, 'appearance-none pl-3 pr-9', KRDS_FOCUS)}
            >
              {FIELD_OPTIONS.map((o) => (
                <option key={o.value} value={o.value}>
                  {o.label}
                </option>
              ))}
            </select>
            <ChevronDown
              className="pointer-events-none absolute right-3 top-1/2 h-4 w-4 -translate-y-1/2 text-gray-600"
              aria-hidden
            />
          </div>
        </div>
        <div className="flex min-w-[220px] flex-1 flex-col gap-1">
          <label htmlFor="notice-search-keyword" className={FILTER_LABEL_CLASS}>
            검색어
          </label>
          {/* @design SCREEN-030 — placeholder 는 **마침표까지** 사양 문구 그대로다. */}
          <input
            id="notice-search-keyword"
            type="text"
            value={keywordInput}
            onChange={(e) => setKeywordInput(e.target.value)}
            placeholder="검색어를 입력하세요."
            maxLength={100}
            className={cn(FILTER_CONTROL_CLASS, 'px-3 placeholder:text-gray-600', KRDS_FOCUS)}
          />
        </div>
        <div className="flex items-center gap-2">
          <Button type="submit" variant="secondary" size="md" leftIcon={Search}>
            검색
          </Button>
          {isFilterActive && (
            <Button
              type="button"
              variant="outline"
              size="md"
              leftIcon={RotateCcw}
              onClick={handleResetSearch}
            >
              초기화
            </Button>
          )}
        </div>
      </form>

      {error && <ErrorState title="공지 목록을 불러올 수 없습니다" />}

      {/* ③ 게시글 테이블 — 카드 표면 + 스크롤 영역(헤더 고정) + 하단 페이지네이션 */}
      <section className="flex flex-col gap-3 rounded-lg border border-gray-200 bg-white p-4 shadow-sm">
        <h2 className="text-title-sm text-gray-950">공지 목록</h2>

        <div className="max-h-[60vh] overflow-auto rounded-md border border-gray-100">
          <table className="w-full text-body-md">
            <thead className="sticky top-0 z-10 bg-secondary-50">
              {/* ★번호(순번) 컬럼은 두지 않는다 — 제목/상태/등록일 3열 구성이다(사양 SCREEN-030).
                  구 구현은 이 자리에 순번도 아닌 DB PK(n.id)를 그대로 노출하고 있었다(내부 식별자 유출). */}
              <tr className="border-b border-gray-200">
                <th scope="col" className={TH_CLASS}>
                  제목
                </th>
                {isReviewer && (
                  <th scope="col" className={cn(TH_CLASS, 'w-24')}>
                    상태
                  </th>
                )}
                {/* 정렬 축은 서버가 고정한다(고정글 우선 + 등록일 내림차순) — 누를 수 있는
                    정렬 토글이 아니라 현재 정렬 방향을 알리는 표시다. */}
                <th scope="col" aria-sort="descending" className={cn(TH_CLASS, 'w-36')}>
                  <span className="inline-flex items-center gap-1">
                    등록일
                    <ChevronDown className="h-3 w-3" aria-hidden />
                  </span>
                </th>
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
                  <td colSpan={isReviewer ? 3 : 2} className="px-3 py-4">
                    <EmptyState message="등록된 공지가 없습니다" />
                  </td>
                </tr>
              ) : (
                notices.map((n) => (
                  <tr
                    key={n.id}
                    onClick={() => navigate(`/notice/${n.id}`)}
                    className={cn(
                      'group cursor-pointer border-b border-gray-100 transition-colors last:border-b-0',
                      // 행 hover 표면은 전용 토큰 하나로 통일한다(DS-001 do_rules) —
                      // 회색 계열은 카드 표면과 겹쳐 짚은 행이 구분되지 않는다.
                      'hover:bg-rowHover',
                      // KRDS 예외: 고정 pinned amber 는 강조 accent(상태 아님) — 토큰 획일화 제외(의도적 유지).
                      n.pinned && 'bg-amber-50/40',
                    )}
                  >
                    <td className="px-4 py-3">
                      <div className="flex min-w-0 items-center gap-2">
                        {/* 고정 표시는 색만으로 구분하지 않는다 — Pin 아이콘 + 텍스트 병기(UI-111). */}
                        {n.pinned && <Badge variant="pinned" label="고정" />}
                        <button
                          type="button"
                          title={n.title}
                          onClick={(e) => {
                            e.stopPropagation();
                            navigate(`/notice/${n.id}`);
                          }}
                          className={cn(
                            'max-w-[480px] truncate rounded-sm text-left text-body-md text-gray-900 transition-colors',
                            'group-hover:text-primary-700 hover:underline',
                            KRDS_FOCUS,
                          )}
                        >
                          {n.title}
                        </button>
                      </div>
                    </td>
                    {isReviewer && (
                      <td className="px-4 py-3">
                        {/* 발행=success / 작성중=neutral (UI-111). 공지 상세와 같은 배지를 쓴다 —
                            화면마다 pill 을 따로 그리면 색·형태가 갈린다. */}
                        <Badge
                          variant={
                            n.pubStatus === NoticePubStatus.PUBLISHED ? 'success' : 'neutral'
                          }
                          label={
                            n.pubStatus === NoticePubStatus.PUBLISHED ? '발행' : '작성중'
                          }
                        />
                      </td>
                    )}
                    <td className="whitespace-nowrap px-4 py-3 text-body-md text-gray-900">
                      <span className="inline-flex items-center gap-2">
                        {formatDate(n.regDt)}
                        {/* 행 전체가 상세로 가는 클릭 영역임을 알리는 장식 — idle 은 non-text
                            대비 3:1 을 넘는 gray-400(3.08:1), hover 는 primary-500. */}
                        <ChevronRight
                          className="h-4 w-4 shrink-0 text-gray-400 transition-colors group-hover:text-primary-500"
                          aria-hidden
                        />
                      </span>
                    </td>
                  </tr>
                ))
              )}
            </tbody>
          </table>
        </div>

        {/* Pagination */}
        {totalElements > 0 && (
          <Pagination
            page={page}
            totalPages={data?.totalPages ?? 0}
            onChange={handlePageChange}
          />
        )}
      </section>
    </div>
  );
}
