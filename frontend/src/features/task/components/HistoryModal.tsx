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
 * - BE 는 본체 REG_DT 기반 ASSIGN 이벤트를 항상 첫 행으로 합성해 반환하므로 정상 응답은 최소 1건이다.
 * - 빈 상태("이력이 없습니다")는 비정상/방어 케이스에서만 노출된다.
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
          ? `배정 #${assignmentId} 의 배정·재배정 이력을 시간순으로 표시합니다.`
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
            message="이력이 없습니다"
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
                    <span
                      className={`ml-2 inline-flex items-center rounded-full px-2 py-0.5 text-[10px] font-medium ${chgTypeChipClass(row.chgTypeCd)}`}
                    >
                      {chgTypeLabel(row.chgTypeCd)}
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
 * 변경 타입(chgTypeCd) → 한글 라벨 매핑.
 * 알 수 없는 값은 원본을 그대로 반환 (방어적 fallback).
 */
function chgTypeLabel(code: AssignmentHistory['chgTypeCd']): string {
  switch (code) {
    case 'ASSIGN':
      return '배정';
    case 'REASSIGN':
      return '재배정';
    default:
      return code;
  }
}

/**
 * 변경 타입에 따른 칩 배경/텍스트 색상(Tailwind) — 시각 명료성 확보.
 */
function chgTypeChipClass(code: AssignmentHistory['chgTypeCd']): string {
  switch (code) {
    case 'ASSIGN':
      return 'bg-green-50 text-green-700';
    case 'REASSIGN':
      return 'bg-blue-50 text-blue-700';
    default:
      return 'bg-gray-50 text-gray-700';
  }
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
