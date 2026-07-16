import {
  forwardRef,
  useEffect,
  useImperativeHandle,
  useRef,
  useState,
  type RefObject,
} from 'react';
import { Circle, Line, Rect } from 'react-konva';
import type Konva from 'konva';

import { useLabelStore } from '@/stores/useLabelStore';

import { useLabelMasters } from '../../hooks/useLabelMasters';
import type { Sam2SegmentRequest, Sam2SegmentResponse } from '../../api';
import type { Label, ToolType } from '../../types';
import { COCO_SKELETON, KEYPOINT_NAMES, ToolType as ToolTypeEnum } from '../../types';
import { isValidBox, normalizeBox } from '../utils/canvasGeometry';
import {
  clampToImage,
  translateFromCanvas,
  translateToCanvas,
  type Geometry,
  type Point,
} from '../utils/coordinateTransformer';
import { skeletonEdgeToCanvasLine } from '../utils/keypointHelpers';
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
  /** 폴리곤 커밋이 라벨 마스터 미로딩 등으로 실패했을 때 사용자 안내 메시지 전달. */
  onCommitError?: (message: string) => void;
  /**
   * KEYPOINT 순차 배치 중 "지금 찍을 관절"의 0-based 인덱스(0~16)를 상위에 보고.
   * 배치 미진행(툴 비활성) 또는 17점 완료 시 null. 캔버스 밖 인체 다이어그램 가이드 연동용.
   */
  onKeypointPlacingChange?: (placingIndex: number | null) => void;
}

interface BboxDraft {
  start: Point; // canvas 좌표
  current: Point;
}

/**
 * OverlayLayer 가 상위(CanvasShell→LabelingPage)에 노출하는 명령 핸들.
 * 폴리곤 편집 state 가 OverlayLayer 내부에 캡슐화돼 있어, 키보드 단축키(F/Q)가
 * 마우스 클릭과 동일한 폴리곤 로직을 호출할 수 있도록 imperative handle 로 중계한다.
 */
export interface OverlayLayerHandle {
  /** F — 현재 포인터 위치를 폴리곤 점으로 추가(마우스 클릭과 동일 경로). POLYGON 도구 아니면 no-op. */
  addPointAtPointer: () => void;
  /** Q — 진행 중 폴리곤을 커밋. 점이 부족하면 no-op(draft 유지 — 오조작 방지). */
  completePolygon: () => void;
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

export const OverlayLayer = forwardRef<OverlayLayerHandle, OverlayLayerProps>(function OverlayLayer(
  {
    geometry,
    activeTool,
    onLabelAdd,
    stageRef,
    segment,
    onMockWarning,
    onLowConfidence,
    onCommitError,
    onKeypointPlacingChange,
  }: OverlayLayerProps,
  ref,
) {
  const [bboxDraft, setBboxDraft] = useState<BboxDraft | null>(null);
  // SAM_SEGMENT 박스 드래그 draft (canvas 좌표).
  const [segDraft, setSegDraft] = useState<BboxDraft | null>(null);
  const [polyPoints, setPolyPoints] = useState<number[]>([]);
  // KEYPOINT 순차 배치 draft — 배치된 관절(이미지 좌표) 0..17 개. 17개 채워지면 커밋.
  const [kptDraft, setKptDraft] = useState<{ x: number; y: number; v: number }[]>([]);

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
    // KEYPOINT 도구를 벗어나면 진행 중 배치 draft 폐기(부분 배치 조용한 소실 방지 겸 초기화).
    if (activeTool !== ToolTypeEnum.KEYPOINT) {
      setKptDraft([]);
    }
    // POLYGON 도구를 벗어나면 진행 중 폴리곤 draft 초기화(다른 도구로 전환 시 스테일 점 잔존 방지).
    if (activeTool !== ToolTypeEnum.POLYGON) {
      setPolyPoints([]);
    }
  }, [activeTool]);

  // 콜백을 ref 에 보관해 인라인 함수 전달에도 보고 effect 가 매 렌더 재실행되지 않도록 한다.
  const placingChangeRef = useRef(onKeypointPlacingChange);
  placingChangeRef.current = onKeypointPlacingChange;
  // 마지막으로 보고한 값 — 동일 값 중복 보고(불필요 setState) 억제. 초기값 null 로 시작.
  const lastPlacingRef = useRef<number | null>(null);

