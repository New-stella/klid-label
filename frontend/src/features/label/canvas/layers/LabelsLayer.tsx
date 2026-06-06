import { Fragment, useEffect, useRef } from 'react';
import { Line, Rect, Transformer } from 'react-konva';
import type Konva from 'konva';

import { useLabelStore } from '@/stores/useLabelStore';

import { useLabelMasters } from '../../hooks/useLabelMasters';
import type { Label } from '../../types';
import { getLabelDisplayColor } from '../../utils/labelColor';
import { canvasRectToImageBox } from '../utils/bboxEdit';
import { translateToCanvas, type Geometry } from '../utils/coordinateTransformer';

interface LabelsLayerProps {
  labels: Label[];
  geometry: Geometry;
  /**
   * 편집 잠금 — true 면 선택돼도 이동/리사이즈 비활성 (작업락/포털 읽기 제약 등).
   * LabelingPage 의 isLocked(LOCKED_FOR_REDEIDENT 등)와 연동.
   */
  readOnly?: boolean;
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
 * R17 이슈4: SELECT 도구에서 선택된 BBOX 는 draggable 이동 + Transformer 리사이즈.
 *   이동/리사이즈 종료 시 캔버스 좌표를 이미지 좌표로 환산(canvasRectToImageBox)하여
 *   스토어 라벨 좌표를 갱신한다(updateLabel — undo/redo 1 step). 폴리곤은 범위 밖(이동/리사이즈 미지원).
 *
 * Phase 3 (속성 직후): 외곽선 색상 우선순위
 *   1) label.color (BE LS_LABEL.color enrichment, '#RRGGBB')
 *   2) labelMasters[label.labelId].color (FE lookup)
 *   3) trackIdToColor(label.trackId) (트랙 시각화)
 *   4) source 별 fallback (기존 동작 유지)
 * 자세한 정책은 utils/labelColor.ts 참조.
 */
export function LabelsLayer({ labels, geometry, readOnly = false }: LabelsLayerProps) {
  const selectedId = useLabelStore((s) => s.selectedLabelId);
  const selectLabel = useLabelStore((s) => s.selectLabel);
  const updateLabel = useLabelStore((s) => s.updateLabel);
  // labelMasters 는 staleTime 5분 캐시 — LabelSidebar/OverlayLayer 와 동일 쿼리 공유.
  // 로드 실패/지연 중에도 label.color enrichment 만으로 정상 동작.
  const { data: labelMasters } = useLabelMasters();

  // 선택된 BBOX 노드 ↔ Transformer 연결.
  const selectedRectRef = useRef<Konva.Rect | null>(null);
  const transformerRef = useRef<Konva.Transformer | null>(null);

  const selectedLabel = labels.find((l) => l.id === selectedId) ?? null;
  // BBOX 이고 잠금 아니면 이동/리사이즈 가능.
  const editable = !readOnly && selectedLabel?.shape.type === 'BBOX';

  useEffect(() => {
    const tr = transformerRef.current;
    if (!tr) return;
    if (editable && selectedRectRef.current) {
      tr.nodes([selectedRectRef.current]);
    } else {
      tr.nodes([]);
    }
    tr.getLayer()?.batchDraw();
  }, [editable, selectedId, labels]);

  /** 캔버스 노드의 사각형을 이미지 BBOX 로 환산하여 스토어 갱신 (무효면 무시). */
  function commitNode(id: string, node: Konva.Node) {
    const box = canvasRectToImageBox(geometry, {
      x: node.x(),
      y: node.y(),
      width: Math.abs(node.width() * node.scaleX()),
      height: Math.abs(node.height() * node.scaleY()),
    });
    // konva Transformer 는 scale 로 리사이즈 — width/height 에 반영 후 scale 초기화.
    node.scaleX(1);
    node.scaleY(1);
    if (!box) return; // 0/음수/경계 무효 — 좌표 유지
    updateLabel(id, { shape: { type: 'BBOX', ...box } });
  }

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
          const draggable = !readOnly && isSelected;
          return (
            <Fragment key={label.id}>
              <Rect
                ref={isSelected ? selectedRectRef : undefined}
                x={tl.x}
                y={tl.y}
                width={br.x - tl.x}
                height={br.y - tl.y}
                stroke={stroke}
                strokeWidth={strokeWidth}
                dash={dash}
                draggable={draggable}
                onClick={() => selectLabel(label.id)}
                onTap={() => selectLabel(label.id)}
                onDragEnd={(e) => commitNode(label.id, e.target)}
                onTransformEnd={(e) => commitNode(label.id, e.target)}
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
      {editable && (
        <Transformer
          ref={transformerRef}
          rotateEnabled={false}
          // 음수/0 크기로 뒤집히는 리사이즈 방지 — 최소 크기 보장.
          boundBoxFunc={(oldBox, newBox) =>
            newBox.width < 5 || newBox.height < 5 ? oldBox : newBox
          }
        />
      )}
    </>
  );
}
