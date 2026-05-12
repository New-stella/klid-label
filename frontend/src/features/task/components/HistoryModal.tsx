import { useQuery } from '@tanstack/react-query';
import { History as HistoryIcon } from 'lucide-react';

import { Button } from '@/components/common/Button';
import { EmptyState } from '@/components/common/EmptyState';
import { Modal } from '@/components/common/Modal';
import { Skeleton } from '@/components/common/Skeleton';
import { ASSIGNMENT_KEYS } from '@/lib/queryKeys';

import { getAssignmentHistory } from '../api';
import type { AssignmentHistory } from '../types';

export interface HistoryModalProps {
  open: boolean;
  onClose: () => void;
  /** 조회 대상 배정 PK — null/undefined 면 query disabled */
  assignmentId: number | null;
}

/**
 * SCR-TASK-003 배정 이력 모달 — REVIEWER 전용 (BE @PreAuthorize 로 강제됨).
 *
 * - React Query 로 `/assignments/{id}/history` 조회 → 시간 오름차순 타임라인 표시
 * - 데이터: prev 작업자 → new 작업자 + 변경 시각 (현재 reason 컬럼은 BE 스키마에 없어 null 로 옴)
 * - 빈 상태: "이력이 없습니다 (재배정 기록 없음)"
 * - 보안: 모든 사용자 입력값(이름) 은 React 의 JSX 텍스트 보간으로 자동 이스케이프되며,
 *   `dangerouslySetInnerHTML` 을 사용하지 않는다.
 */
export function HistoryModal({ open, onClose, assignmentId }: HistoryModalProps) {
  const enabled = open && typeof assignmentId === 'number' && assignmentId > 0;
  const { data, isLoading, isError } = useQuery<AssignmentHistory[]>({
    queryKey: assignmentId
      ? ASSIGNMENT_KEYS.history(assignmentId)
      : ['assignments', 'history', 'disabled'],
    queryFn: () => getAssignmentHistory(assignmentId as number),
    enabled,
    staleTime: 30_000,
  });

  return (
    <Modal
      open={open}
      onClose={onClose}
      title="배정 이력"
      description={
        assignmentId
          ? `배정 #${assignmentId} 의 재배정 이력을 시간순으로 표시합니다.`
          : undefined
      }
      footer={
        <Button variant="outline" onClick={onClose}>
          닫기
        </Button>
      }
    >
      <div className="space-y-4">
        {isLoading ? (
          <div className="space-y-3" data-testid="history-loading">
            <Skeleton height={56} />
            <Skeleton height={56} />
            <Skeleton height={56} />
          </div>
        ) : isError ? (
          <p className="text-sm text-red-500">
            이력을 불러올 수 없습니다.
          </p>
        ) : !data || data.length === 0 ? (
          <EmptyState
            icon={<HistoryIcon size={28} aria-hidden />}
            message="이력이 없습니다 (재배정 기록 없음)"
          />
        ) : (
          <ol className="relative space-y-4 border-l border-gray-200 pl-4">
            {data.map((row) => (
              <li key={row.hstrySn} className="relative">
                <span
                  className="absolute -left-[21px] top-1.5 inline-flex h-3 w-3 rounded-full bg-primary-500 ring-2 ring-white"
                  aria-hidden
                />
                <div className="space-y-1">
                  <p className="text-xs font-semibold text-gray-500">
                    {formatDateTime(row.chgDt)}
                    <span className="ml-2 inline-flex items-center rounded-full bg-blue-50 px-2 py-0.5 text-[10px] font-medium text-blue-700">
                      {row.chgTypeCd}
                    </span>
                  </p>
                  <p className="text-sm text-gray-800">
                    <span className="font-medium">
                      {row.prevUserName ??
                        (row.prevUserNo != null
                          ? `user #${row.prevUserNo}`
                          : '미지정')}
                    </span>
                    <span className="mx-1.5 text-gray-400">→</span>
                    <span className="font-medium text-primary-700">
                      {row.newUserName ??
                        (row.newUserNo != null
                          ? `user #${row.newUserNo}`
                          : '미지정')}
                    </span>
                  </p>
                  {row.reason ? (
                    <p className="text-xs text-gray-500">사유: {row.reason}</p>
                  ) : null}
                </div>
              </li>
            ))}
          </ol>
        )}
      </div>
    </Modal>
  );
}

/**
 * ISO/LocalDateTime 문자열을 `YYYY-MM-DD HH:mm` 로 포매팅.
 * 잘못된 입력이면 원본을 그대로 반환 (예외 방어).
 */
function formatDateTime(s: string): string {
  if (!s) return '';
  try {
    const d = new Date(s);
    if (Number.isNaN(d.getTime())) return s;
    const pad = (n: number) => n.toString().padStart(2, '0');
    return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`;
  } catch {
    return s;
  }
}
