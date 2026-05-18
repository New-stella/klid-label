import { Fragment } from 'react';
import { Line, Rect } from 'react-konva';

import { useLabelStore } from '@/stores/useLabelStore';

import { useLabelMasters } from '../../hooks/useLabelMasters';
import type { Label } from '../../types';
import { getLabelDisplayColor } from '../../utils/labelColor';
import { translateToCanvas, type Geometry } from '../utils/coordinateTransformer';

interface LabelsLayerProps {
  labels: Label[];
  geometry: Geometry;
}

/**
 * Phase 4: 보간된 라벨 (LBL_SRC_CD='INTERPOLATED') 외곽선 점선 패턴.
 * Konva `dash` prop 은 [선, 공백] px 배열. trackColor 와 직교 — 색은 그대로 유지.
 */
const INTERPOLATED_DASH = [6, 4] as const;

/**
 * 라벨 shape 렌더 — BBox는 Rect, Polygon은 Line(closed).
 * 선택된 라벨은 굵은 테두리 + 다른 색상.
 *
 * Phase 3 (속성 직후): 외곽선 색상 우선순위
 *   1) label.color (BE LS_LABEL.color enrichment, '#RRGGBB')
 *   2) labelMasters[label.labelId].color (FE lookup)
 *   3) trackIdToColor(label.trackId) (트랙 시각화)
 *   4) source 별 fallback (기존 동작 유지)
 * 자세한 정책은 utils/labelColor.ts 참조.
 */
export function LabelsLayer({ labels, geometry }: LabelsLayerProps) {
  const selectedId = useLabelStore((s) => s.selectedLabelId);
  const selectLabel = useLabelStore((s) => s.selectLabel);
  // labelMasters 는 staleTime 5분 캐시 — LabelSidebar/OverlayLayer 와 동일 쿼리 공유.
  // 로드 실패/지연 중에도 label.color enrichment 만으로 정상 동작.
  const { data: labelMasters } = useLabelMasters();

  return (
    <>
      {labels.map((label) => {
        const isSelected = label.id === selectedId;
        const baseColor = getLabelDisplayColor(label, labelMasters);
        const stroke = isSelected ? '#FF3D71' : baseColor;
        const strokeWidth = isSelected ? 3 : 2;
        const isInterpolated = label.lblSrcCd === 'INTERPOLATED';
        // Konva dash prop — INTERPOLATED 만 적용. 미적용 시 undefined 로 두어 실선 유지.
        const dash = isInterpolated ? [...INTERPOLATED_DASH] : undefined;

        if (label.shape.type === 'BBOX') {
          const tl = translateToCanvas(geometry, label.shape.left, label.shape.top);
          const br = translateToCanvas(geometry, label.shape.right, label.shape.bottom);
          return (
            <Fragment key={label.id}>
              <Rect
                x={tl.x}
                y={tl.y}
                width={br.x - tl.x}
                height={br.y - tl.y}
                stroke={stroke}
                strokeWidth={strokeWidth}
                dash={dash}
                onClick={() => selectLabel(label.id)}
                onTap={() => selectLabel(label.id)}
              />
            </Fragment>
          );
        }
        if (label.shape.type === 'POLYGON') {
          const pts: number[] = [];
          for (let i = 0; i + 1 < label.shape.points.length; i += 2) {
            const p = translateToCanvas(
              geometry,
              label.shape.points[i],
              label.shape.points[i + 1],
            );
            pts.push(p.x, p.y);
          }
          return (
            <Line
              key={label.id}
              points={pts}
              stroke={stroke}
              strokeWidth={strokeWidth}
              dash={dash}
              closed
              fill={`${stroke}33`}
              onClick={() => selectLabel(label.id)}
              onTap={() => selectLabel(label.id)}
            />
          );
        }
        return null;
      })}
    </>
  );
}
