import { useEffect, useRef, useState, type RefObject } from 'react';
import { Circle, Line, Rect } from 'react-konva';
import type Konva from 'konva';

import { useLabelStore } from '@/stores/useLabelStore';

import { useLabelMasters } from '../../hooks/useLabelMasters';
import type { Sam2SegmentRequest, Sam2SegmentResponse } from '../../api';
import type { Label, ToolType } from '../../types';
import { ToolType as ToolTypeEnum } from '../../types';
import { isValidBox, normalizeBox } from '../utils/canvasGeometry';
import {
  clampToImage,
  translateFromCanvas,
  type Geometry,
  type Point,
} from '../utils/coordinateTransformer';
import { closePolygonIfNear, simplifyPolygon, validatePolygonPoints } from '../utils/polygonHelpers';

import { resolveDefaultLabel } from './resolveDefaultLabel';

interface OverlayLayerProps {
  geometry: Geometry;
  activeTool: ToolType;
  onLabelAdd?: (label: Label) => void;
  stageRef: RefObject<Konva.Stage | null>;
  /**
   * SAM2 클릭/박스 분할 요청 함수 (SAM_SEGMENT 활성 시 주입).
   * 진행 중 무시·프레임 전환 stale 폐기는 호출자(useSam2Segment)가 책임지며 폐기 시 null 반환.
   */
  segment?: (payload: Omit<Sam2SegmentRequest, 'srcSn'>) => Promise<Sam2SegmentResponse | null>;
  /** mock 응답(모델 미로드) 수신 시 호출 — 경고 표시 + 자동 적용 차단. */
  onMockWarning?: (res: Sam2SegmentResponse) => void;
  /** 낮은 신뢰도(score < 0.3) 응답 수신 시 호출 — 적용 여부 안내. */
  onLowConfidence?: (res: Sam2SegmentResponse) => void;
}

interface BboxDraft {
  start: Point; // canvas 좌표
  current: Point;
}

/**
 * 활성 도구의 임시 그리기 오버레이.
 * - BBOX: pointerdown → drag → pointerup 으로 박스 생성
 * - POLYGON: 클릭으로 점 추가, dblclick 또는 시작점 근접 시 닫기
 *
 * Phase 7: 신규 라벨은 activeLabelId 의 라벨 마스터로부터 classId/className 을 적용.
 *   activeLabelId 가 null 이면 sortNo 최소 활성 라벨로 fallback.
 *   labelMasters 가 비어 있으면 신규 BBOX/Polygon 생성 거부 (안전장치).
 */
/** SAM2 분할 자동 적용 차단 임계 — 이 미만이면 낮은 신뢰도 안내. */
const SAM_LOW_CONFIDENCE_THRESHOLD = 0.3;

