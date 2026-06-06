// SCR-TASK-002 배정 전용 페이지 — REVIEWER 전용.
//
// TaskBoard API (status=UNASSIGNED 필터)로 미배정 영상 목록을 표시하고
// 단건/일괄 배정 기능을 제공한다.
//
// 레이아웃: 미배정 목록 테이블 + 체크박스 + 단건/일괄 배정 모달.
//
// 보안:
// - 라우터 RoleGuard 에서 REVIEWER 인가 검증 (router/index.tsx).
// - 작업자/검수자 select 는 AssignModal 내부에서 BE @PreAuthorize 보호 API 호출.
// - 입력값은 숫자 ID 만 전달 — SQL Injection / XSS 위험 없음 (axios URL 인코딩).

import { useCallback, useMemo, useState } from 'react';

import { Button } from '@/components/common/Button';
import { ErrorState } from '@/components/common/ErrorState';
import { Spinner } from '@/components/common/Spinner';
import { AssignModal } from '@/features/task/components/AssignModal';
import { useTaskBoard } from '@/features/task/hooks/useTaskBoard';
import type { TaskBoardItem } from '@/features/task/types';

// 배치 상태(batchStatus) 별 뱃지 색상. 미배정 목록은 상태 무관 영상을 포함하므로
// REGISTERED/PENDING/FAILED/COMPLETED 등을 시각적으로 구분 표시한다.
const BADGE_BASE =
  'inline-flex items-center rounded-full px-2 py-0.5 text-xs font-medium';

function batchStatusBadgeClass(status: string | null): string {
  switch (status) {
    case 'COMPLETED':
      return `${BADGE_BASE} bg-green-100 text-green-700`;
    case 'FAILED':
      return `${BADGE_BASE} bg-red-100 text-red-700`;
    case 'PENDING':
    case 'REGISTERED':
      return `${BADGE_BASE} bg-amber-100 text-amber-700`;
    default:
      return `${BADGE_BASE} bg-gray-100 text-gray-600`;
  }
}

/**
 * SCR-TASK-002 배정 전용 페이지.
 */
