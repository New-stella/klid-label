import { ArrowDown, ArrowUp, ArrowUpDown, History, ListTodo, Play, RefreshCw, UserPlus } from 'lucide-react';

import { Button } from '@/components/common/Button';
import { Checkbox } from '@/components/common/Checkbox';
import { EmptyState } from '@/components/common/EmptyState';
import { EventTypeBadge } from '@/components/common/EventTypeBadge';
import { Field, FieldLabel } from '@/components/common/Field';
import { Skeleton } from '@/components/common/Skeleton';
import { StatusBadge, type BadgeStatus } from '@/components/common/StatusBadge';
import { augTypeLabel, type AugType } from '@/features/augment/augTypeLabel';
import { formatDateTime } from '@/features/review/formatDateTime';
import {
  sortDirectionOf,
  type BoardSortColumn,
  type BoardSortEntry,
} from '@/features/task/boardSort';
import { TASK_STATUS_LABEL, type RowStatus } from '@/features/task/statusLabels';
import type { Task } from '@/features/task/types';
import { isMarkingBlocked, type Video } from '@/features/video/types';
import { cn } from '@/lib/cn';
import { KRDS_FOCUS } from '@/lib/focusRing';

/** 작업목록 한 행의 화면 모델 (영상 + optional 배정). */
export interface TaskRow {
  id: string;
  video: Video;
  task: Task | undefined;
  rowStatus: RowStatus;
  videoName: string;
  /** 증강/해상도 파생 데이터 여부 (뱃지 노출 판정). */
  augmented: boolean;
  /** 증강 종류 코드 — null 이면 뱃지에 '증강'만 표시. */
  augType: AugType | null;
}

// AssignmentStatus → BadgeStatus 매핑 (UNASSIGNED는 PENDING으로)
const STATUS_BADGE_MAP: Record<RowStatus, BadgeStatus> = {
  UNASSIGNED: 'PENDING',
  PENDING: 'PENDING',
  IN_PROGRESS: 'IN_PROGRESS',
  REVIEW_PENDING: 'REVIEW_PENDING',
  COMPLETED: 'COMPLETED',
  REJECTED: 'REJECTED',
};

// `whitespace-nowrap` 필수 — 헤더 라벨은 전부 짧은 고정 문구인데 한글은 단어 경계가 없어
// 폭이 좁아지면 글자 단위로 끊긴다("영상 ID" → 2줄). 아래 TABLE_MIN_WIDTH 와 한 세트다.
const TH_CLASS =
  'whitespace-nowrap px-4 py-3 text-left text-label font-semibold uppercase tracking-wide text-gray-500';

/**
 * 표 최소 폭 — 래퍼의 `overflow-x-auto` 가 **실제로 발동하게** 만드는 값이다.
 *
 * min-width 가 없으면 `table-layout: auto` + `w-full` 이 표를 부모 폭에 억지로 맞추고,
 * 한글은 단어 경계가 없어 셀이 글자 단위로 뭉개진다(1280px 실측: 배지 15×74px, 행 높이 111px).
 *
 * 산정 근거 — 컬럼별 최소 필요 폭(콘텐츠 + `px-4` 좌우 32px) 합:
 * - REVIEWER 9컬럼: 체크박스 40 + 영상명 192(`min-w-[160px]`+32) + 영상 ID 94 +
 *   촬영일시 ~197(`toLocaleString('ko-KR')` 최장) + 이벤트 ~146 + 상태 ~112 +
 *   작업자 ~100 + 검수자 ~110 + 액션 ~184(재배정+이력) ≈ **1175** → 여유 포함 1200
 * - WORKER 7컬럼(체크박스·촬영일시 없음): ≈ **906** → 여유 포함 960
 *
 * 상한 제약: FHD(1920×1080, 표 래퍼 clientWidth 1630)에서 가로 스크롤이 새로 생기면 안 되므로
 * 두 값 모두 1630 미만이어야 한다. WORKER 값을 REVIEWER 와 같이 키우면 1280px 에서 스크롤이
 * 필요 없는데도 생기므로(래퍼 990 ≥ 960) 역할별로 나눈다.
 */
const TABLE_MIN_WIDTH = {
  reviewer: 'min-w-[1200px]',
  worker: 'min-w-[960px]',
} as const;

function videoCode(videoId: number): string {
  return `video-${String(videoId).padStart(4, '0')}`;
}

interface SortableHeaderProps {
  label: string;
  column: BoardSortColumn;
  sort: readonly BoardSortEntry[];
  onSort: (column: BoardSortColumn) => void;
}

/**
 * 정렬 가능한 컬럼 헤더.
 *
 * 접근성: 클릭 요소는 `button`, 정렬 상태는 색/아이콘이 아니라 `aria-sort` 로 전달한다.
 */
