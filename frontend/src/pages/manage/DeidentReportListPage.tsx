import { useState } from 'react';

import { Button } from '@/components/common/Button';
import { EmptyState } from '@/components/common/EmptyState';
import { ErrorState } from '@/components/common/ErrorState';
import { PageHeader } from '@/components/common/PageHeader';
import { Skeleton } from '@/components/common/Skeleton';
import {
  useDeidentReports,
  useResolveDeidentReport,
} from '@/features/deident/hooks/useDeidentReports';
import {
  DeidentReportStatus,
  type DeidentReportStatus as Status,
} from '@/features/deident/reportTypes';
import { resolveDisplayName } from '@/lib/displayName';
import { useUiStore } from '@/stores/useUiStore';

const STATUS_TABS: { value: Status; label: string }[] = [
  { value: DeidentReportStatus.OPEN, label: '미해소(OPEN)' },
  { value: DeidentReportStatus.RESOLVED, label: '해소됨(RESOLVED)' },
];

const PAGE_SIZE = 20;

/**
 * SCR-MANAGE-DEIDENT — 비식별 신고 관리 (REVIEWER 전용, `/manage/deident-reports`).
 *
 * 라벨링/마킹 중 작업자가 비식별 누락을 신고하면 영상이 잠기고 라벨이 삭제된다.
 * REVIEWER 는 본 화면에서 OPEN 신고를 확인하고, 외부 솔루션으로 수동 비식별화를 완료한 뒤
 * "해소 처리" 로 잠금을 해제한다(POST /v1/deident-reports/{rprtSn}/resolve).
 *
 * 보안:
 * - REVIEWER 역할 검증은 라우터 RoleGuard + BE @PreAuthorize 이중.
 * - 신고 사유(reason)는 사용자 입력 — React 가 자동 escape 하여 텍스트로만 렌더(XSS 방어).
 */
