// SCR-LABEL-001 우측 상단 객체 트리 (mock 정합 — 분류별 그룹화 + 펼치기 + bbox/polygon 표시).

import { useEffect, useMemo, useRef, useState } from 'react';
import {
  Bot,
  Check,
  ChevronDown,
  ChevronRight,
  Eye,
  EyeOff,
  Link2,
  ListX,
  Lock,
  Pencil,
  Scissors,
  Trash2,
  Unlock,
  X,
} from 'lucide-react';

import { ConfirmDialog } from '@/components/common/ConfirmDialog';
import { cn } from '@/lib/cn';
import { isEditBlockedNow, useLabelStore, useIsEditBlocked } from '@/stores/useLabelStore';
import { useUiStore } from '@/stores/useUiStore';

import { busyRejectedMessage } from '../hooks/useBusyTask';
import { useLabelMasters } from '../hooks/useLabelMasters';
import { resolveLabelDisplayName } from '../utils/labelDisplayName';
import type { Label } from '../types';
import { FALLBACK_LABEL_COLOR, getLabelDisplayColor, safeHexColor } from '../utils/labelColor';
import { trackIdToColor } from '../utils/trackColor';

interface ObjectClassTreeProps {
  labels: Label[];
  /**
   * 트랙 번호 변경 저장 콜백(Phase 4). (fromTrackId, toTrackId).
   * 부모가 mergeTracks API(미사용 번호로의 병합=이름변경)로 배선한다. 미지정 시 클라이언트
   * 상태(store)만 갱신하고 다음 저장 시 반영한다.
   */
  onRenameTrack?: (fromTrackId: string, toTrackId: string) => void;
  /**
   * 트랙 삭제 콜백(R4). (trackId, fromFrameNo). 현재 프레임(fromFrameNo) 이후 해당 트랙 궤적을 삭제한다.
   * 부모가 deleteTrack API 로 배선한다. 미지정 시 트랙 삭제 액션을 숨긴다.
   */
  onDeleteTrack?: (trackId: string, fromFrameNo: number) => void;
  /**
   * 트랙 분할 콜백(R5). (trackId, atFrameNo). 현재 프레임(atFrameNo) 기준 트랙을 새 트랙으로 분할한다.
   * 부모가 splitTrack API 로 배선한다. 미지정 시 트랙 분할 액션을 숨긴다.
   */
  onSplitTrack?: (trackId: string, atFrameNo: number) => void;
  /**
   * 현재 보고 있는 프레임 번호 — R4 삭제(fromFrameNo)/R5 분할(atFrameNo) 기준값. 트랙 삭제/분할은
   * 이 프레임을 기준으로 동작한다. 미지정(undefined)이면 트랙 삭제/분할 액션을 노출하지 않는다.
   */
  currentFrameNo?: number;
  /**
   * Phase 10(축소) — 포털 채널 여부. 포털 라벨은 트랙 데이터모델 부재(프레임별 단건)라
   * rename/머지가 불가능하므로, true 면 트랙 번호 변경(연필) 진입 자체를 숨긴다.
   * 내부 전용 mergeTracks(/v1/videos/{rawSn}/tracks/merge)는 PORTAL 채널 403 이라 절대 호출하지 않는다.
   */
  portalMode?: boolean;
}

/**
 * 그룹 대표 항목 선택 — **배열 순서에 좌우되지 않는 결정적 판정**.
 *
 * 그룹핑 키가 `className` **문자열**인데 라벨 마스터 이름에는 유일성 제약이 없다. 같은 이름·다른
 * `labelId` 라벨이 한 그룹에 섞이면 `items[0]`(= 배열 순서)로 대표를 고를 때 정렬·필터·재조회로
 * 순서만 바뀌어도 그룹 점 색이 흔들린다.
 *
 * 기준: **최소 `labelId`**(미연결 null 은 뒤로) → 동률·전부 null 이면 라벨 `id` 사전순.
 * - `labelId asc` 는 `canvas/layers/resolveDefaultLabel` 의 안정 정렬 규약(`sortNo asc → labelId asc`)
 *   보조키와 같은 방향이다. `sortNo` 는 마스터에만 있고 캔버스 `Label` 에는 없어 여기선 쓸 수 없다.
 * - `id` tiebreak 이 있어야 **전부 미연결(레거시)** 인 그룹에서도 순서 독립이 성립한다.
 *
 * ⚠ 색상 판정은 하지 않는다 — 단일 진실원 `utils/labelColor.getLabelDisplayColor` 에
 *   **어떤 항목을 넘길지**만 정한다(판정 로직 복제 금지).
 */