export function TaskAssignPage() {
  const [page, setPage] = useState(0);
  const { data, isLoading, isError, refetch } = useTaskBoard(
    { status: 'UNASSIGNED', page, size: 20 },
    { enabled: true },
  );

  const items: TaskBoardItem[] = data?.content ?? [];
  const totalPages = data?.totalPages ?? 0;

  // 체크박스 선택 상태
  const [selected, setSelected] = useState<Set<number>>(new Set());

  const toggleRow = useCallback((videoId: number) => {
    setSelected((prev) => {
      const next = new Set(prev);
      if (next.has(videoId)) {
        next.delete(videoId);
      } else {
        next.add(videoId);
      }
      return next;
    });
  }, []);

  const toggleAll = useCallback(() => {
    setSelected((prev) => {
      if (prev.size === items.length && items.length > 0) {
        return new Set();
      }
      return new Set(items.map((i) => i.videoId));
    });
  }, [items]);

  const allChecked = items.length > 0 && selected.size === items.length;

  // 단건 배정 모달
  const [assignModalOpen, setAssignModalOpen] = useState(false);
  const [assignTarget, setAssignTarget] = useState<{
    videoId: number;
    videoName: string;
  } | null>(null);

  // 일괄 배정 모달
  const [bulkModalOpen, setBulkModalOpen] = useState(false);

  const videoNameById = useMemo(() => {
    const map: Record<number, string> = {};
    for (const item of items) {
      map[item.videoId] = item.cctvName ?? `#${item.videoId}`;
    }
    return map;
  }, [items]);

  const handleSingleAssign = useCallback(
    (item: TaskBoardItem) => {
      setAssignTarget({
        videoId: item.videoId,
        videoName: item.cctvName ?? `#${item.videoId}`,
      });
      setAssignModalOpen(true);
    },
    [],
  );

  const handleBulkAssign = useCallback(() => {
    if (selected.size === 0) return;
    setBulkModalOpen(true);
  }, [selected.size]);

  const handleAssignSuccess = useCallback(() => {
    setAssignTarget(null);
    setSelected(new Set());
  }, []);

  const handleBulkSuccess = useCallback(() => {
    setSelected(new Set());
  }, []);

  if (isLoading) {
    return (
      <div className="flex items-center justify-center py-20" data-testid="assign-page-loading">
        <Spinner label="미배정 영상 로딩" />
      </div>
    );
  }

  if (isError) {
    return (
      <div data-testid="assign-page-error">
        <ErrorState
          title="목록을 불러오지 못했습니다"
          message="미배정 영상 목록을 불러오는 중 오류가 발생했습니다."
          onRetry={() => refetch()}
          retryLabel="재시도"
        />
      </div>
    );
  }

  return (
    <div className="space-y-4" data-testid="task-assign-page">
      <div className="flex items-center justify-between">
        <h1 className="text-lg font-semibold text-gray-900">작업 배정</h1>
        {selected.size > 0 && (
          <Button
            variant="primary"
            size="sm"
            onClick={handleBulkAssign}
            aria-label={`${selected.size}건 일괄 배정`}
          >
            {selected.size}건 일괄 배정
          </Button>
        )}
      </div>

      {items.length === 0 ? (
        <div
          className="rounded-lg border border-dashed border-gray-300 bg-gray-50 px-6 py-12 text-center"
          data-testid="assign-page-empty"
        >
          <p className="text-sm text-gray-500">미배정 영상이 없습니다</p>
          <p className="mt-1 text-xs text-gray-400">
            모든 영상에 작업자가 배정되어 있습니다.
          </p>
        </div>
      ) : (
        <>
          <div className="overflow-hidden rounded-lg border border-gray-200 bg-white shadow-sm">
            <table className="w-full text-left text-sm">
              <thead className="border-b border-gray-200 bg-gray-50">
                <tr>
                  <th className="px-4 py-3 w-10">
                    <input
                      type="checkbox"
                      checked={allChecked}
                      onChange={toggleAll}
                      aria-label="전체 선택"
                      className="h-4 w-4 rounded border-gray-300 text-primary-600 focus:ring-primary-500"
                    />
                  </th>
                  <th className="px-4 py-3 text-xs font-medium uppercase tracking-wide text-gray-500">
                    영상명
                  </th>
                  <th className="px-4 py-3 text-xs font-medium uppercase tracking-wide text-gray-500">
                    상태
                  </th>
                  <th className="px-4 py-3 text-xs font-medium uppercase tracking-wide text-gray-500">
                    이벤트
                  </th>
                  <th className="px-4 py-3 text-xs font-medium uppercase tracking-wide text-gray-500">
                    프레임 수
                  </th>
                  <th className="px-4 py-3 text-xs font-medium uppercase tracking-wide text-gray-500">
                    촬영일시
                  </th>
                  <th className="px-4 py-3 text-xs font-medium uppercase tracking-wide text-gray-500">
                    액션
                  </th>
                </tr>
              </thead>
              <tbody>
                {items.map((item) => (
                  <tr
                    key={item.videoId}
                    className="border-b border-gray-100 transition-colors hover:bg-gray-50"
                    data-testid={`assign-row-${item.videoId}`}
                  >
                    <td className="px-4 py-3">
                      <input
                        type="checkbox"
                        checked={selected.has(item.videoId)}
                        onChange={() => toggleRow(item.videoId)}
                        aria-label={`${item.cctvName ?? item.videoId} 선택`}
                        className="h-4 w-4 rounded border-gray-300 text-primary-600 focus:ring-primary-500"
                      />
                    </td>
                    <td className="px-4 py-3">
                      <span className="font-medium text-gray-800">
                        {item.cctvName ?? `#${item.videoId}`}
                      </span>
                    </td>
                    <td className="px-4 py-3">
                      <span
                        data-testid={`assign-status-${item.videoId}`}
                        className={batchStatusBadgeClass(item.batchStatus)}
                      >
                        {item.batchStatus ?? '-'}
                      </span>
                    </td>
                    <td className="px-4 py-3 text-gray-600">
                      {item.eventName ?? <span className="text-gray-400">-</span>}
                    </td>
                    <td className="px-4 py-3 text-gray-600">{item.frameCount}</td>
                    <td className="px-4 py-3 text-gray-600">
                      {item.capturedAt
                        ? new Date(item.capturedAt).toLocaleDateString('ko-KR')
                        : '-'}
                    </td>
                    <td className="px-4 py-3">
                      <Button
                        variant="ghost"
                        size="sm"
                        onClick={() => handleSingleAssign(item)}
                        aria-label={`${item.cctvName ?? item.videoId} 배정`}
                      >
                        배정
                      </Button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>

          {/* 페이지네이션 */}
          {totalPages > 1 && (
            <div className="flex items-center justify-center gap-2">
              <Button
                variant="outline"
                size="sm"
                disabled={page === 0}
                onClick={() => setPage((p) => Math.max(0, p - 1))}
              >
                이전
              </Button>
              <span className="text-sm text-gray-600">
                {page + 1} / {totalPages}
              </span>
              <Button
                variant="outline"
                size="sm"
                disabled={page >= totalPages - 1}
                onClick={() => setPage((p) => p + 1)}
              >
                다음
              </Button>
            </div>
          )}
        </>
      )}

      {/* 단건 배정 모달 */}
      <AssignModal
        open={assignModalOpen}
        onClose={() => {
          setAssignModalOpen(false);
          setAssignTarget(null);
        }}
        task={null}
        mode="assign"
        videoId={assignTarget?.videoId}
        videoName={assignTarget?.videoName}
        onSuccess={handleAssignSuccess}
      />

      {/* 일괄 배정 모달 */}
      <AssignModal
        open={bulkModalOpen}
        onClose={() => setBulkModalOpen(false)}
        task={null}
        mode="bulk"
        videoIds={[...selected]}
        videoNameById={videoNameById}
        onBulkSuccess={handleBulkSuccess}
      />
    </div>
  );
}
