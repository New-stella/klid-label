import { useEffect, useRef } from 'react';
import { createPortal } from 'react-dom';
import { useQuery } from '@tanstack/react-query';
import { History as HistoryIcon, X } from 'lucide-react';

import { EmptyState } from '@/components/common/EmptyState';
import { Skeleton } from '@/components/common/Skeleton';
import { KRDS_FOCUS } from '@/lib/focusRing';
import { getPortalOverlayRoot } from '@/lib/portalOverlayRoot';
import { ASSIGNMENT_KEYS } from '@/lib/queryKeys';
import { ROLE_LABEL } from '@/lib/roleDisplay';

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
 * - 타임라인: 이 창구가 돌려주는 이벤트를 **그대로** 그린다 — 종류로 거르는 질의 항목이 없어
 *   {@link TaskEventType} 값역에 있는 종류가 모두 실린다. 진행 축(ASSIGN / REASSIGN /
 *   START_REVIEW / SUBMIT / CANCEL_SUBMIT / APPROVE / REJECT)과 기록 축(PRIVACY_META_UPDATE /
 *   PRIVACY_META_RESET / FRAME_DISCARD / FRAME_RESTORE / START_VERSION_APPLY)이 함께 온다.
 *   ★종류를 개수로 적지 않는다 — 열거가 곧 목록이며 숫자는 값이 늘 때마다 틀린다(`UI-084`).
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
 *
 * [@design UI-084] [@design API-116] [@design ADR-067]
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
              {/* ★그대로 펼치지 않는다 — 같은 사람의 연속 재진입은 한 줄로 접는다(UI-084). */}
              {foldConsecutiveStartReviews(data).map((entry) => (
                <li key={entry.row.eventSeq} className="relative">
                  <span
                    className={`absolute -left-[25px] top-2 h-2 w-2 rounded-full ring-2 ring-white ${dotClass(entry.row.eventTypeCd)}`}
                    aria-hidden
                  />
                  <div className="space-y-1">
                    <p
                      className="text-caption text-gray-500"
                      data-testid={`history-when-${entry.row.eventSeq}`}
                    >
                      {/* 접은 줄은 **최초 시각과 마지막 시각을 함께** 보인다 — 최초 시각을
                          마지막 값으로 덮어쓰면 언제 처음 열었는지가 이력에서 사라진다. */}
                      {entry.count > 1
                        ? `${formatDateTimeMinute(entry.firstOccurredAt)} ~ ${formatDateTimeMinute(entry.lastOccurredAt)}`
                        : formatDate(entry.row.occurredAt)}
                    </p>
                    <p className="text-body-md font-semibold text-gray-800">
                      {describeEvent(entry.row)}
                    </p>
                    {entry.row.reason ? (
                      <p className="text-caption text-gray-500">
                        사유: {entry.row.reason}
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

  // 덧띄움은 앵커 «안»에 붙인다 — `document.body` 직하면 포털 채널에서 스타일 격리 범위
  // 밖으로 떨어진다(근거 전문은 `lib/portalOverlayRoot`). [@design INT-013]
  return createPortal(node, getPortalOverlayRoot());
}

/** 타임라인이 실제로 그리는 한 줄 — 원본 1건이거나, 연속 재진입을 접은 묶음이다. */
export interface HistoryTimelineEntry {
  /** 이 줄을 대표하는 행 — 문구·역할·사유·점 색상·`key` 를 여기서 읽는다. */
  row: AssignmentHistory;
  /** 접힌 건수. `1` 이면 접히지 않은 보통 줄이다. */
  count: number;
  /** 구간에서 가장 **이른** 발생 시각(= 최초로 시작한 시각). */
  firstOccurredAt: string;
  /** 구간에서 가장 **늦은** 발생 시각(= 마지막으로 다시 들어온 시각). */
  lastOccurredAt: string;
}

/**
 * 접기 대상 — **검수 시작 하나뿐이다.**
 *
 * ★다른 종류로 넓히지 말 것. `UI-084` 가 접으라고 한 근거는 *"보던 사람이 다시 들어오면 잡은
 * 시각을 뒤로 미루려고 검수 시작이 새로 쌓인다"* 는 **이 종류에만 있는 성질**이다. 예컨대 승인은
 * 재검수 뒤 같은 사람이 다시 승인하면 연속 두 건이 되는데, 그 둘은 **서로 다른 실제 행위**라
 * 접으면 재승인이 이력에서 사라진다. 같은 조항의 *"행위자와 종류가 모두 같은 연속 구간"* 은
 * 접는 **범위를 좁히는** 조건이지 모든 종류로 넓히라는 말이 아니다.
 */
const FOLDABLE_EVENT: TaskEventType = 'START_REVIEW';

/**
 * 행위자 동일성 키 — 사번이 있으면 사번, 없으면 이름으로 가른다.
 * 사번이 없는 행끼리 이름까지 다르면 **다른 사람**이므로 접지 않는다.
 */
function actorKeyOf(row: AssignmentHistory): string {
  return row.actorUserNo != null ? `no:${row.actorUserNo}` : `nm:${row.actorUserName ?? ''}`;
}

/** `a` 가 `b` 보다 이른가 — 파싱 불가한 값은 문자열 순으로 비교한다(예외 대신 결정적 동작). */
function isEarlier(a: string, b: string): boolean {
  const ta = new Date(a).getTime();
  const tb = new Date(b).getTime();
  if (Number.isNaN(ta) || Number.isNaN(tb)) return a < b;
  return ta < tb;
}

/**
 * 같은 사람이 **잇달아** 남긴 검수 시작을 한 줄로 접는다(`UI-084`).
 *
 * 서버는 재진입마다 기록을 새로 쌓는다 — 그것이 점유 시각을 뒤로 미루는 유일한 수단이기
 * 때문이다. 그래서 **화면이 접지 않으면** 같은 문구가 여러 줄 이어져 타임라인이 그 사람의
 * 재진입으로 덮인다.
 *
 * ★접는 범위는 **행위자와 종류가 모두 같은 연속 구간**뿐이다. 사이에 다른 사람이나 다른 종류가
 * 끼면 접지 않는다 — 떨어져 있는 두 구간을 합치면 그 사이에 무슨 일이 있었는지가 지워진다.
 *
 * ★목록 정렬 방향을 전제하지 않는다. 최초·마지막을 위치가 아니라 **값**으로 고르므로
 * 오름차순·내림차순 어느 쪽이 와도 「최초 시각」이 마지막 재진입 값으로 덮이지 않는다.
 */
export function foldConsecutiveStartReviews(
  rows: readonly AssignmentHistory[],
): HistoryTimelineEntry[] {
  const folded: HistoryTimelineEntry[] = [];
  for (const row of rows) {
    const prev = folded[folded.length - 1];
    const foldable =
      prev !== undefined &&
      row.eventTypeCd === FOLDABLE_EVENT &&
      prev.row.eventTypeCd === FOLDABLE_EVENT &&
      actorKeyOf(prev.row) === actorKeyOf(row);

    if (!foldable) {
      folded.push({
        row,
        count: 1,
        firstOccurredAt: row.occurredAt,
        lastOccurredAt: row.occurredAt,
      });
      continue;
    }

    prev.count += 1;
    if (isEarlier(row.occurredAt, prev.firstOccurredAt)) prev.firstOccurredAt = row.occurredAt;
    if (isEarlier(prev.lastOccurredAt, row.occurredAt)) prev.lastOccurredAt = row.occurredAt;
  }
  return folded;
}

/**
 * eventType 별 좌측 dot 색상 (Tailwind class) — KRDS 의미상태색 토큰. 원시 팔레트 미사용.
 *
 * 의미군 매핑: APPROVE=성공(success) · REJECT=실패(danger) ·
 * REASSIGN/CANCEL_SUBMIT=주의(warning) · ASSIGN/SUBMIT/START_REVIEW=정보(info) ·
 * 기록군(개인정보 감사 2종 + 프레임 폐기·복원 + 시작 버전 적용)=중립(neutral).
 *
 * ⚠ ASSIGN 과 SUBMIT 은 **같은 정보군**이라 같은 토큰을 쓴다(구 구현은 ASSIGN 만
 *   `primary-600` 이라 같은 군인데 색이 갈렸다). 기록군은 워크플로 진행이 아니라
 *   **무엇이 언제 바뀌었는지**를 남긴 축이므로 의미 상태색이 아닌 **중립 톤**이다 —
 *   구 구현의 `info` 재사용은 그 둘을 진행 이벤트와 같은 군으로 보이게 했다.
 *
 * ⚠ `default` 는 **폴백이지 분류가 아니다.** 여기 갈래가 없는 종류는 회색 점으로 떨어져
 *   「아직 이름 붙지 않은 무엇」처럼 보인다 — 값역이 넓어질 때마다 이 함수를 함께 본다.
 */
export function dotClass(code: TaskEventType): string {
  switch (code) {
    // 정보군 — 워크플로가 **진행**되는 이벤트. 검수 시작도 여기다(승인·반려 같은 결말이 아니다).
    case 'ASSIGN':
    case 'SUBMIT':
    case 'START_REVIEW':
      return 'bg-info';
    case 'REASSIGN':
    case 'CANCEL_SUBMIT':
      return 'bg-warning';
    case 'APPROVE':
      return 'bg-success';
    case 'REJECT':
      return 'bg-danger';
    // 기록군 — 진행이 아니라 **무엇이 언제 바뀌었는지**를 남긴 축이다(`UI-084`). 개인정보 감사
    // 2종에 프레임 폐기·복원과 시작 버전 적용이 같은 군으로 붙는다. 셋을 정보군에 넣으면
    // 배정·검수가 나아간 일과 한 덩어리로 읽히고, 폴백 회색으로 두면 분류가 없는 값처럼 보인다.
    case 'PRIVACY_META_UPDATE':
    case 'PRIVACY_META_RESET':
    case 'FRAME_DISCARD':
    case 'FRAME_RESTORE':
    case 'START_VERSION_APPLY':
      return 'bg-neutral-500';
    default:
      return 'bg-gray-400';
  }
}

/**
 * 행위자 표기 — 이름 뒤에 **행위 시점의 역할**을 괄호로 덧붙인다.
 *
 * ★역할이 없으면(이 축이 생기기 전에 쌓인 옛 이력) **빈 괄호를 남기지 않고** 이름만 보인다.
 * 값을 지어내지 않으므로 옛 행은 영영 비어 있다(백필 금지 — 복원할 수 없는 값이다).
 *
 * ★이 역할은 **그때 기록된 값**이지 그 사람의 지금 역할이 아니다. 관리자가 승인한 건은 나중에
 * 그 사람이 검수자로 바뀌어도 관리자로 남는다 — 그것이 이 축을 만든 이유다.
 */
function actorLabel(row: AssignmentHistory): string {
  const name =
    row.actorUserName ??
    (row.actorUserNo != null ? `user #${row.actorUserNo}` : '시스템');
  const role = row.actorRoleCd?.trim();
  // ★표시명 표를 여기서 새로 만들지 않는다 — 역할 표시 축의 단일 진실원을 쓴다.
  //   (그 표를 복제하면 한쪽만 갱신돼 화면마다 역할 이름이 갈린다 — 이 저장소의 반복 결함이라
  //    전역 가드가 표의 개수를 세고 있다.)
  //   모르는 코드는 **코드값 그대로** 보인다 — 빈칸으로 두면 값이 있는데 없는 것처럼 읽힌다.
  //
  // ⚠ 이 축에 **실제로 나타나는 값**은 승인자 표시 축보다 넓다 — 작업자도 제출 이벤트를 남기므로
  //   `WORKER` 가 든다. 표를 공유하는 것과 값역이 다른 것은 별개다.
  return role ? `${name}(${ROLE_LABEL[role] ?? role})` : name;
}

/**
 * 이벤트 한 줄 설명 문구 합성 — `{행위자} — {행위 설명}` 패턴.
 *
 * actor / subject / prev 이름이 없으면 user #{id} fallback, id 도 없으면 '시스템' 등으로 표시.
 * 행위자에는 **행위 시점의 역할**이 괄호로 함께 붙는다({@link actorLabel}).
 */
export function describeEvent(row: AssignmentHistory): string {
  const actor = actorLabel(row);
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
    // 검수 시작은 그 영상의 **점유를 세우는** 기록이다(ADR-067). 문구가 없으면 아래 폴백이
    // 코드값(`START_REVIEW`)을 사람에게 그대로 노출한다 — 이 줄은 이번 라운드부터 실제로 쌓인다.
    case 'START_REVIEW':
      return `${actor} — 검수 시작`;
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
    // 아래 세 종류도 이력 조회에 **실제로 실려 온다** — 이 창구에는 종류로 거르는 질의 항목이
    // 없어 원장에 쌓인 것이 그대로 온다. 문구가 없으면 아래 폴백이 코드값(`FRAME_DISCARD` 등)을
    // 사람에게 그대로 노출한다. 문구는 `UI-084` 원문 그대로다.
    case 'FRAME_DISCARD':
      return `${actor} — 프레임 폐기`;
    case 'FRAME_RESTORE':
      return `${actor} — 프레임 복원`;
    case 'START_VERSION_APPLY':
      return `${actor} — 시작 버전 적용`;
    // ★폴백은 **남긴다** — 값역이 넓어지는 동안 화면이 터지면 안 된다. 다만 이 자리에 떨어진
    //   값은 코드값이 그대로 보이므로 정상이 아니다. 그 사실을 시험이 고정한다(전 종류를 돌며
    //   코드값 미노출을 단언 + 이 폴백이 코드값을 내보임을 단언).
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

/**
 * 접은 줄 전용 시각 표기 — `YYYY-MM-DD HH:mm`.
 *
 * ★접은 줄에 **날짜만** 쓰면 쓸모가 없다. 같은 사람이 **같은 날** 여러 번 다시 들어오는 것이
 * 정확히 이 줄이 생기는 조건이라, 날짜만 보이면 최초 시각과 마지막 시각이 **같은 글자**가 되어
 * 「언제 처음 열었는지」가 사라진다 — `UI-084` 가 막으라고 한 바로 그 상태다.
 * 접히지 않은 보통 줄은 종전대로 날짜만 보인다(표시 규칙을 넓히지 않는다).
 */
export function formatDateTimeMinute(s: string): string {
  if (!s) return '';
  const d = new Date(s);
  if (Number.isNaN(d.getTime())) return s;
  const pad = (n: number) => n.toString().padStart(2, '0');
  return `${formatDate(s)} ${pad(d.getHours())}:${pad(d.getMinutes())}`;
}
