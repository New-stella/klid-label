import { useEffect, useRef } from 'react';
import { createPortal } from 'react-dom';
import { useQuery } from '@tanstack/react-query';
import { History as HistoryIcon, X } from 'lucide-react';

import { EmptyState } from '@/components/common/EmptyState';
import { Skeleton } from '@/components/common/Skeleton';
import { KRDS_FOCUS } from '@/lib/focusRing';
import { ASSIGNMENT_KEYS } from '@/lib/queryKeys';

import { getAssignmentHistory } from '../api';
import type { AssignmentHistory, TaskEventType } from '../types';

const FOCUSABLE_SELECTOR =
  'a[href], area[href], input:not([disabled]), select:not([disabled]), textarea:not([disabled]), button:not([disabled]), [tabindex]:not([tabindex="-1"])';

export interface HistoryDrawerProps {
  open: boolean;
  onClose: () => void;
  /** 조회 대상 배정 PK — null/undefined 면 query disabled. */
  assignmentId: number | null;
  /** 대상 작업의 영상명 — 헤더에 노출 (예: "CCTV-강남구-003 영상"). */
  videoName?: string;
}

/**
 * SCR-TASK-003 배정 이력 Drawer — REVIEWER 전용 (BE @PreAuthorize 로 강제됨).
 *
 * 우측 슬라이드 Drawer 로 표시되며 다음 정보를 타임라인 카드로 보여준다:
 * - 헤더: 시계 아이콘 + "배정 이력" 타이틀 + 닫기 X
 * - 대상 작업: "대상 작업" 라벨 + 영상명
 * - 타임라인: ASSIGN / REASSIGN / SUBMIT / CANCEL_SUBMIT / APPROVE / REJECT 워크플로 이벤트
 *   + PRIVACY_META_UPDATE / PRIVACY_META_RESET 감사 이벤트(개인정보 선언 변경·초기화)
 *
 * 데이터:
 * - React Query 로 `/assignments/{id}/history` 조회
 * - BE 가 합성 ASSIGN 이벤트를 항상 첫 행으로 반환하므로 정상 응답은 최소 1건
 * - 빈 상태는 비정상/방어 케이스에서만 노출
 *
 * 닫기: ESC / 배경 클릭 / X 버튼
 *
 * 보안: 모든 사용자 입력값(이름, 사유 등) 은 JSX 텍스트 보간으로 자동 이스케이프되며,
 *       `dangerouslySetInnerHTML` 을 사용하지 않는다.
 */