export function DeidentReportListPage() {
  const pushToast = useUiStore((s) => s.pushToast);
  const [status, setStatus] = useState<Status>(DeidentReportStatus.OPEN);
  const [page, setPage] = useState(0);

  const { data, isLoading, error } = useDeidentReports({
    status,
    page,
    size: PAGE_SIZE,
  });

  const { mutate: resolve, isPending } = useResolveDeidentReport({
    onSuccess: () => pushToast({ variant: 'success', message: '신고가 해소되었습니다.' }),
    onError: () => pushToast({ variant: 'error', message: '해소 처리에 실패했습니다.' }),
  });

  const rows = data?.content ?? [];
  const totalElements = data?.totalElements ?? 0;
  const totalPages = Math.max(1, data?.totalPages ?? 1);
  const currentPage = data?.number ?? page;

  const changeStatus = (next: Status) => {
    setStatus(next);
    setPage(0);
  };

  return (
    <section className="flex flex-col gap-4" data-testid="deident-report-page">
      <PageHeader
        title="비식별 신고 관리"
        description="라벨링·마킹 중 신고된 비식별 누락 건을 확인하고 외부 수동 비식별화 완료 후 해소 처리합니다."
      />

      <div className="flex gap-2" role="tablist" aria-label="신고 상태 필터">
        {STATUS_TABS.map((tab) => (
          <button
            key={tab.value}
            type="button"
            role="tab"
            aria-selected={status === tab.value}
            data-testid={`deident-status-tab-${tab.value}`}
            onClick={() => changeStatus(tab.value)}
            className={
              status === tab.value
                ? 'rounded-md border border-primary-500 bg-primary-50 px-3 py-1.5 text-sm font-medium text-primary-700'
                : 'rounded-md border border-gray-200 bg-white px-3 py-1.5 text-sm text-gray-600 hover:bg-gray-50'
            }
          >
            {tab.label}
          </button>
        ))}
        <span className="ml-auto inline-flex items-center rounded-full bg-gray-100 px-2 py-0.5 text-xs font-medium text-gray-600">
          {totalElements}건
        </span>
      </div>

      {isLoading && (
        <div className="space-y-2">
          {Array.from({ length: 5 }).map((_, i) => (
            <Skeleton key={i} height={40} />
          ))}
        </div>
      )}

      {error && <ErrorState title="신고 목록을 불러올 수 없습니다" />}

      {data && rows.length === 0 && (
        <EmptyState
          message={
            status === DeidentReportStatus.OPEN
              ? '미해소 신고가 없습니다.'
              : '해소된 신고가 없습니다.'
          }
        />
      )}

      {data && rows.length > 0 && (
        <div className="overflow-hidden rounded-lg border border-gray-200 bg-white shadow-sm">
          <table className="w-full text-sm" data-testid="deident-report-table">
            <thead className="bg-gray-50">
              <tr>
                <th className="px-3 py-2 text-left text-xs font-medium text-gray-600">신고 번호</th>
                <th className="px-3 py-2 text-left text-xs font-medium text-gray-600">영상</th>
                <th className="px-3 py-2 text-left text-xs font-medium text-gray-600">신고자</th>
                <th className="px-3 py-2 text-left text-xs font-medium text-gray-600">사유</th>
                <th className="px-3 py-2 text-left text-xs font-medium text-gray-600">신고일시</th>
                <th className="px-3 py-2 text-left text-xs font-medium text-gray-600">처리</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((r) => (
                <tr
                  key={r.rprtSn}
                  data-testid={`deident-report-row-${r.rprtSn}`}
                  className="border-b border-gray-100"
                >
                  <td className="px-3 py-2 font-mono text-xs text-gray-500">#{r.rprtSn}</td>
                  <td className="px-3 py-2 font-mono text-xs text-gray-700">영상 #{r.rawSn}</td>
                  {/* 신고자 — 표시명 우선, 없으면 원값(reporterNo) 폴백. 둘 다 없으면 '-'. */}
                  <td
                    className="px-3 py-2 text-xs text-gray-600"
                    data-testid={`deident-reporter-${r.rprtSn}`}
                  >
                    {resolveDisplayName(r.reporterName, r.reporterNo) ?? '-'}
                  </td>
                  <td className="max-w-[280px] truncate px-3 py-2 text-xs text-gray-700" title={r.reason}>
                    {r.reason}
                  </td>
                  <td className="px-3 py-2 text-xs text-gray-500">
                    {new Date(r.reportDt).toLocaleString('ko-KR')}
                  </td>
                  <td className="px-3 py-2">
                    {r.status === DeidentReportStatus.OPEN ? (
                      <Button
                        size="sm"
                        variant="primary"
                        data-testid={`deident-resolve-${r.rprtSn}`}
                        disabled={isPending}
                        onClick={() => resolve(r.rprtSn)}
                      >
                        해소 처리
                      </Button>
                    ) : (
                      <span className="text-xs text-gray-400">
                        {r.resolvedDt
                          ? `해소 ${new Date(r.resolvedDt).toLocaleDateString('ko-KR')}`
                          : '해소됨'}
                      </span>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
          {totalPages > 1 && (
            <div className="flex items-center justify-between bg-gray-50 px-3 py-2 text-xs text-gray-500">
              <span>
                전체 {totalElements}건 ({currentPage + 1}/{totalPages} 페이지)
              </span>
              <div className="flex gap-1">
                <button
                  type="button"
                  onClick={() => setPage(Math.max(0, currentPage - 1))}
                  disabled={currentPage === 0}
                  className="rounded px-2 py-1 hover:bg-gray-200 disabled:cursor-not-allowed disabled:opacity-40"
                >
                  이전
                </button>
                <button
                  type="button"
                  onClick={() => setPage(currentPage + 1)}
                  disabled={currentPage >= totalPages - 1}
                  className="rounded px-2 py-1 hover:bg-gray-200 disabled:cursor-not-allowed disabled:opacity-40"
                >
                  다음
                </button>
              </div>
            </div>
          )}
        </div>
      )}
    </section>
  );
}