export function groupRepresentative(items: readonly Label[]): Label | undefined {
  let best: Label | undefined;
  for (const cand of items) {
    if (best === undefined || compareRepresentative(cand, best) < 0) best = cand;
  }
  return best;
}

/** 대표 후보 비교자 — 음수면 a 가 대표에 더 가깝다. */
function compareRepresentative(a: Label, b: Label): number {
  const ka = masterKeyOf(a);
  const kb = masterKeyOf(b);
  if (ka !== kb) {
    // 마스터 미연결(null)은 항상 뒤 — 연결된 항목이 있으면 그 쪽이 대표다.
    if (ka === null) return 1;
    if (kb === null) return -1;
    return ka - kb;
  }
  if (a.id === b.id) return 0;
  return a.id < b.id ? -1 : 1;
}

/** 대표 선택 키 = 라벨 마스터 PK. 유효하지 않으면 null(=미연결). */
function masterKeyOf(label: Label): number | null {
  return typeof label.labelId === 'number' && Number.isFinite(label.labelId)
    ? label.labelId
    : null;
}

export function ObjectClassTree({
  labels,
  onRenameTrack,
  onDeleteTrack,
  onSplitTrack,
  currentFrameNo,
  portalMode = false,
}: ObjectClassTreeProps) {
  // 편집 차단 단일 판정원 — 장시간 작업 중에는 객체/트랙 편집(삭제·번호변경·분할) 진입을 막는다.
  // 캔버스만 막고 목록 패널을 열어두면 같은 라벨을 옆문으로 지울 수 있다.
  const editBlocked = useIsEditBlocked();
  const selectedId = useLabelStore((s) => s.selectedLabelId);
  const selectLabel = useLabelStore((s) => s.selectLabel);
  const removeLabel = useLabelStore((s) => s.removeLabel);
  const updateLabel = useLabelStore((s) => s.updateLabel);
  // Phase 3 R6 — 객체 개별 표시/숨김 + 잠금.
  const hiddenLabelIds = useLabelStore((s) => s.hiddenLabelIds);
  const toggleLabelVisibility = useLabelStore((s) => s.toggleLabelVisibility);
  const lockedLabelIds = useLabelStore((s) => s.lockedLabelIds);
  const toggleLabelLock = useLabelStore((s) => s.toggleLabelLock);
  // 그룹 헤더 색상 판정용 — 캔버스(LabelsLayer)/속성 패널과 같은 공유 쿼리(staleTime 5분).
  const { data: labelMasters } = useLabelMasters();

  const [collapsed, setCollapsed] = useState<Record<string, boolean>>({});
  // 트랙 번호 인라인 편집 상태 — 편집 중인 라벨 id 와 입력 draft.
  const [renamingId, setRenamingId] = useState<string | null>(null);
  const [draft, setDraft] = useState('');
  // 편집 진입 시 입력칸으로 포커스를 옮긴다. 연필 버튼은 편집 모드로 바뀌며 언마운트되므로,
  // 포커스를 명시적으로 옮기지 않으면 키보드 사용자는 포커스를 body 로 잃고 입력칸까지
  // 다시 Tab 으로 찾아가야 한다. (구 autoFocus 속성이 하던 일을 명시적 이동으로 대체 —
  // autoFocus 는 마운트 시점에 무조건 포커스를 뺏어 페이지 진입 맥락까지 흔든다.)
  const renameInputRef = useRef<HTMLInputElement>(null);
  useEffect(() => {
    if (renamingId != null) renameInputRef.current?.focus();
  }, [renamingId]);
  // R4 파괴적 안전 — 트랙 삭제(현재 프레임 이후 궤적)는 되돌릴 수 없으므로 확인 다이얼로그를 거친다.
  const [deleteConfirm, setDeleteConfirm] = useState<{
    trackId: string;
    fromFrameNo: number;
    label: string;
  } | null>(null);

  const startRename = (obj: Label) => {
    setRenamingId(obj.id);
    setDraft(String(obj.trackId ?? ''));
  };

  const commitRename = (obj: Label) => {
    // 차단 중 확정된 편집은 반영하지 않는다. **무음으로 버리지 않는다** — 사용자는 번호가 바뀐
    // 줄 알고 다음 작업으로 넘어간다. draft 는 그대로 두어 해제 후 다시 확정할 수 있게 한다.
    // 렌더 값이 낡았을 수 있으므로 실시간 store 값도 함께 본다(fail-closed).
    if (editBlocked || isEditBlockedNow()) {
      useUiStore.getState().pushToast({
        variant: 'warning',
        message: busyRejectedMessage(useLabelStore.getState().busy?.kind ?? null),
      });
      return;
    }
    const next = draft.trim();
    const current = obj.trackId ?? null;
    setRenamingId(null);
    // 빈 값/무변경은 no-op.
    if (!next || next === (current ?? '')) return;
    // 같은 트랙(current)의 모든 라벨 trackId 를 일괄 변경(트랙 단위 rename). current 가 null(트랙
    // 미부여)이면 이 라벨만 신규 trackId 부여.
    const targets = current != null ? labels.filter((l) => l.trackId === current) : [obj];
    targets.forEach((l) => updateLabel(l.id, { trackId: next }));
    // 서버 트랙(current!=null)일 때만 영속(미사용 번호로의 병합=rename). 클라이언트 신규 부여는
    // 다음 일괄 저장에서 반영.
    if (current != null) onRenameTrack?.(current, next);
  };

  const groups = useMemo(() => {
    const map = new Map<string, Label[]>();
    for (const l of labels) {
      const key = l.className || 'UNKNOWN';
      const arr = map.get(key) ?? [];
      arr.push(l);
      map.set(key, arr);
    }
    return Array.from(map.entries());
  }, [labels]);

  if (groups.length === 0) {
    return (
      <div className="p-4 text-caption text-gray-500 text-center">
        이 프레임에 객체가 없습니다
      </div>
    );
  }

  const toggleGroup = (key: string) =>
    setCollapsed((prev) => ({ ...prev, [key]: !prev[key] }));

  return (
    <>
      <div className="overflow-y-auto flex-1 text-body-md">
      {groups.map(([className, items]) => {
        const isCollapsed = collapsed[className] ?? false;
        // 그룹 점 = **라벨 마스터 색상**. 판정은 공용 단일 진실원(getLabelDisplayColor)을 재사용한다 —
        // 하드코딩 색상표(구 labelColors.LABEL_CLASS_DEFS)는 마스터와 어긋나는 두 번째 진실원이라 폐지했다.
        // useTrackFallback:false — 그룹은 분류 축이므로 trackId 해시색이 새어들면 안 된다
        // (개별 항목 막대의 trackIdToColor 는 트랙 시각화 의도라 그대로 유지).
        // 대표 항목은 groupRepresentative 가 **순서 독립적**으로 고른다 — items[0] 은 정렬·필터·
        // 재조회로 순서가 바뀌면 그룹 색이 흔들린다(className 그룹핑은 labelId 유일성을 보장하지 않음).
        const representative = groupRepresentative(items);
        const color = representative
          ? safeHexColor(
              getLabelDisplayColor(representative, labelMasters, { useTrackFallback: false }),
            )
          : FALLBACK_LABEL_COLOR;
        // 표시명은 공용 함수 단일 출처 — 마스터 등록명(className 원문) 그대로다.
        // (2026-08-03 재확정: 구 LABEL_CLASS_DEFS 한글 치환은 폐지. 마스터가 단일 진실원.)
        const displayName = resolveLabelDisplayName(className);

        return (
          <div key={className}>
            <button
              type="button"
              onClick={() => toggleGroup(className)}
              className="w-full flex items-center gap-1.5 px-3 py-1.5 hover:bg-gray-50 text-gray-700 text-label font-semibold"
            >
              {isCollapsed ? <ChevronRight size={12} /> : <ChevronDown size={12} />}
              <span
                className="w-2.5 h-2.5 rounded-full shrink-0"
                style={{ backgroundColor: color }}
              />
              {displayName}
              <span className="ml-auto text-gray-500">({items.length})</span>
            </button>

            {!isCollapsed &&
              items.map((obj, idx) => {
                const isSelected = selectedId === obj.id;
                const shapeType = obj.shape?.type ?? '-';
                const isInterpolated = obj.lblSrcCd === 'INTERPOLATED';
                const isAuto = obj.source !== 'MANUAL';
                // Phase 4: 보간 우선 → 자동 → 수동 순.
                // 표식은 아이콘 라이브러리(보간=링크 / 자동=봇 / 수동=연필)다. 행 버튼은 aria-label 로
                // 이름이 고정돼 자식이 낭독되지 않으므로(이모지였을 때도 같다) 아이콘은 aria-hidden 이
                // 정확하고, 마우스 사용자를 위한 설명은 title 로 준다. 출처 축은 data 속성으로 노출한다.
                const SourceIcon = isInterpolated ? Link2 : isAuto ? Bot : Pencil;
                const sourceKind = isInterpolated ? 'INTERPOLATED' : isAuto ? 'AUTO' : 'MANUAL';
                const sourceTitle = isInterpolated
                  ? '보간 라벨'
                  : isAuto
                    ? '자동 생성 라벨'
                    : '수동 입력 라벨';
                // 순번(#N)은 목록 내 안정적 순서 식별자(=idx+1)로만 유지한다.
                // track_id 와 섞지 않는다 — track_id 는 아래 별도 chip 으로 명확히 표기.
                const objNumber = idx + 1;
                // track_id 원값(있으면 실제 트랙 ID, 없으면 null → "미부여" 표기).
                const trackId = obj.trackId ?? null;
                const barColor = trackIdToColor(obj.trackId);
                // Phase 3 R6 — 개별 표시/숨김·잠금 상태.
                const isHidden = hiddenLabelIds.has(obj.id);
                const isLocked = lockedLabelIds.has(obj.id);
                return (
                  <div
                    key={obj.id}
                    className={cn(
                      'flex items-stretch gap-2 pl-3 pr-2 py-1 text-caption group',
                      isSelected
                        ? 'bg-primary-50 text-primary-900'
                        : 'text-gray-700 hover:bg-gray-50',
                    )}
                  >
                    <span
                      data-testid="label-color-bar"
                      aria-hidden="true"
                      className="inline-block w-1 shrink-0 rounded-sm"
                      style={{ backgroundColor: barColor }}
                    />
                    {renamingId === obj.id ? (
                      <div className="flex-1 flex items-center gap-1">
                        <span
                          aria-hidden
                          title={sourceTitle}
                          data-label-source={sourceKind}
                          className="inline-flex shrink-0 text-gray-500"
                        >
                          <SourceIcon className="h-3 w-3" />
                        </span>
                        {/* 편집 대상이 track_id 임을 UI 에서 명확히 — 라벨을 "트랙 ID"로 표기(문구 통일). */}
                        <span className="truncate text-gray-700">트랙 ID</span>
                        <input
                          ref={renameInputRef}
                          type="text"
                          value={draft}
                          onChange={(e) => setDraft(e.target.value)}
                          onClick={(e) => e.stopPropagation()}
                          onKeyDown={(e) => {
                            if (e.key === 'Enter') commitRename(obj);
                            else if (e.key === 'Escape') setRenamingId(null);
                          }}
                          className="w-16 bg-white border border-gray-300 rounded px-1 text-caption text-gray-900"
                          aria-label="트랙 ID 입력"
                        />
                        <button
                          type="button"
                          onClick={(e) => {
                            e.stopPropagation();
                            commitRename(obj);
                          }}
                          className="text-green-400 hover:text-green-300"
                          aria-label="트랙 ID 저장"
                        >
                          <Check size={12} />
                        </button>
                        <button
                          type="button"
                          onClick={(e) => {
                            e.stopPropagation();
                            setRenamingId(null);
                          }}
                          className="text-gray-500 hover:text-gray-900"
                          aria-label="트랙 ID 변경 취소"
                        >
                          <X size={12} />
                        </button>
                      </div>
                    ) : (
                      <button
                        type="button"
                        // 선택은 이동·리사이즈·속성편집의 진입점이다 — 차단 중에는 캔버스 선택이
                        // 막히므로 목록 행도 같이 막아야 옆문이 열리지 않는다(AC1).
                        onClick={() => {
                          if (editBlocked) return;
                          selectLabel(obj.id);
                        }}
                        disabled={editBlocked}
                        className="flex-1 flex items-center gap-2 text-left disabled:cursor-not-allowed"
                        aria-label={`${displayName} #${objNumber} 선택`}
                      >
                        <span
                          aria-hidden
                          title={sourceTitle}
                          data-label-source={sourceKind}
                          className="inline-flex shrink-0 text-gray-500"
                        >
                          <SourceIcon className="h-3 w-3" />
                        </span>
                        <span className="flex-1 truncate">
                          {displayName} #{objNumber}
                        </span>
                        {/* track_id 별도 chip — 순번(#N)과 시각적으로 구분(작은 글씨/별도 배경).
                            값이 있으면 실제 track_id, 없으면 "미부여"를 명시적으로 표기. */}
                        {trackId != null ? (
                          <span
                            title={`트랙 ID ${trackId}`}
                            aria-label={`트랙 ID ${trackId}`}
                            className="shrink-0 rounded bg-primary-600/40 px-1 text-[10px] font-medium text-primary-100"
                          >
                            T:{trackId}
                          </span>
                        ) : (
                          <span
                            title="트랙 ID 미부여"
                            aria-label="트랙 ID 미부여"
                            className="shrink-0 text-[10px] text-gray-500"
                          >
                            T:—
                          </span>
                        )}
                        <span className="text-gray-500 text-caption uppercase">{shapeType}</span>
                      </button>
                    )}
                    {/* 표시/숨김 토글(eye) — 상태 항상 노출. hiddenLabelIds 반영. */}
                    {renamingId !== obj.id && (
                      <button
                        type="button"
                        onClick={(e) => {
                          e.stopPropagation();
                          toggleLabelVisibility(obj.id);
                        }}
                        className="text-gray-500 hover:text-primary-600"
                        aria-label={`${displayName} #${objNumber} ${isHidden ? '표시' : '숨김'}`}
                        aria-pressed={isHidden}
                      >
                        {isHidden ? <EyeOff size={12} /> : <Eye size={12} />}
                      </button>
                    )}
                    {/* 잠금 토글(lock) — 상태 항상 노출. lockedLabelIds 반영. */}
                    {renamingId !== obj.id && (
                      <button
                        type="button"
                        onClick={(e) => {
                          e.stopPropagation();
                          toggleLabelLock(obj.id);
                        }}
                        className="text-gray-500 hover:text-primary-600"
                        aria-label={`${displayName} #${objNumber} ${isLocked ? '잠금 해제' : '잠금'}`}
                        aria-pressed={isLocked}
                      >
                        {isLocked ? <Lock size={12} /> : <Unlock size={12} />}
                      </button>
                    )}
                    {/* Phase 10(축소) — 포털은 트랙 rename/머지 미제공(데이터모델 부재)이라 연필 버튼 숨김.
                        잠금(isLocked) 객체는 트랙 ID 변경 진입 차단. */}
                    {!portalMode && renamingId !== obj.id && !isLocked && !editBlocked && (
                      <button
                        type="button"
                        onClick={(e) => {
                          e.stopPropagation();
                          startRename(obj);
                        }}
                        className="opacity-0 group-hover:opacity-100 text-gray-500 hover:text-primary-600 transition-opacity"
                        aria-label={`${displayName} #${objNumber} 트랙 ID 변경`}
                      >
                        <Pencil size={12} />
                      </button>
                    )}
                    {/* R5 트랙 분할(split) — 현재 프레임 기준. 서버 트랙(trackId 있음)만 노출.
                        개별 라벨 Trash2 와 구분되는 트랙 단위 액션. */}
                    {!portalMode &&
                      renamingId !== obj.id &&
                      !isLocked &&
                      !editBlocked &&
                      trackId != null &&
                      currentFrameNo != null &&
                      onSplitTrack && (
                        <button
                          type="button"
                          onClick={(e) => {
                            e.stopPropagation();
                            onSplitTrack(trackId, currentFrameNo);
                          }}
                          className="opacity-0 group-hover:opacity-100 text-gray-500 hover:text-primary-600 transition-opacity"
                          aria-label={`${displayName} #${objNumber} 트랙 분할`}
                        >
                          <Scissors size={12} />
                        </button>
                      )}
                    {/* R4 트랙 삭제 — 현재 프레임 이후 궤적 삭제. 서버 트랙(trackId 있음)만 노출. */}
                    {!portalMode &&
                      renamingId !== obj.id &&
                      !isLocked &&
                      !editBlocked &&
                      trackId != null &&
                      currentFrameNo != null &&
                      onDeleteTrack && (
                        <button
                          type="button"
                          onClick={(e) => {
                            e.stopPropagation();
                            // 즉시 삭제하지 않고 확인 다이얼로그를 연다(파괴적 안전).
                            setDeleteConfirm({
                              trackId,
                              fromFrameNo: currentFrameNo,
                              label: `${displayName} #${objNumber}`,
                            });
                          }}
                          className="opacity-0 group-hover:opacity-100 text-gray-500 hover:text-danger transition-opacity"
                          aria-label={`${displayName} #${objNumber} 트랙 삭제`}
                        >
                          <ListX size={12} />
                        </button>
                      )}
                    {/* 잠금 객체는 삭제 차단(disabled + store 가드). */}
                    <button
                      type="button"
                      disabled={isLocked || editBlocked}
                      onClick={(e) => {
                        e.stopPropagation();
                        if (isLocked || editBlocked) return;
                        removeLabel(obj.id);
                      }}
                      className={cn(
                        'text-gray-500 transition-opacity',
                        isLocked || editBlocked
                          ? 'opacity-30 cursor-not-allowed'
                          : 'opacity-0 group-hover:opacity-100 hover:text-danger',
                      )}
                      aria-label="객체 삭제"
                    >
                      <Trash2 size={12} />
                    </button>
                  </div>
                );
              })}
          </div>
        );
      })}
      </div>
      {/* R4 파괴적 안전 — 트랙 삭제 확인. 확인 시에만 실제 삭제 콜백 실행(되돌릴 수 없음). */}
      <ConfirmDialog
        open={deleteConfirm !== null}
        variant="danger"
        title="트랙 삭제"
        description={
          deleteConfirm
            ? `${deleteConfirm.label} 트랙의 현재 프레임 이후 궤적을 모두 삭제합니다. 되돌릴 수 없습니다.`
            : ''
        }
        confirmLabel="삭제"
        cancelLabel="취소"
        onConfirm={() => {
          if (deleteConfirm) {
            onDeleteTrack?.(deleteConfirm.trackId, deleteConfirm.fromFrameNo);
          }
          setDeleteConfirm(null);
        }}
        onCancel={() => setDeleteConfirm(null)}
      />
    </>
  );
}