export function OverlayLayer({
  geometry,
  activeTool,
  onLabelAdd,
  stageRef,
  segment,
  onMockWarning,
  onLowConfidence,
}: OverlayLayerProps) {
  const [bboxDraft, setBboxDraft] = useState<BboxDraft | null>(null);
  // SAM_SEGMENT 박스 드래그 draft (canvas 좌표).
  const [segDraft, setSegDraft] = useState<BboxDraft | null>(null);
  const [polyPoints, setPolyPoints] = useState<number[]>([]);

  // SAM_SEGMENT: 박스 드래그 후 발생하는 click 이벤트가 포인트로 잘못 처리되지 않도록 억제.
  const segSuppressClickRef = useRef(false);

  const { data: labelMasters } = useLabelMasters();
  const activeLabelId = useLabelStore((s) => s.activeLabelId);

  // 도구가 SAM_SEGMENT 가 아니게 되면 진행 중 분할 박스 draft 초기화.
  useEffect(() => {
    if (activeTool !== ToolTypeEnum.SAM_SEGMENT) {
      setSegDraft(null);
      segSuppressClickRef.current = false;
    }
  }, [activeTool]);

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
    const def = resolveDefaultLabel(labelMasters ?? [], activeLabelId);
    if (!def) return; // 라벨 마스터 없음 — 신규 라벨 생성 거부 (Object fallback 제거)
    onLabelAdd?.({
      id: `tmp-${Date.now()}`,
      frameNo: 0,
      classId: def.labelId,
      className: def.name,
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
    const def = resolveDefaultLabel(labelMasters ?? [], activeLabelId);
    if (!def) return; // 라벨 마스터 없음 — 신규 라벨 생성 거부
    onLabelAdd?.({
      id: `tmp-${Date.now()}`,
      frameNo: 0,
      classId: def.labelId,
      className: def.name,
      source: 'MANUAL',
      shape: { type: 'POLYGON', points: valid },
    });
  }

  // 이미지 좌표 flat points 를 그대로 폴리곤으로 커밋 (SAM_SEGMENT 응답 적용 — 좌표 변환 불필요).
  function commitImagePolygon(imagePoints: number[]) {
    const clamped: number[] = [];
    for (let i = 0; i + 1 < imagePoints.length; i += 2) {
      const c = clampToImage(geometry, { x: imagePoints[i], y: imagePoints[i + 1] });
      clamped.push(c.x, c.y);
    }
    const simplified = simplifyPolygon(clamped, 1.0, 1000);
    const valid = validatePolygonPoints(simplified);
    if (!valid) return;
    const def = resolveDefaultLabel(labelMasters ?? [], activeLabelId);
    if (!def) return;
    onLabelAdd?.({
      id: `tmp-${Date.now()}`,
      frameNo: 0,
      classId: def.labelId,
      className: def.name,
      source: 'MANUAL',
      shape: { type: 'POLYGON', points: valid },
    });
  }

  // === SAM2 분할(SAM_SEGMENT) ===
  // 응답 폴리곤([[x,y],...] image px)을 기존 폴리곤 적용 흐름으로 추가.
  // mock=true 면 자동 적용 차단 + 경고 콜백, score 낮으면 안내 콜백.
  function applySegmentResult(res: Sam2SegmentResponse | null) {
    if (!res) return; // 폐기(진행 중 무시 / 프레임 전환 stale)
    if (res.mock) {
      onMockWarning?.(res);
      return; // 자동 적용 차단 — 사용자 확인 후만 적용
    }
    if (res.score < SAM_LOW_CONFIDENCE_THRESHOLD) {
      onLowConfidence?.(res);
      return;
    }
    const flat: number[] = [];
    for (const pair of res.polygon) {
      if (pair.length >= 2) flat.push(pair[0], pair[1]);
    }
    // clampToImage + validatePolygonPoints + simplify — 기존 폴리곤 적용 흐름과 동일.
    commitImagePolygon(flat);
  }

  function handleSegmentClick() {
    if (!segment) return; // 미주입 — 안전 무시
    const cp = pointerCanvas();
    if (!cp) return;
    const img = clampToImage(geometry, translateFromCanvas(geometry, cp.x, cp.y));
    void segment({ points: [[img.x, img.y]] }).then(applySegmentResult);
  }

  function handleSegmentBox(start: Point, end: Point) {
    if (!segment) return;
    const a = clampToImage(geometry, translateFromCanvas(geometry, start.x, start.y));
    const b = clampToImage(geometry, translateFromCanvas(geometry, end.x, end.y));
    void segment({ box: [a.x, a.y, b.x, b.y] }).then(applySegmentResult);
  }

  // === BBox 핸들러 (전체 캔버스 영역에 invisible Rect 캡처)
  const captureRect =
    activeTool === ToolTypeEnum.BBOX ||
    activeTool === ToolTypeEnum.POLYGON ||
    activeTool === ToolTypeEnum.SAM_SEGMENT ? (
      <Rect
        x={0}
        y={0}
        width={geometry.canvas.width}
        height={geometry.canvas.height}
        fill="rgba(0,0,0,0.001)"
        onMouseDown={() => {
          if (activeTool === ToolTypeEnum.SAM_SEGMENT) {
            const p = pointerCanvas();
            if (!p) return;
            setSegDraft({ start: p, current: p });
            return;
          }
          if (activeTool !== ToolTypeEnum.BBOX) return;
          const p = pointerCanvas();
          if (!p) return;
          setBboxDraft({ start: p, current: p });
        }}
        onMouseMove={() => {
          if (activeTool === ToolTypeEnum.SAM_SEGMENT) {
            if (!segDraft) return;
            const p = pointerCanvas();
            if (!p) return;
            setSegDraft({ ...segDraft, current: p });
            return;
          }
          if (activeTool !== ToolTypeEnum.BBOX || !bboxDraft) return;
          const p = pointerCanvas();
          if (!p) return;
          setBboxDraft({ ...bboxDraft, current: p });
        }}
        onMouseUp={() => {
          if (activeTool === ToolTypeEnum.SAM_SEGMENT) {
            if (!segDraft) return;
            const p = pointerCanvas() ?? segDraft.current;
            const moved = Math.hypot(p.x - segDraft.start.x, p.y - segDraft.start.y);
            // 유의미한 드래그면 박스 프롬프트, 아니면 무시(클릭은 onClick 에서 포인트 처리).
            if (moved >= 3) {
              handleSegmentBox(segDraft.start, p);
              segSuppressClickRef.current = true;
            }
            setSegDraft(null);
            return;
          }
          if (activeTool !== ToolTypeEnum.BBOX || !bboxDraft) return;
          const { start, current } = bboxDraft;
          commitBbox(start, current);
          setBboxDraft(null);
        }}
        onClick={() => {
          if (activeTool === ToolTypeEnum.SAM_SEGMENT) {
            // 직전 드래그(박스)로 처리된 클릭이면 무시.
            if (segSuppressClickRef.current) {
              segSuppressClickRef.current = false;
              return;
            }
            handleSegmentClick();
            return;
          }
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
      {segDraft && (
        <Rect
          x={Math.min(segDraft.start.x, segDraft.current.x)}
          y={Math.min(segDraft.start.y, segDraft.current.y)}
          width={Math.abs(segDraft.current.x - segDraft.start.x)}
          height={Math.abs(segDraft.current.y - segDraft.start.y)}
          stroke="#7E57C2"
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