export function HistoryDrawer({
  open,
  onClose,
  assignmentId,
  videoName,
}: HistoryDrawerProps) {
  const enabled = open && typeof assignmentId === 'number' && assignmentId > 0;
  const { data, isLoading, isError } = useQuery<AssignmentHistory[]>({
    queryKey: assignmentId
      ? ASSIGNMENT_KEYS.history(assignmentId)
      : ['assignments', 'history', 'disabled'],
    queryFn: () => getAssignmentHistory(assignmentId as number),
    enabled,
    staleTime: 30_000,
  });

  const panelRef = useRef<HTMLDivElement>(null);
  const lastActiveRef = useRef<HTMLElement | null>(null);

  // ESC 닫기
  useEffect(() => {
    if (!open) return;
    const handler = (e: KeyboardEvent) => {
      if (e.key === 'Escape') {
        e.stopPropagation();
        onClose();
      }
    };
    document.addEventListener('keydown', handler);
    return () => document.removeEventListener('keydown', handler);
  }, [open, onClose]);

  // 포커스 트랩 + 복귀
  useEffect(() => {
    if (!open) return;
    lastActiveRef.current = document.activeElement as HTMLElement | null;
    const root = panelRef.current;
    if (root) {
      const first = root.querySelector<HTMLElement>(FOCUSABLE_SELECTOR);
      (first ?? root).focus();
    }
    const trap = (e: KeyboardEvent) => {
      if (e.key !== 'Tab' || !root) return;
      const focusables = Array.from(
        root.querySelectorAll<HTMLElement>(FOCUSABLE_SELECTOR),
      );
      if (focusables.length === 0) {
        e.preventDefault();
        return;
      }
      const first = focusables[0]!;
      const last = focusables[focusables.length - 1]!;
      const active = document.activeElement as HTMLElement | null;
      if (e.shiftKey && active === first) {
        e.preventDefault();
        last.focus();
      } else if (!e.shiftKey && active === last) {
        e.preventDefault();
        first.focus();
      }
    };
    document.addEventListener('keydown', trap);
    return () => {
      document.removeEventListener('keydown', trap);
      lastActiveRef.current?.focus?.();
    };
  }, [open]);

  if (!open) return null;

  const node = (
    // 배경 클릭으로도 닫을 수 있게 하되, ESC + X 버튼이 주 닫기 경로이므로 보조 수단.
    // eslint-disable-next-line jsx-a11y/click-events-have-key-events, jsx-a11y/no-static-element-interactions
    <div
      data-testid="history-drawer-backdrop"
      className="fixed inset-0 z-50 bg-black/30"
      onClick={(e) => {
        if (e.target === e.currentTarget) onClose();
      }}
    >
      <div
        ref={panelRef}
        role="dialog"
        aria-modal="true"
        aria-label="배정 이력"
        tabIndex={-1}
        className="absolute inset-y-0 right-0 flex h-full w-96 max-w-full translate-x-0 flex-col bg-white shadow-lg outline-hidden transition-transform duration-300 ease-out"
      >
        {/* 헤더 */}
        <div className="border-b border-gray-100 px-5 pt-5 pb-4">
          <div className="flex items-center justify-between">
            {/* 제목 옆 장식 아이콘은 두지 않는다 — 제목 텍스트를 되풀이할 뿐이다. */}
            <h2 className="text-title-sm font-semibold text-gray-900">배정 이력</h2>
            <button
              type="button"
              onClick={onClose}
              aria-label="배정 이력 닫기"
              className={`inline-flex h-8 w-8 items-center justify-center rounded-md text-gray-400 transition-colors hover:bg-gray-100 hover:text-gray-600 ${KRDS_FOCUS}`}
            >
              <X className="h-4 w-4" aria-hidden="true" />
            </button>
          </div>
          {/* 대상 작업 박스 */}
          <div className="mt-3 rounded-md bg-gray-50 px-3 py-2">
            <p className="text-[11px] font-medium uppercase tracking-wide text-gray-600">
              대상 작업
            </p>
            <p className="mt-0.5 truncate text-body-md font-semibold text-gray-800">
              {videoName ?? '영상 정보 없음'}
            </p>
          </div>
        </div>

        {/* 타임라인 영역 */}
        <div className="flex-1 overflow-y-auto px-5 py-5">
          {isLoading ? (
            <div className="space-y-3" data-testid="history-loading">
              <Skeleton height={56} />
              <Skeleton height={56} />
              <Skeleton height={56} />
            </div>
          ) : isError ? (
            <p className="text-body-md text-danger">이력을 불러올 수 없습니다.</p>
          ) : !data || data.length === 0 ? (
            <EmptyState
              icon={<HistoryIcon size={28} aria-hidden />}
              message="이력이 없습니다"
            />
          ) : (
            <ol className="relative space-y-5 border-l-2 border-gray-200 pl-5">
              {data.map((row) => (
                <li key={row.eventSeq} className="relative">
                  <span
                    className={`absolute -left-[25px] top-2 h-2 w-2 rounded-full ring-2 ring-white ${dotClass(row.eventTypeCd)}`}
                    aria-hidden
                  />
                  <div className="space-y-1">
                    <p className="text-caption text-gray-500">
                      {formatDate(row.occurredAt)}
                    </p>
                    <p className="text-body-md font-semibold text-gray-800">
                      {describeEvent(row)}
                    </p>
                    {row.reason ? (
                      <p className="text-caption text-gray-500">
                        사유: {row.reason}
                      </p>
                    ) : null}
                  </div>
                </li>
              ))}
            </ol>
          )}
        </div>
      </div>
    </div>
  );

  return createPortal(node, document.body);
}

