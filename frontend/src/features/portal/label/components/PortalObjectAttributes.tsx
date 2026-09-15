// 포털 라벨링 — 고른 객체의 속성(라벨 · 트랙 · 생성출처 · 신뢰도 · 형태 · 좌표 · 속성 값).
//
// <h3>흐름은 원본 그대로</h3>
// 원본 객체 속성 판(`ObjectAttributePanel`)과 같은 판정 · 같은 스토어 쓰기다 —
//   · 라벨 목록은 라벨 마스터(활성만, sortNo → labelId). 바꾸면 classId · labelId · className · color 를 함께 바꾼다
//     (labelId 를 빼면 저장 왕복에서 마스터 연결이 끊긴다)
//   · 사각형 좌표는 네 칸으로 고치고, 이미지 실측 크기로 끝을 붙인 뒤 좌우 · 위아래가 뒤집히지 않게 정리한다
//   · 다각형은 꼭짓점 수만 보이고, 꼭짓점이 너무 많으면 꼭짓점 편집이 꺼진다는 사정을 알린다
//   · 편집 차단 중에는 라벨 · 좌표를 고칠 수 없다
//   · 포털에는 AI 추적 · AI 분할 정밀도가 없다(원본 판도 포털판에서는 두 칸을 세우지 않는다)
//
// <h3>모양 — 포털 저작도구 화면이 정본</h3>
// 포털 화면(KLID_Portal `AuthoringLabelingView` 의 「객체 속성」)과 같다 — 라벨 드롭다운(면 없는 필터 판) ·
// 면 없는 라벨·값 줄 · 좌표 두 칸씩 두 줄 · 「속성 값」 판.

import { useMemo, type ReactNode } from 'react';
import { Badge, TextInput } from 'krds-react';

import {
  Alert,
  Dropdown,
  EmptyState,
  FilterPanel,
  KeyValueList,
  ProgressBar,
  type KeyValueItem,
} from '@portal/components/custom';
import { ToolPanel } from '@portal/pages/workspace/authoring/ToolPanel';
import { normalizeBox } from '@/features/label/canvas/utils/canvasGeometry';
import { shouldRenderVertexAnchors } from '@/features/label/canvas/utils/polygonEdit';
import { useLabelMasters } from '@/features/label/hooks/useLabelMasters';
import type { Label } from '@/features/label/types';
import { resolveLabelDisplayName } from '@/features/label/utils/labelDisplayName';
import { useIsEditBlocked, useLabelStore } from '@/stores/useLabelStore';

import { PortalObjectAttributeValues } from './PortalObjectAttributeValues';

type BoxField = 'left' | 'top' | 'right' | 'bottom';

