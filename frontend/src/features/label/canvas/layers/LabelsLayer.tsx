import { Fragment, useEffect, useRef } from 'react';
import { Circle, Line, Rect, Transformer } from 'react-konva';
import type Konva from 'konva';

import { useLabelStore } from '@/stores/useLabelStore';

import { useLabelMasters } from '../../hooks/useLabelMasters';
import type { KeypointShape, Label } from '../../types';
import { COCO_SKELETON } from '../../types';
import { getLabelDisplayColor } from '../../utils/labelColor';
import { canvasRectToImageBox } from '../utils/bboxEdit';
import {
  clampToImage,
  translateFromCanvas,
  translateToCanvas,
  type Geometry,
} from '../utils/coordinateTransformer';
import { cycleVisibility, skeletonEdgeToCanvasLine, visibilityStyle } from '../utils/keypointHelpers';
import {
  imagePointsToCanvas,
  movePolygonByCanvasDelta,
  moveVertexToCanvas,
  shouldRenderVertexAnchors,
} from '../utils/polygonEdit';

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

  /**
   * 폴리곤 전체 이동 — 선택 Line 의 dragEnd. 노드 x/y 가 캔버스 이동량(delta).
   * 모든 점에 scale 보정된 이미지 delta 를 적용하고 외접 박스 기준 경계 클램프(모양 유지).
   * 이동 후 노드 위치는 0 으로 리셋해 다음 렌더에서 점 좌표와 이중 반영되지 않게 한다.
   */
  function commitPolygonMove(id: string, node: Konva.Node, points: number[]) {
    const dx = node.x();
    const dy = node.y();
    node.x(0);
    node.y(0);
    if (dx === 0 && dy === 0) return;
    const moved = movePolygonByCanvasDelta(geometry, points, dx, dy);
    updateLabel(id, { shape: { type: 'POLYGON', points: moved } });
  }

  /** 꼭짓점 앵커 dragEnd — 해당 점만 캔버스 좌표 → 이미지 좌표로 갱신 (개별 클램프). */
  function commitVertex(id: string, node: Konva.Node, points: number[], vertexIndex: number) {
    const moved = moveVertexToCanvas(geometry, points, vertexIndex, node.x(), node.y());
    if (moved === points) return;
    updateLabel(id, { shape: { type: 'POLYGON', points: moved } });
  }

  /**
   * 키포인트 관절 앵커 dragEnd — 드래그한 index 관절의 x/y 만 이미지 좌표로 갱신(경계 클램프).
   * 가시성(v)·다른 관절은 보존. 불변성 유지를 위해 새 배열/객체 생성.
   */
  function commitKeypointDrag(
    id: string,
    keypoints: KeypointShape['keypoints'],
    index: number,
    node: Konva.Node,
  ) {
    const img = clampToImage(geometry, translateFromCanvas(geometry, node.x(), node.y()));
    const next = keypoints.map((kp, i) => (i === index ? { ...kp, x: img.x, y: img.y } : kp));
    updateLabel(id, { shape: { type: 'KEYPOINT', keypoints: next } });
  }

  /**
   * 키포인트 관절 Alt+클릭 — 해당 index 관절의 가시성(v) 을 2→1→0→2 순환(R7).
   * 좌표·다른 관절은 보존. 일반 클릭은 라벨 선택으로 위임(클릭/드래그 충돌 회피).
   */
  function commitKeypointVisibility(
    id: string,
    keypoints: KeypointShape['keypoints'],
    index: number,
  ) {
    const next = keypoints.map((kp, i) =>
      i === index ? { ...kp, v: cycleVisibility(kp.v) } : kp,
    );
    updateLabel(id, { shape: { type: 'KEYPOINT', keypoints: next } });
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
          const imagePoints = label.shape.points;
          const pts = imagePointsToCanvas(geometry, imagePoints);
          // 폴리곤 이동은 BBOX 와 동일 정책: 잠금 아니고 선택 시에만 draggable.
          // Transformer 스케일 리사이즈는 점 왜곡 때문에 폴리곤에 적용하지 않음 (이동 + 꼭짓점 편집만).
          const draggable = !readOnly && isSelected;
          // 선택 + 비잠금 + 점 수 임계 이하일 때만 꼭짓점 앵커 렌더 (대량 SAM2 폴리곤 렉 방지).
          const showAnchors = isSelected && !readOnly && shouldRenderVertexAnchors(imagePoints);
          return (
            <Fragment key={label.id}>
              <Line
                points={pts}
                stroke={stroke}
                strokeWidth={strokeWidth}
                dash={dash}
                closed
                fill={`${stroke}33`}
                draggable={draggable}
                onClick={() => selectLabel(label.id)}
                onTap={() => selectLabel(label.id)}
                onDragEnd={(e) => commitPolygonMove(label.id, e.target, imagePoints)}
              />
              {showAnchors &&
                imagePoints.reduce<JSX.Element[]>((acc, _v, i) => {
                  if (i % 2 !== 0) return acc;
                  const vertexIndex = i / 2;
                  acc.push(
                    <Circle
                      key={`${label.id}-v${vertexIndex}`}
                      x={pts[i]}
                      y={pts[i + 1]}
                      radius={4}
                      fill="#FFFFFF"
                      stroke={stroke}
                      strokeWidth={1.5}
                      draggable
                      onDragEnd={(e) =>
                        commitVertex(label.id, e.target, imagePoints, vertexIndex)
                      }
                    />,
                  );
                  return acc;
                }, [])}
            </Fragment>
          );
        }
        if (label.shape.type === 'KEYPOINT') {
          const keypoints = label.shape.keypoints;
          // 선택 + 비잠금 시 각 관절 개별 드래그로 좌표 수정 가능.
          const draggable = !readOnly && isSelected;
          return (
            <Fragment key={label.id}>
              {/* 스켈레톤 연결선 (COCO_SKELETON 19엣지, v=0 끝점은 숨김). */}
              {COCO_SKELETON.map((edge, i) => {
                const line = skeletonEdgeToCanvasLine(geometry, keypoints, edge);
                if (!line) return null;
                return (
                  <Line
                    key={`${label.id}-edge-${i}`}
                    points={line}
                    stroke={stroke}
                    strokeWidth={strokeWidth}
                    dash={dash}
                    listening={false}
                  />
                );
              })}
              {/* 관절 마커 — 가시성별 불투명도/점선, 개별 draggable. */}
              {keypoints.map((kp, i) => {
                const c = translateToCanvas(geometry, kp.x, kp.y);
                const vs = visibilityStyle(kp.v);
                return (
                  <Circle
                    key={`${label.id}-kpt-${i}`}
                    x={c.x}
                    y={c.y}
                    radius={5}
                    fill={stroke}
                    stroke="#FFFFFF"
                    strokeWidth={1}
                    opacity={vs.opacity}
                    dash={vs.dash}
                    draggable={draggable}
                    onClick={(e) => {
                      // Alt+클릭 = 가시성 순환(편집 가능 시), 일반 클릭 = 선택.
                      if (!readOnly && e.evt?.altKey) {
                        commitKeypointVisibility(label.id, keypoints, i);
                        return;
                      }
                      selectLabel(label.id);
                    }}
                    onTap={() => selectLabel(label.id)}
                    onDragEnd={(e) => commitKeypointDrag(label.id, keypoints, i, e.target)}
                  />
                );
              })}
            </Fragment>
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