function SortableHeader({ label, column, sort, onSort }: SortableHeaderProps) {
  const direction = sortDirectionOf(sort, column);
  const ariaSort =
    direction === 'asc' ? 'ascending' : direction === 'desc' ? 'descending' : 'none';
  const Icon = direction === 'asc' ? ArrowUp : direction === 'desc' ? ArrowDown : ArrowUpDown;
  return (
    <th scope="col" aria-sort={ariaSort} className={TH_CLASS}>
      <button
        type="button"
        onClick={() => onSort(column)}
        className={cn(
          'inline-flex items-center gap-1 rounded uppercase tracking-wide',
          'hover:text-gray-700',
          KRDS_FOCUS,
        )}
      >
        {label}
        <Icon size={12} aria-hidden />
      </button>
    </th>
  );
}

export interface TaskBoardTableProps {
  rows: TaskRow[];
  isReviewer: boolean;
  isLoading: boolean;
  /** 백그라운드 갱신 중 (이전 결과를 유지하므로 정지 화면이 최신으로 보인다). */
  refreshing: boolean;
  totalElements: number;
  totalPages: number;
  /** 표시용(클램프된) 현재 페이지 인덱스. */
  currentPage: number;
  sort: readonly BoardSortEntry[];
  onSort: (column: BoardSortColumn) => void;
  selectedVideoIds: Set<number>;
  onToggleRow: (videoId: number) => void;
  onToggleAllPaged: () => void;
  /** 목록 조회 실패 등으로 화면 데이터가 최신이 아닐 때 배정 액션을 잠근다. */
  actionsDisabled: boolean;
  /** 검수자 ID → 이름 (BE reviewerName 폴백). */
  reviewerMap: Record<number, string>;
  onAssign: (row: TaskRow) => void;
  onHistory: (row: TaskRow) => void;
  onOpenLabel: (srcSn: number) => void;
  onOpenMarking: (videoId: number) => void;
}

/**
 * 작업목록 테이블 — 헤더(정렬) + 행 렌더링 전담.
 *
 * TaskListPage 에서 **순수 추출**한 표시 컴포넌트다. 데이터 조회·필터·선택 상태는 전부 페이지가 갖고,
 * 여기서는 받은 것을 그리기만 한다.
 *
 * UI/UX §4-5 정합 — priority/deadline 컬럼은 절대 추가하지 않는다.
 */