export function PortalObjectAttributes({
  labels,
  imageWidth,
  imageHeight,
}: {
  labels: Label[];
  /** 이미지 실측 크기 — 로드 전이면 undefined(그때는 위쪽 끝을 붙이지 않는다). */
  imageWidth?: number;
  imageHeight?: number;
}) {
  // 편집 차단 판정은 원본 판과 같다 — 포털판에는 추적 칸이 없어 프레임을 좁히지 않고 본다.
  const editBlocked = useIsEditBlocked();
  const { data: labelMasters } = useLabelMasters();
  const available = useMemo(() => {
    if (!labelMasters) return [];
    return labelMasters
      .filter((m) => m.useYn === 'Y')
      .sort((a, b) => (a.sortNo !== b.sortNo ? a.sortNo - b.sortNo : a.labelId - b.labelId))
      .map((m) => ({ id: m.labelId, name: m.name, color: m.color }));
  }, [labelMasters]);
  const selectedId = useLabelStore((s) => s.selectedLabelId);
  const updateLabel = useLabelStore((s) => s.updateLabel);
  const target = useMemo(() => labels.find((l) => l.id === selectedId), [labels, selectedId]);

  if (!target) {
    return (
      <ToolPanel level={4} title="객체 속성" surface={false}>
        <EmptyState size="xs" title="선택된 객체가 없습니다." />
      </ToolPanel>
    );
  }

  const lowConfidence = target.confidence !== undefined && target.confidence < 0.5;

  const clampX = (v: number) => {
    const lower = Math.max(0, v);
    return imageWidth != null ? Math.min(imageWidth - 1, lower) : lower;
  };
  const clampY = (v: number) => {
    const lower = Math.max(0, v);
    return imageHeight != null ? Math.min(imageHeight - 1, lower) : lower;
  };

  const handleLabelChange = (nextValue: string) => {
    if (editBlocked) return;
    const found = available.find((l) => l.id === Number(nextValue));
    if (!found) return;
    updateLabel(target.id, {
      classId: found.id,
      labelId: found.id,
      className: found.name,
      color: found.color ?? null,
    });
  };

  const handleCoordChange = (field: BoxField, raw: string) => {
    if (editBlocked || target.shape.type !== 'BBOX') return;
    const num = Number(raw);
    if (!Number.isFinite(num)) return;
    const clamped = field === 'left' || field === 'right' ? clampX(num) : clampY(num);
    const merged = { ...target.shape, [field]: clamped };
    const norm = normalizeBox(merged.left, merged.top, merged.right, merged.bottom);
    updateLabel(target.id, { shape: { ...merged, ...norm } });
  };

  const facts: KeyValueItem[] = [
    { label: '트랙 ID', value: target.trackId != null ? target.trackId : '미부여' },
    {
      label: '생성출처',
      value: (
        <span className="klid-labeling-source">
          {target.source === 'MANUAL' ? '수동' : '자동'}
          {lowConfidence && (
            <Badge variant="light" color="warning" className="klid-badge-tint">
              낮은 신뢰도
            </Badge>
          )}
        </span>
      ),
    },
    ...(target.confidence !== undefined
      ? [
          {
            label: '신뢰도',
            value: (
              <span className="klid-labeling-confidence">
                <ProgressBar size="sm" value={target.confidence * 100} label="신뢰도" />
                {(target.confidence * 100).toFixed(1)}%
              </span>
            ),
          },
        ]
      : []),
    { label: '형태', value: target.shape?.type ?? '-' },
    ...shapeFacts(target),
  ];

  const box = target.shape.type === 'BBOX' ? target.shape : null;
  const coordInput = (field: BoxField, label: string) => (
    <FilterPanel.Field label={label}>
      <TextInput
        size="small"
        inputMode="numeric"
        aria-label={label}
        disabled={editBlocked}
        value={box && Number.isFinite(box[field]) ? String(box[field]) : ''}
        onChange={(v) => handleCoordChange(field, v)}
      />
    </FilterPanel.Field>
  );

  return (
    <ToolPanel
      level={4}
      title="객체 속성"
      surface={false}
      aside={<span data-testid="object-attribute-id">#{String(target.id ?? '').slice(0, 8)}</span>}
    >
      <div className="klid-labeling-attrs">
        {available.length > 0 ? (
          <FilterPanel surface="bare" layout="stack" aria-label="객체 라벨">
            <FilterPanel.Field label="라벨">
              <Dropdown
                size="small"
                aria-label="라벨 선택"
                disabled={editBlocked}
                value={String(target.classId)}
                options={available.map((l) => ({
                  value: String(l.id),
                  label: `${resolveLabelDisplayName(l.name)} (#${l.id})`,
                }))}
                onChange={handleLabelChange}
              />
            </FilterPanel.Field>
          </FilterPanel>
        ) : null}

        <KeyValueList
          surface={false}
          ariaLabel="객체 정보"
          items={
            available.length > 0
              ? facts
              : [
                  {
                    label: '라벨',
                    value: target.className
                      ? `${resolveLabelDisplayName(target.className)} (#${target.classId})`
                      : '라벨 없음',
                  },
                  ...facts,
                ]
          }
        />

        {target.shape.type === 'POLYGON' && !shouldRenderVertexAnchors(target.shape.points) && (
          <Alert tone="warning" live="status">
            정점이 많아 꼭짓점 편집은 비활성화됩니다. 폴리곤 전체 이동만 가능합니다.
          </Alert>
        )}
        {target.shape.type === 'KEYPOINT' && (
          <p className="klid-tool-panel-note">
            관절을 Alt+클릭하면 가시성(가시→비가시→미표기)이 순환됩니다.
          </p>
        )}

        {/* 사각형 좌표 — 이미지 밖 값은 이미지 끝으로 붙는다. 두 칸씩 두 줄 */}
        {box && (
          <FilterPanel surface="bare" layout="stack" aria-label="좌표">
            <FilterPanel.Row>
              {coordInput('left', 'X 좌표')}
              {coordInput('top', 'Y 좌표')}
            </FilterPanel.Row>
            <FilterPanel.Row>
              {coordInput('right', 'W 우측')}
              {coordInput('bottom', 'H 하단')}
            </FilterPanel.Row>
          </FilterPanel>
        )}

        {/* 라벨 관리에서 정의한 속성 — 객체를 바꾸면 다시 세워 편집 중이던 초안이 옮겨 붙지 않게 한다 */}
        {target.classId > 0 && (
          <PortalObjectAttributeValues
            key={target.serverId ?? target.id}
            classId={target.classId}
            serverId={target.serverId}
            editBlocked={editBlocked}
          />
        )}
      </div>
    </ToolPanel>
  );
}

/** 형태별 좌표 줄 — 사각형은 아래 좌표 칸이 맡고, 나머지는 읽기 전용 요약이다(원본 규칙). */
function shapeFacts(target: Label): { label: string; value: ReactNode }[] {
  const shape = target.shape;
  if (shape.type === 'BBOX') return [];
  if (shape.type === 'POLYGON') return [{ label: '좌표', value: `${shape.points.length / 2}개 정점` }];
  if (shape.type === 'KEYPOINT') {
    const kps = shape.keypoints;
    const visible = kps.filter((k) => k.v === 2).length;
    const occluded = kps.filter((k) => k.v === 1).length;
    const unlabeled = kps.filter((k) => k.v === 0).length;
    return [
      { label: '좌표', value: `스켈레톤 ${kps.length}관절` },
      { label: '가시성', value: `가시 ${visible} · 비가시 ${occluded} · 미표기 ${unlabeled}` },
    ];
  }
  return [{ label: '좌표', value: 'Mask' }];
}
