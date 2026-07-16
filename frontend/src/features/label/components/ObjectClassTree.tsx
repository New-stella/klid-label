// SCR-LABEL-001 우측 상단 객체 트리 (mock 정합 — 분류별 그룹화 + 펼치기 + bbox/polygon 표시).

import { useMemo, useState } from 'react';
import { Check, ChevronDown, ChevronRight, Pencil, Trash2, X } from 'lucide-react';

import { cn } from '@/lib/cn';
import { useLabelStore } from '@/stores/useLabelStore';

import { getLabelColor, getLabelDisplayName } from '../labelColors';
import type { Label } from '../types';
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
   * Phase 10(축소) — 포털 채널 여부. 포털 라벨은 트랙 데이터모델 부재(프레임별 단건)라
   * rename/머지가 불가능하므로, true 면 트랙 번호 변경(연필) 진입 자체를 숨긴다.
   * 내부 전용 mergeTracks(/v1/videos/{rawSn}/tracks/merge)는 PORTAL 채널 403 이라 절대 호출하지 않는다.
   */
  portalMode?: boolean;
}

export function ObjectClassTree({
  labels,
  onRenameTrack,
  portalMode = false,
}: ObjectClassTreeProps) {
  const selectedId = useLabelStore((s) => s.selectedLabelId);
  const selectLabel = useLabelStore((s) => s.selectLabel);
  const removeLabel = useLabelStore((s) => s.removeLabel);
  const updateLabel = useLabelStore((s) => s.updateLabel);

  const [collapsed, setCollapsed] = useState<Record<string, boolean>>({});
  // 트랙 번호 인라인 편집 상태 — 편집 중인 라벨 id 와 입력 draft.
  const [renamingId, setRenamingId] = useState<string | null>(null);
  const [draft, setDraft] = useState('');

  const startRename = (obj: Label) => {
    setRenamingId(obj.id);
    setDraft(String(obj.trackId ?? ''));
  };

  const commitRename = (obj: Label) => {
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
      <div className="p-4 text-xs text-gray-400 text-center">
        이 프레임에 객체가 없습니다
      </div>
    );
  }

  const toggleGroup = (key: string) =>
    setCollapsed((prev) => ({ ...prev, [key]: !prev[key] }));

  return (
    <div className="overflow-y-auto flex-1 text-sm">
      {groups.map(([className, items]) => {
        const isCollapsed = collapsed[className] ?? false;
        const color = getLabelColor(className);
        const displayName = getLabelDisplayName(className);

        return (
          <div key={className}>
            <button
              type="button"
              onClick={() => toggleGroup(className)}
              className="w-full flex items-center gap-1.5 px-3 py-1.5 hover:bg-gray-700 text-gray-200 text-xs font-semibold"
            >
              {isCollapsed ? <ChevronRight size={12} /> : <ChevronDown size={12} />}
              <span
                className="w-2.5 h-2.5 rounded-full shrink-0"
                style={{ backgroundColor: color }}
              />
              {displayName}
              <span className="ml-auto text-gray-400">({items.length})</span>
            </button>

            {!isCollapsed &&
              items.map((obj, idx) => {
                const isSelected = selectedId === obj.id;
                const shapeType = obj.shape?.type ?? '-';
                const isInterpolated = obj.lblSrcCd === 'INTERPOLATED';
                const isAuto = obj.source !== 'MANUAL';
                // Phase 4: 보간 우선 → 자동 → 수동 순.
                const sourceIcon = isInterpolated ? '🔗' : isAuto ? '🤖' : '✏️';
                // 순번(#N)은 목록 내 안정적 순서 식별자(=idx+1)로만 유지한다.
                // track_id 와 섞지 않는다 — track_id 는 아래 별도 chip 으로 명확히 표기.
                const objNumber = idx + 1;
                // track_id 원값(있으면 실제 트랙 ID, 없으면 null → "미부여" 표기).
                const trackId = obj.trackId ?? null;
                const barColor = trackIdToColor(obj.trackId);
                return (
                  <div
                    key={obj.id}
                    className={cn(
                      'flex items-stretch gap-2 pl-3 pr-2 py-1 text-xs group',
                      isSelected
                        ? 'bg-primary-600/30 text-white'
                        : 'text-gray-300 hover:bg-gray-700',
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
                        <span aria-hidden>{sourceIcon}</span>
                        {/* 편집 대상이 track_id 임을 UI 에서 명확히 — 라벨을 "트랙 ID"로 표기(문구 통일). */}
                        <span className="truncate text-gray-300">트랙 ID</span>
                        <input
                          type="text"
                          value={draft}
                          autoFocus
                          onChange={(e) => setDraft(e.target.value)}
                          onClick={(e) => e.stopPropagation()}
                          onKeyDown={(e) => {
                            if (e.key === 'Enter') commitRename(obj);
                            else if (e.key === 'Escape') setRenamingId(null);
                          }}
                          className="w-16 bg-gray-800 border border-gray-600 rounded px-1 text-xs text-white"
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
                          className="text-gray-400 hover:text-gray-200"
                          aria-label="트랙 ID 변경 취소"
                        >
                          <X size={12} />
                        </button>
                      </div>
                    ) : (
                      <button
                        type="button"
                        onClick={() => selectLabel(obj.id)}
                        className="flex-1 flex items-center gap-2 text-left"
                        aria-label={`${displayName} #${objNumber} 선택`}
                      >
                        <span aria-hidden>{sourceIcon}</span>
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
                        <span className="text-gray-500 text-xs uppercase">{shapeType}</span>
                      </button>
                    )}
                    {/* Phase 10(축소) — 포털은 트랙 rename/머지 미제공(데이터모델 부재)이라 연필 버튼 숨김. */}
                    {!portalMode && renamingId !== obj.id && (
                      <button
                        type="button"
                        onClick={(e) => {
                          e.stopPropagation();
                          startRename(obj);
                        }}
                        className="opacity-0 group-hover:opacity-100 text-gray-400 hover:text-primary-300 transition-opacity"
                        aria-label={`${displayName} #${objNumber} 트랙 ID 변경`}
                      >
                        <Pencil size={12} />
                      </button>
                    )}
                    <button
                      type="button"
                      onClick={(e) => {
                        e.stopPropagation();
                        removeLabel(obj.id);
                      }}
                      className="opacity-0 group-hover:opacity-100 text-gray-400 hover:text-red-400 transition-opacity"
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
  );
}
