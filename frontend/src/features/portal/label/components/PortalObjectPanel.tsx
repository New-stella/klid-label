// 포털 라벨링 — 오른쪽 속성 칸의 「객체」 탭(객체 목록 · 속성 · 이미지 조절).
//
// <h3>흐름은 원본 그대로</h3>
// 원본 객체 목록(`ObjectClassTree`)과 같은 판정이다 —
//   · 무리는 라벨 이름(className)으로 묶고, 무리 점 색은 라벨 마스터 색이다(대표 항목은 순서와 무관하게
//     `groupRepresentative` 가 고르고, 색 판정은 `getLabelDisplayColor` 단일 지점이 한다)
//   · 줄 이름은 「라벨 #순번」, 만든 방식은 보간 → 자동 → 수동 순으로 가른다
//   · 고르기 · 숨김 · 잠금 · 지우기는 스토어 그대로. 잠긴 객체는 지울 수 없고, 편집 차단 중에는 고르기 · 지우기가 막힌다
//   · 포털에는 트랙 번호 변경 · 분할 · 트랙 삭제가 없다(원본 목록도 포털판에서는 세우지 않는다)
//
// <h3>모양 — 포털 저작도구 화면이 정본</h3>
// 포털 화면(KLID_Portal `AuthoringLabelingView` 의 객체 탭)과 같다 — 면 없는 「객체 목록」 판(건수는 이름 줄 끝),
// 그 아래 「속성」 판 안에 「객체 속성」 · 「이미지 조절」.
// ★ 원본 목록은 줄마다 트랙 색 막대를 세웠다. 포털 목록은 색을 무리 머리의 라벨 색 점 하나만 쓴다
//   (목록 안의 색이 두 갈래가 되지 않게 — 포털 화면 결정). 트랙 ID 는 줄의 「T:…」 글자가 받은 값 그대로 말한다.

import { useMemo } from 'react';

import { ObjectList, type ObjectListGroup } from '@portal/pages/workspace/authoring/ObjectList';
import { ToolPanel } from '@portal/pages/workspace/authoring/ToolPanel';
import { groupRepresentative } from '@/features/label/components/ObjectClassTree';
import { useLabelMasters } from '@/features/label/hooks/useLabelMasters';
import type { Label } from '@/features/label/types';
import {
  FALLBACK_LABEL_COLOR,
  getLabelDisplayColor,
  safeHexColor,
} from '@/features/label/utils/labelColor';
import { resolveLabelDisplayName } from '@/features/label/utils/labelDisplayName';
import { useIsEditBlocked, useLabelStore } from '@/stores/useLabelStore';

import { PortalImageAdjust } from './PortalImageAdjust';
import { PortalObjectAttributes } from './PortalObjectAttributes';

export function PortalObjectPanel({
  labels,
  imageWidth,
  imageHeight,
}: {
  labels: Label[];
  imageWidth?: number;
  imageHeight?: number;
}) {
  const editBlocked = useIsEditBlocked();
  const selectedId = useLabelStore((s) => s.selectedLabelId);
  const selectLabel = useLabelStore((s) => s.selectLabel);
  const removeLabel = useLabelStore((s) => s.removeLabel);
  const hiddenLabelIds = useLabelStore((s) => s.hiddenLabelIds);
  const lockedLabelIds = useLabelStore((s) => s.lockedLabelIds);
  const toggleLabelVisibility = useLabelStore((s) => s.toggleLabelVisibility);
  const toggleLabelLock = useLabelStore((s) => s.toggleLabelLock);
  const { data: labelMasters } = useLabelMasters();

  const groups: ObjectListGroup[] = useMemo(() => {
    const map = new Map<string, Label[]>();
    for (const l of labels) {
      const key = l.className || 'UNKNOWN';
      const arr = map.get(key) ?? [];
      arr.push(l);
      map.set(key, arr);
    }
    return Array.from(map.entries()).map(([className, items]) => {
      const representative = groupRepresentative(items);
      const color = representative
        ? safeHexColor(getLabelDisplayColor(representative, labelMasters, { useTrackFallback: false }))
        : FALLBACK_LABEL_COLOR;
      const displayName = resolveLabelDisplayName(className);
      return {
        label: displayName,
        color,
        items: items.map((obj, idx) => ({
          id: obj.id,
          name: `${displayName} #${idx + 1}`,
          // 트랙 ID 는 원본 목록처럼 받은 값 그대로 — 없으면 「T:—」, 숫자가 아니어도 그 이름을 보인다
          trackId: obj.trackId != null && obj.trackId !== '' ? obj.trackId : undefined,
          source:
            obj.lblSrcCd === 'INTERPOLATED' ? 'interp' : obj.source !== 'MANUAL' ? 'auto' : 'manual',
          shape: (obj.shape?.type ?? '-').toUpperCase(),
          hidden: hiddenLabelIds.has(obj.id),
          locked: lockedLabelIds.has(obj.id),
        })),
      };
    });
  }, [labels, labelMasters, hiddenLabelIds, lockedLabelIds]);

  return (
    <div className="klid-labeling-panel-body">
      <ToolPanel
        title="객체 목록"
        surface={false}
        aside={<span data-testid="object-count-badge">{labels.length}개 객체</span>}
      >
        <ObjectList
          groups={groups}
          selectedId={selectedId}
          disabled={editBlocked}
          onSelect={(id) => {
            if (editBlocked) return;
            selectLabel(id);
          }}
          onToggleHidden={toggleLabelVisibility}
          onToggleLocked={toggleLabelLock}
          onDelete={(id) => {
            if (editBlocked || lockedLabelIds.has(id)) return;
            removeLabel(id);
          }}
        />
      </ToolPanel>
      <ToolPanel title="속성" surface={false}>
        <div className="klid-labeling-panel-group">
          <PortalObjectAttributes
            labels={labels}
            imageWidth={imageWidth}
            imageHeight={imageHeight}
          />
          <PortalImageAdjust />
        </div>
      </ToolPanel>
    </div>
  );
}