  // "지금 찍을 관절" 인덱스를 상위(다이어그램 가이드)로 보고. 배치 미진행/완료 시 null.
  useEffect(() => {
    const placing =
      activeTool === ToolTypeEnum.KEYPOINT && kptDraft.length < KEYPOINT_NAMES.length
        ? kptDraft.length
        : null;
    if (placing !== lastPlacingRef.current) {
      lastPlacingRef.current = placing;
      placingChangeRef.current?.(placing);
    }
  }, [activeTool, kptDraft.length]);

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

  /**
   * 폴리곤을 라벨로 커밋. 커밋에 성공하면 true, 검증 실패/라벨 마스터 미로딩 등으로
   * no-op 이면 false 를 반환한다. 호출처는 성공 시에만 그리던 점을 비워야 한다
   * (실패 시 점을 유지해 사용자가 그린 폴리곤이 조용히 사라지지 않도록).
   */
  function commitPolygon(points: number[]): boolean {
    const pts: number[] = [];
    for (let i = 0; i + 1 < points.length; i += 2) {
      const p = translateFromCanvas(geometry, points[i], points[i + 1]);
      const c = clampToImage(geometry, p);
      pts.push(c.x, c.y);
    }
    const valid = validatePolygonPoints(pts);
    if (!valid) return false;
    const def = resolveDefaultLabel(labelMasters ?? [], activeLabelId);
    if (!def) {
      // 라벨 마스터 없음 — 신규 라벨 생성 거부. 사용자에게 원인 안내(점은 호출처에서 유지).
      onCommitError?.('라벨 분류가 로딩되지 않아 폴리곤을 추가할 수 없습니다. 잠시 후 다시 시도하세요.');
      return false;
    }
    onLabelAdd?.({
      id: `tmp-${Date.now()}`,
      frameNo: 0,
      classId: def.labelId,
      className: def.name,
      source: 'MANUAL',
      shape: { type: 'POLYGON', points: valid },
    });
    return true;
  }

  /**
   * 진행 중 폴리곤에 canvas 좌표 1점을 추가한다. 시작점 근접 시 자동 닫힘 → 커밋 시도.
   * 마우스 클릭(onClick)과 키보드 F(addPointAtPointer)가 공유하는 단일 경로.
   */
  function addPolygonPointAt(cp: Point) {
    const next = [...polyPoints, cp.x, cp.y];
    const closed = closePolygonIfNear(next);
    if (closed.closed) {
      // 커밋 성공 시에만 점 비움 — 실패(라벨 마스터 미로딩 등)면 점 유지.
      if (commitPolygon(closed.points)) setPolyPoints([]);
    } else {
      setPolyPoints(next);
    }
  }

  /**
   * 진행 중 폴리곤 커밋 시도(점 3개 이상 + 검증 통과 시에만). 성공하면 draft 를 비우고 true 를 반환한다.
   * 점이 부족하거나 커밋 실패면 draft 를 건드리지 않고 false 를 반환한다.
   * 마우스 dblclick(finish)과 키보드 Q(completePolygon)가 공유한다.
   */
  function tryCommitPolygon(): boolean {
    if (polyPoints.length < 6) return false;
    if (commitPolygon(polyPoints)) {
      setPolyPoints([]);
      return true;
    }
    return false;
  }

  useImperativeHandle(ref, () => ({
    addPointAtPointer() {
      if (activeTool !== ToolTypeEnum.POLYGON) return; // 폴리곤 도구 아니면 무시
      const cp = pointerCanvas();
      if (!cp) return;
      addPolygonPointAt(cp);
    },
    completePolygon() {
      // 점 부족이면 tryCommitPolygon 이 no-op(draft 유지). 마우스 dblclick 과 달리 취소로 비우지 않음.
      tryCommitPolygon();
    },
  }));

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

  /**
   * 배치 완료된 17 관절(이미지 좌표 삼중값)을 KEYPOINT 라벨로 커밋.
   * 라벨 마스터 미로딩 시 no-op(false) + 사용자 안내. 성공 시 true.
   */
  function commitKeypoints(kps: { x: number; y: number; v: number }[]): boolean {
    const def = resolveDefaultLabel(labelMasters ?? [], activeLabelId);
    if (!def) {
      onCommitError?.('라벨 분류가 로딩되지 않아 키포인트를 추가할 수 없습니다. 잠시 후 다시 시도하세요.');
      return false;
    }
    onLabelAdd?.({
      id: `tmp-${Date.now()}`,
      frameNo: 0,
      classId: def.labelId,
      className: def.name,
      source: 'MANUAL',
      shape: { type: 'KEYPOINT', keypoints: kps },
    });
    return true;
  }

