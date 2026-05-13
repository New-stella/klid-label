import { Fragment } from 'react';
import { Line, Rect } from 'react-konva';

import { useLabelStore } from '@/stores/useLabelStore';

import { LabelSource, type Label } from '../../types';
import { trackIdToColor } from '../../utils/trackColor';
import { translateToCanvas, type Geometry } from '../utils/coordinateTransformer';

interface LabelsLayerProps {
  labels: Label[];
  geometry: Geometry;
}

const sourceColor: Record<LabelSource, string> = {
  AUTO_YOLO: '#5B8FF9',
  AUTO_SAM2: '#9C6CF9',
  MANUAL: '#26A69A',
};

/**
 * 라벨 shape 렌더 — BBox는 Rect, Polygon은 Line(closed).
 * 선택된 라벨은 굵은 테두리 + 다른 색상.
 *
 * Phase 5: trackId 가 있으면 외곽선 색상을 trackId 해시 기반 HSL 로 결정 (같은 트랙 = 같은 색).
 *          trackId 가 없으면 기존 source 기반 색상 fallback (회귀 안전).
 */
export function LabelsLayer({ labels, geometry }: LabelsLayerProps) {
  const selectedId = useLabelStore((s) => s.selectedLabelId);
  const selectLabel = useLabelStore((s) => s.selectLabel);

  return (
    <>
      {labels.map((label) => {
        const isSelected = label.id === selectedId;
        const baseColor = label.trackId
          ? trackIdToColor(label.trackId)
          : sourceColor[label.source];
        const stroke = isSelected ? '#FF3D71' : baseColor;
        const strokeWidth = isSelected ? 3 : 2;

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