export function TaskBoardTable({
  rows,
  isReviewer,
  isLoading,
  refreshing,
  totalElements,
  totalPages,
  currentPage,
  sort,
  onSort,
  selectedVideoIds,
  onToggleRow,
  onToggleAllPaged,
  actionsDisabled,
  reviewerMap,
  onAssign,
  onHistory,
  onOpenLabel,
  onOpenMarking,
}: TaskBoardTableProps) {
  // 일괄 배정은 미배정 행 전용이다(사양 SCREEN-012 ★) — 이미 작업자가 배정된 행은
  // "전체 선택" 대상 집합에서 제외한다(개별 체크박스는 아래에서 별도로 disabled 처리).
  const pagedVideoIds = rows.filter((r) => !r.task?.workerId).map((r) => r.video.id);
  const allPagedSelected =
    pagedVideoIds.length > 0 && pagedVideoIds.every((id) => selectedVideoIds.has(id));
  const somePagedSelected = pagedVideoIds.some((id) => selectedVideoIds.has(id));
  // 체크박스 + 영상명/영상ID/(촬영일시)/이벤트/상태/작업자/검수자/액션
  const columnCount = isReviewer ? 9 : 7;

  return (
    <div className="overflow-hidden rounded-lg border border-gray-200 bg-white shadow-sm">
      <div className="flex items-center gap-3 border-b border-gray-100 bg-gray-50 px-4 py-2">
        {isReviewer && pagedVideoIds.length > 0 && (
          <Field orientation="horizontal">
            <Checkbox
              checked={allPagedSelected ? true : somePagedSelected ? 'indeterminate' : false}
              disabled={actionsDisabled}
              onCheckedChange={() => onToggleAllPaged()}
            />
            <FieldLabel className="text-label font-normal text-gray-600">
              현재 페이지 전체 선택
            </FieldLabel>
          </Field>
        )}
        <span className="ml-auto flex items-center gap-2 text-caption text-gray-500">
          {refreshing && (
            <span data-testid="board-refreshing" role="status">
              갱신 중…
            </span>
          )}
          전체 {totalElements}건
          {totalPages > 1 ? ` (${currentPage + 1}/${totalPages} 페이지)` : ''}
        </span>
      </div>
      <div className="overflow-x-auto">
        <table
          className={cn(
            'w-full text-body-md',
            isReviewer ? TABLE_MIN_WIDTH.reviewer : TABLE_MIN_WIDTH.worker,
          )}
        >
          <thead>
            <tr className="border-b border-gray-200 bg-gray-50">
              {isReviewer && <th className="w-10 px-4 py-3"></th>}
              <th scope="col" className={TH_CLASS}>
                영상명
              </th>
              {/* 영상 ID·촬영일시는 BE 정렬 allowlist(rawSn / shtDt)와 1:1 대응하는 컬럼이다.
                  WORKER 시각(/v1/assignments)은 서버 정렬을 지원하지 않아 정적 헤더로 둔다. */}
              {isReviewer ? (
                <SortableHeader label="영상 ID" column="videoId" sort={sort} onSort={onSort} />
              ) : (
                <th scope="col" className={TH_CLASS}>
                  영상 ID
                </th>
              )}
              {isReviewer && (
                <SortableHeader
                  label="촬영일시"
                  column="capturedAt"
                  sort={sort}
                  onSort={onSort}
                />
              )}
              <th scope="col" className={TH_CLASS}>
                이벤트
              </th>
              <th scope="col" className={TH_CLASS}>
                상태
              </th>
              <th scope="col" className={TH_CLASS}>
                작업자
              </th>
              <th scope="col" className={TH_CLASS}>
                검수자
              </th>
              <th scope="col" className={TH_CLASS}>
                액션
              </th>
            </tr>
          </thead>
          <tbody>
            {isLoading ? (
              Array.from({ length: 5 }).map((_, i) => (
                <tr key={i} className="border-b border-gray-100">
                  {Array.from({ length: columnCount }).map((__, j) => (
                    <td key={j} className="px-4 py-3">
                      <Skeleton height={16} />
                    </td>
                  ))}
                </tr>
              ))
            ) : rows.length === 0 ? (
              <tr>
                <td colSpan={columnCount} className="px-3 py-12">
                  <EmptyState message="배정된 작업이 없습니다" />
                </td>
              </tr>
            ) : (
              rows.map((r) => (
                <tr
                  key={r.id}
                  className="border-b border-gray-100 transition-colors hover:bg-gray-50"
                >
                  {isReviewer && (
                    <td className="px-4 py-3">
                      {/* 일괄 배정은 미배정 행 전용이다 — 이미 작업자가 배정된 행은
                          선택 체크박스를 비활성화한다(사양 SCREEN-012 ★). */}
                      <Checkbox
                        aria-label={`${r.videoName} 선택`}
                        checked={selectedVideoIds.has(r.video.id)}
                        disabled={actionsDisabled || !!r.task?.workerId}
                        onCheckedChange={() => onToggleRow(r.video.id)}
                      />
                    </td>
                  )}
                  <td className="px-4 py-3">
                    <p className="min-w-[160px] max-w-[200px] truncate text-body-md font-medium text-gray-800">
                      {r.videoName}
                    </p>
                  </td>
                  {/* 영상 코드는 하이픈에서 끊기면 안 되는 단일 식별자다. */}
                  <td className="whitespace-nowrap px-4 py-3">
                    <span className="text-caption text-gray-400">{videoCode(r.video.id)}</span>
                  </td>
                  {isReviewer && (
                    <td className="px-4 py-3">
                      {r.video.capturedAt ? (
                        <span className="whitespace-nowrap text-body-md text-gray-700">
                          {formatDateTime(r.video.capturedAt)}
                        </span>
                      ) : (
                        <span className="text-caption text-gray-400">-</span>
                      )}
                    </td>
                  )}
                  {/* 이벤트·증강 뱃지는 pill 이라 줄바꿈되면 형태가 무너진다
                      ("이상행동(유괴)" 처럼 긴 라벨이 글자 단위로 끊긴다). */}
                  <td className="whitespace-nowrap px-4 py-3">
                    <div className="flex flex-col items-start gap-1">
                      {r.video.eventName ? (
                        <EventTypeBadge eventType={r.video.eventName} />
                      ) : (
                        <span className="text-caption text-gray-400">-</span>
                      )}
                      {/* 증강/해상도 파생 데이터 뱃지 (R3). 원본(augmented=false)은 미표시.
                          기술모델명 비노출 — augTypeLabel 로 한글 라벨만 표시. */}
                      {r.augmented && (
                        <span
                          data-testid={`task-aug-badge-${r.video.id}`}
                          aria-label={`증강 데이터: ${augTypeLabel(r.augType)}`}
                          className="inline-flex w-fit items-center rounded bg-info/10 px-2 py-0.5 text-label font-medium text-info-700"
                        >
                          {augTypeLabel(r.augType)}
                        </span>
                      )}
                    </div>
                  </td>
                  {/* 상태 뱃지 — 1280px 에서 "미배정" 이 `미/배/정` 3줄로 쪼개지던 지점. */}
                  <td className="whitespace-nowrap px-4 py-3">
                    <StatusBadge
                      status={STATUS_BADGE_MAP[r.rowStatus]}
                      label={TASK_STATUS_LABEL[r.rowStatus]}
                    />
                  </td>
                  {/* 사람 이름·"미배정"/"미등록" 은 짧은 고정 문구라 줄바꿈 이득이 없다. */}
                  <td className="whitespace-nowrap px-4 py-3">
                    {r.task?.workerName ? (
                      <span className="text-body-md text-gray-700">{r.task.workerName}</span>
                    ) : (
                      <span className="text-body-md italic text-gray-400">미배정</span>
                    )}
                  </td>
                  <td className="whitespace-nowrap px-4 py-3">
                    {r.task?.reviewerName ? (
                      <span className="text-body-md text-gray-700">{r.task.reviewerName}</span>
                    ) : r.task?.reviewerId ? (
                      <span className="text-body-md text-gray-700">
                        {reviewerMap[r.task.reviewerId] ?? `user #${r.task.reviewerId}`}
                      </span>
                    ) : (
                      <span className="text-body-md italic text-gray-400">미등록</span>
                    )}
                  </td>
                  {/* 액션 버튼 라벨("배정"/"재배정"/"이력"/"작업"/"마킹")은 줄바꿈되면
                      버튼이 세로로 늘어나 행 높이를 무너뜨린다. */}
                  <td className="whitespace-nowrap px-4 py-3">
                    <div className="flex flex-nowrap gap-1">
                      {/* 배정 / 재배정 — REVIEWER (mock 정합: ghost 텍스트 버튼).
                          COMPLETED(검수 승인 완료) 행은 재배정 불가 — 버튼 자체를 가린다.
                          BE 가드(ASSIGNMENT_ALREADY_COMPLETED)와 짝을 이루는 UI 정합. */}
                      {isReviewer &&
                        !(r.task?.workerId && r.rowStatus === 'COMPLETED') && (
                          <Button
                            variant="ghost"
                            size="sm"
                            disabled={actionsDisabled}
                            title={
                              actionsDisabled
                                ? '목록이 최신 정보가 아니어서 배정할 수 없습니다'
                                : undefined
                            }
                            onClick={() => onAssign(r)}
                          >
                            {r.task?.workerId ? (
                              <RefreshCw size={14} aria-hidden />
                            ) : (
                              <UserPlus size={14} aria-hidden />
                            )}
                            {r.task?.workerId ? '재배정' : '배정'}
                          </Button>
                        )}
                      {/* 이력 — task가 있을 때만 (REVIEWER) */}
                      {isReviewer && r.task && (
                        <Button variant="ghost" size="sm" onClick={() => onHistory(r)}>
                          <History size={14} aria-hidden />
                          이력
                        </Button>
                      )}
                      {/* WORKER: 본인 배정 작업 시작 / 마킹 + 이력 보기 (BE Service 레이어에서 IDOR 방어).
                          - 프레임 존재(firstSrcSn) → "작업" 버튼 (라벨링 화면으로 navigate)
                          - 프레임 미존재 → "마킹" 버튼 (마킹 화면으로 navigate) */}
                      {!isReviewer && r.task && (
                        <>
                          {r.task.firstSrcSn ? (
                            <Button
                              size="sm"
                              variant="ghost"
                              onClick={() => onOpenLabel(r.task!.firstSrcSn as number)}
                              aria-label={`작업 시작 ${r.videoName}`}
                            >
                              <Play size={14} aria-hidden /> 작업
                            </Button>
                          ) : isMarkingBlocked(r.video) ? (
                            // AC4 — 비식별 미완료 영상은 마킹 진입 차단(버튼 비활성 + 사유 안내).
                            // 색상만이 아닌 텍스트/aria 라벨로 사유 전달(component.md 접근성).
                            <Button
                              size="sm"
                              variant="ghost"
                              disabled
                              title="비식별 완료 후 마킹 가능"
                              aria-label={`마킹 불가 (비식별 완료 후 가능) ${r.videoName}`}
                            >
                              <ListTodo size={14} aria-hidden /> 마킹
                            </Button>
                          ) : (
                            <Button
                              size="sm"
                              variant="ghost"
                              onClick={() => onOpenMarking(r.task!.videoId)}
                              aria-label={`마킹 시작 ${r.videoName}`}
                            >
                              <ListTodo size={14} aria-hidden /> 마킹
                            </Button>
                          )}
                          <Button
                            size="sm"
                            variant="ghost"
                            onClick={() => onHistory(r)}
                            aria-label="배정 이력 보기"
                          >
                            <History size={14} aria-hidden /> 이력
                          </Button>
                        </>
                      )}
                    </div>
                  </td>
                </tr>
              ))
            )}
          </tbody>
        </table>
      </div>
    </div>
  );
}

export default TaskBoardTable;