/**
 * eventType 별 좌측 dot 색상 (Tailwind class) — KRDS 의미상태색 토큰. 원시 팔레트 미사용.
 *
 * 의미군 매핑: APPROVE=성공(success) · REJECT=실패(danger) ·
 * REASSIGN/CANCEL_SUBMIT=주의(warning) · ASSIGN/SUBMIT=정보(info) ·
 * 개인정보 감사 2종=중립(neutral).
 *
 * ⚠ ASSIGN 과 SUBMIT 은 **같은 정보군**이라 같은 토큰을 쓴다(구 구현은 ASSIGN 만
 *   `primary-600` 이라 같은 군인데 색이 갈렸다). 개인정보 감사 2종은 워크플로 진행이
 *   아니라 기록이므로 의미 상태색이 아닌 **중립 톤**이다 — 구 구현의 `info` 재사용은
 *   그 둘을 진행 이벤트와 같은 군으로 보이게 했다.
 */
export function dotClass(code: TaskEventType): string {
  switch (code) {
    case 'ASSIGN':
    case 'SUBMIT':
      return 'bg-info';
    case 'REASSIGN':
    case 'CANCEL_SUBMIT':
      return 'bg-warning';
    case 'APPROVE':
      return 'bg-success';
    case 'REJECT':
      return 'bg-danger';
    case 'PRIVACY_META_UPDATE':
    case 'PRIVACY_META_RESET':
      return 'bg-neutral-500';
    default:
      return 'bg-gray-400';
  }
}

/**
 * 이벤트 한 줄 설명 문구 합성 — `{행위자} — {행위 설명}` 패턴.
 *
 * actor / subject / prev 이름이 없으면 user #{id} fallback, id 도 없으면 '시스템' 등으로 표시.
 */
export function describeEvent(row: AssignmentHistory): string {
  const actor =
    row.actorUserName ??
    (row.actorUserNo != null ? `user #${row.actorUserNo}` : '시스템');
  const subject =
    row.subjectUserName ??
    (row.subjectUserNo != null ? `user #${row.subjectUserNo}` : '');
  // prev 는 현재 카피에 직접 노출하지 않지만, 향후 확장 여지로 보관.
  void row.prevUserName;
  void row.prevUserNo;

  switch (row.eventTypeCd) {
    case 'ASSIGN':
      return `${actor} — ${subject} 작업자에게 배정`;
    case 'REASSIGN':
      return `${actor} — ${subject}(으)로 재배정`;
    case 'SUBMIT':
      return `${actor} — 검수 제출`;
    case 'CANCEL_SUBMIT':
      return `${actor} — 검수 취소`;
    case 'APPROVE':
      return `${actor} — 검수 승인 완료`;
    case 'REJECT':
      return `${actor} — 검수 반려`;
    case 'PRIVACY_META_UPDATE':
      return `${actor} — 개인정보 선언 저장`;
    case 'PRIVACY_META_RESET':
      return `${actor} — 비식별 신고로 개인정보 선언 초기화`;
    default:
      return `${actor} — ${row.eventTypeCd}`;
  }
}

/**
 * ISO/LocalDateTime 문자열을 `YYYY-MM-DD` 로 포매팅.
 * 잘못된 입력이면 원본을 그대로 반환 (예외 방어).
 */
function formatDate(s: string): string {
  if (!s) return '';
  try {
    const d = new Date(s);
    if (Number.isNaN(d.getTime())) return s;
    const pad = (n: number) => n.toString().padStart(2, '0');
    return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
  } catch {
    return s;
  }
}
