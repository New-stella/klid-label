// 화면ID: KLID-AT-SC-030 — 게시판(공지) 목록
import { useMemo, useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { Pin, Plus, Search } from 'lucide-react';

import { Button } from '@/components/common/Button';
import { EmptyState } from '@/components/common/EmptyState';
import { ErrorState } from '@/components/common/ErrorState';
import { Pagination } from '@/components/common/Pagination';
import { Skeleton } from '@/components/common/Skeleton';
import { NoticeEditModal } from '@/features/notice/components/NoticeEditModal';
import { useNoticeActions } from '@/features/notice/hooks/useNoticeActions';
import { useNotices } from '@/features/notice/hooks/useNotices';
import { KRDS_FOCUS } from '@/lib/focusRing';
import {
  noticeListParamsToSearchParams,
  parseNoticeListParams,
  toNoticeSearchField,
} from '@/features/notice/parseNoticeListParams';
import {
  NoticePubStatus,
  NoticeSearchField,
  type NoticeForm,
} from '@/features/notice/types';
import { cn } from '@/lib/cn';
import { Role } from '@/lib/api/types';
import { useAuthStore } from '@/stores/useAuthStore';

const PAGE_SIZE = 20;

const FIELD_OPTIONS: { value: NoticeSearchField; label: string }[] = [
  { value: NoticeSearchField.ALL, label: '제목+내용' },
  { value: NoticeSearchField.TITLE, label: '제목' },
  { value: NoticeSearchField.CONTENT, label: '내용' },
];

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

  const [createOpen, setCreateOpen] = useState(false);

  const { data, isLoading, error } = useNotices(params);
  const { create } = useNoticeActions();

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

  const handlePageChange = (next: number) => {
    const sp = noticeListParamsToSearchParams({ ...params, page: next });
    setSearchParams(sp, { replace: false });
  };

  const handleCreate = (form: NoticeForm) => {
    create.mutate(form, {
      onSuccess: (created) => {
        setCreateOpen(false);
        navigate(`/notice/${created.id}`);
      },
    });
  };

  return (
    <div className="space-y-4">
      {/* Header */}
      <div className="flex items-center justify-between">
        <div className="flex items-center gap-3">
          <h1 className="text-xl font-bold text-gray-900">게시판</h1>
          <span className="text-xs text-gray-500">공지사항</span>
        </div>
        {isReviewer && (
          <Button
            variant="primary"
            size="sm"
            leftIcon={Plus}
            onClick={() => setCreateOpen(true)}
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
          className={`text-sm border border-gray-300 rounded-lg px-3 py-2 bg-white ${KRDS_FOCUS}`}
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
          className={`flex-1 min-w-[180px] text-sm border border-gray-300 rounded-lg px-3 py-2 ${KRDS_FOCUS}`}
        />
        <Button type="submit" variant="secondary" size="sm" leftIcon={Search}>
          검색
        </Button>
      </form>

      {error && <ErrorState title="공지 목록을 불러올 수 없습니다" />}

      {/* Table */}
      <div className="overflow-hidden rounded-lg border border-gray-200 bg-white shadow-sm">
        <div className="overflow-x-auto">
          <table className="w-full text-sm">
            <thead>
              <tr className="border-b border-gray-200 bg-gray-50">
                <th className="w-16 px-4 py-3 text-left text-xs font-semibold uppercase tracking-wide text-gray-500">
                  번호
                </th>
                <th className="px-4 py-3 text-left text-xs font-semibold uppercase tracking-wide text-gray-500">
                  제목
                </th>
                {isReviewer && (
                  <th className="w-24 px-4 py-3 text-left text-xs font-semibold uppercase tracking-wide text-gray-500">
                    상태
                  </th>
                )}
                <th className="w-32 px-4 py-3 text-left text-xs font-semibold uppercase tracking-wide text-gray-500">
                  등록일
                </th>
              </tr>
            </thead>
            <tbody>
              {isLoading ? (
                Array.from({ length: 5 }).map((_, i) => (
                  <tr key={i} className="border-b border-gray-100">
                    {Array.from({ length: isReviewer ? 4 : 3 }).map((__, j) => (
                      <td key={j} className="px-4 py-3">
                        <Skeleton height={16} />
                      </td>
                    ))}
                  </tr>
                ))
              ) : notices.length === 0 ? (
                <tr>
                  <td colSpan={isReviewer ? 4 : 3} className="px-3 py-12">
                    <EmptyState message="등록된 공지가 없습니다" />
                  </td>
                </tr>
              ) : (
                notices.map((n) => (
                  <tr
                    key={n.id}
                    onClick={() => navigate(`/notice/${n.id}`)}
                    className={cn(
                      'cursor-pointer border-b border-gray-100 transition-colors hover:bg-gray-50',
                      // KRDS 예외: 고정 pinned amber 는 강조 accent(상태 아님) — 토큰 획일화 제외(의도적 유지).
                      n.pinned && 'bg-amber-50/40',
                    )}
                  >
                    <td className="px-4 py-3 text-xs text-gray-400">{n.id}</td>
                    <td className="px-4 py-3">
                      <div className="flex items-center gap-2">
                        {n.pinned && (
                          <span
                            className="inline-flex items-center gap-1 rounded bg-amber-100 px-1.5 py-0.5 text-xs font-semibold text-amber-700"
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
                          className="truncate max-w-[480px] text-left text-sm font-medium text-gray-800 hover:text-primary-700 hover:underline"
                        >
                          {n.title}
                        </button>
                      </div>
                    </td>
                    {isReviewer && (
                      <td className="px-4 py-3">
                        <span
                          className={cn(
                            'inline-flex items-center rounded-full px-2 py-0.5 text-xs font-semibold',
                            n.pubStatus === NoticePubStatus.PUBLISHED
                              ? 'bg-success/10 text-success'
                              : 'bg-gray-100 text-gray-600',
                          )}
                        >
                          {n.pubStatus === NoticePubStatus.PUBLISHED
                            ? '발행'
                            : '작성중'}
                        </span>
                      </td>
                    )}
                    <td className="px-4 py-3 text-sm text-gray-500">
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
          size={PAGE_SIZE}
          totalElements={totalElements}
          onPageChange={handlePageChange}
        />
      )}

      {/* Create Modal (REVIEWER) */}
      {isReviewer && (
        <NoticeEditModal
          open={createOpen}
          onClose={() => setCreateOpen(false)}
          onSubmit={handleCreate}
          submitting={create.isPending}
        />
      )}
    </div>
  );
}
