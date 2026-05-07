import { useState, type RefObject } from 'react';
import { Circle, Line, Rect } from 'react-konva';
import type Konva from 'konva';

import type { Label, ToolType } from '../../types';
import { ToolType as ToolTypeEnum } from '../../types';
import { isValidBox, normalizeBox } from '../utils/canvasGeometry';
import {
  clampToImage,
  translateFromCanvas,
  type Geometry,
  type Point,
} from '../utils/coordinateTransformer';
import { closePolygonIfNear, validatePolygonPoints } from '../utils/polygonHelpers';

interface OverlayLayerProps {
  geometry: Geometry;
  activeTool: ToolType;
  onLabelAdd?: (label: Label) => void;
  stageRef: RefObject<Konva.Stage | null>;
}

interface BboxDraft {
  start: Point; // canvas 좌표
  current: Point;
}

const DEFAULT_CLASS_ID = 1;
const DEFAULT_CLASS_NAME = 'object';

/**
 * 활성 도구의 임시 그리기 오버레이.
 * - BBOX: pointerdown → drag → pointerup 으로 박스 생성
 * - POLYGON: 클릭으로 점 추가, dblclick 또는 시작점 근접 시 닫기
 */
export function OverlayLayer({ geometry, activeTool, onLabelAdd, stageRef }: OverlayLayerProps) {
  const [bboxDraft, setBboxDraft] = useState<BboxDraft | null>(null);
  const [polyPoints, setPolyPoints] = useState<number[]>([]);

  function pointerCanvas(): Point | null {
    const stage = stageRef.current;
    if (!stage) return null;
    const pos = stage.getPointerPosition();
    return pos ? { x: pos.x, y: pos.y } : null;
  }

  function commitBbox(start: Point, end: Point) {
    const a = translateFromCanvas(geometry, start.x, start.y);
    const b = translateFromCanvas(geometry, end.x, end.y);
    const ca = clampToImage(geometry, a);
    const cb = clampToImage(geometry, b);
    const norm = normalizeBox(ca.x, ca.y, cb.x, cb.y);
    if (!isValidBox(norm.left, norm.top, norm.right, norm.bottom)) return;
    onLabelAdd?.({
      id: `tmp-${Date.now()}`,
      frameNo: 0,
      classId: DEFAULT_CLASS_ID,
      className: DEFAULT_CLASS_NAME,
      source: 'MANUAL',
      shape: { type: 'BBOX', ...norm },
    });
  }

  function commitPolygon(points: number[]) {
    const pts: number[] = [];
    for (let i = 0; i + 1 < points.length; i += 2) {
      const p = translateFromCanvas(geometry, points[i], points[i + 1]);
      const c = clampToImage(geometry, p);
      pts.push(c.x, c.y);
    }
    const valid = validatePolygonPoints(pts);
    if (!valid) return;
    onLabelAdd?.({
      id: `tmp-${Date.now()}`,
      frameNo: 0,
      classId: DEFAULT_CLASS_ID,
      className: DEFAULT_CLASS_NAME,
      source: 'MANUAL',
      shape: { type: 'POLYGON', points: valid },
    });
  }

  // === BBox 핸들러 (전체 캔버스 영역에 invisible Rect 캡처)
  const captureRect =
    activeTool === ToolTypeEnum.BBOX || activeTool === ToolTypeEnum.POLYGON ? (
      <Rect
        x={0}
        y={0}
        width={geometry.canvas.width}
        height={geometry.canvas.height}
        fill="rgba(0,0,0,0.001)"
        onMouseDown={() => {
          if (activeTool !== ToolTypeEnum.BBOX) return;
          const p = pointerCanvas();
          if (!p) return;
          setBboxDraft({ start: p, current: p });
        }}
        onMouseMove={() => {
          if (activeTool !== ToolTypeEnum.BBOX || !bboxDraft) return;
          const p = pointerCanvas();
          if (!p) return;
          setBboxDraft({ ...bboxDraft, current: p });
        }}
        onMouseUp={() => {
          if (activeTool !== ToolTypeEnum.BBOX || !bboxDraft) return;
          const { start, current } = bboxDraft;
          commitBbox(start, current);
          setBboxDraft(null);
        }}
        onClick={() => {
          if (activeTool !== ToolTypeEnum.POLYGON) return;
          const p = pointerCanvas();
          if (!p) return;
          const next = [...polyPoints, p.x, p.y];
          const closed = closePolygonIfNear(next);
          if (closed.closed) {
            commitPolygon(closed.points);
            setPolyPoints([]);
          } else {
            setPolyPoints(next);
          }
        }}
        onDblClick={() => {
          if (activeTool !== ToolTypeEnum.POLYGON) return;
          if (polyPoints.length >= 6) {
            commitPolygon(polyPoints);
          }
          setPolyPoints([]);
        }}
      />
    ) : null;

  return (
    <>
      {captureRect}
      {bboxDraft && (
        <Rect
          x={Math.min(bboxDraft.start.x, bboxDraft.current.x)}
          y={Math.min(bboxDraft.start.y, bboxDraft.current.y)}
          width={Math.abs(bboxDraft.current.x - bboxDraft.start.x)}
          height={Math.abs(bboxDraft.current.y - bboxDraft.start.y)}
          stroke="#26A69A"
          strokeWidth={2}
          dash={[4, 4]}
          listening={false}
        />
      )}
      {polyPoints.length >= 4 && (
        <Line points={polyPoints} stroke="#26A69A" strokeWidth={2} dash={[4, 4]} listening={false} />
      )}
      {polyPoints.length > 0 && (
        <>
          {(() => {
            const dots = [];
            for (let i = 0; i + 1 < polyPoints.length; i += 2) {
              dots.push(
                <Circle
                  key={i}
                  x={polyPoints[i]}
                  y={polyPoints[i + 1]}
                  radius={4}
                  fill="#26A69A"
                  listening={false}
                />,
              );
            }
            return dots;
          })()}
        </>
      )}
    </>
  );
}