  // KEYPOINT: 캡처 Rect 클릭마다 현재 관절 1점 배치. 17점 채워지면 커밋.
  function handleKeypointClick() {
    if (kptDraft.length >= KEYPOINT_NAMES.length) return; // 이미 17점(커밋 대기) — 추가 무시
    const cp = pointerCanvas();
    if (!cp) return;
    const img = clampToImage(geometry, translateFromCanvas(geometry, cp.x, cp.y));
    // 기본 가시성 v=2(가시). 비가시/미표기 전환은 커밋 후 편집 단계에서 처리.
    const next = [...kptDraft, { x: img.x, y: img.y, v: 2 }];
    if (next.length >= KEYPOINT_NAMES.length) {
      // 커밋 성공 시에만 draft 비움 — 실패(라벨 마스터 미로딩)면 유지해 재시도 허용.
      if (commitKeypoints(next)) setKptDraft([]);
      else setKptDraft(next);
    } else {
      setKptDraft(next);
    }
  }

  // === SAM2 분할(SAM_SEGMENT) ===
  // 응답 폴리곤([[x,y],...] image px)을 기존 폴리곤 적용 흐름으로 추가.
  // 빈 폴리곤(모델 미로드/mock) 이면 자동 적용 차단 + 경고 콜백, score 낮으면 안내 콜백.
  function applySegmentResult(res: Sam2SegmentResponse | null) {
    if (!res) return; // 폐기(진행 중 무시 / 프레임 전환 stale)
    // mock(모델 미로드) 신호 = 빈 폴리곤. 저신뢰(score) 분기보다 먼저 판정해야
    // "낮은 신뢰도(0%)" 로 오분류되지 않고 올바른 안내(message)가 표시된다.
    if (res.polygon.length === 0) {
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
    activeTool === ToolTypeEnum.SAM_SEGMENT ||
    activeTool === ToolTypeEnum.KEYPOINT ? (
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
          if (activeTool === ToolTypeEnum.KEYPOINT) {
            handleKeypointClick();
            return;
          }
          if (activeTool !== ToolTypeEnum.POLYGON) return;
          const p = pointerCanvas();
          if (!p) return;
          addPolygonPointAt(p);
        }}
        onDblClick={() => {
          if (activeTool !== ToolTypeEnum.POLYGON) return;
          // 커밋 시도 후 점이 부족(폴리곤 불가)이면 그리기 취소로 간주해 draft 를 비운다.
          // (커밋 성공/실패는 tryCommitPolygon 내부에서 처리 — 실패 시 점 유지, 부족 시에만 여기서 취소)
          if (!tryCommitPolygon() && polyPoints.length < 6) setPolyPoints([]);
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
              const px = polyPoints[i];
              const py = polyPoints[i + 1];
              // 정점 좌표 기반 stable key — append-only 드래프트 정점이라 좌표가 정점 정체성을
              // 안정적으로 식별한다 (flat index 단독보다 재정렬/리렌더에 강함).
              dots.push(
                <Circle
                  key={`${px}:${py}`}
                  x={px}
                  y={py}
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
      {/* KEYPOINT 배치 draft — 부분 스켈레톤 Line + 배치된 관절 Circle + 다음 관절 가이드. */}
      {activeTool === ToolTypeEnum.KEYPOINT && kptDraft.length > 0 && (
        <>
          {COCO_SKELETON.map((edge, i) => {
            const line = skeletonEdgeToCanvasLine(geometry, kptDraft, edge);
            return line ? (
              <Line
                key={`kpt-draft-edge-${i}`}
                points={line}
                stroke="#26A69A"
                strokeWidth={2}
                listening={false}
              />
            ) : null;
          })}
          {kptDraft.map((kp, i) => {
            const c = translateToCanvas(geometry, kp.x, kp.y);
            return (
              <Circle
                key={`kpt-draft-${i}`}
                x={c.x}
                y={c.y}
                radius={4}
                fill="#26A69A"
                listening={false}
              />
            );
          })}
        </>
      )}
    </>
  );
});
