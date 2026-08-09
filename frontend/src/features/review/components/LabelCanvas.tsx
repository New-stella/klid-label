// SCR-REVIEW-002 Phase 3 — 라벨 캔버스 (읽기 전용).
//
// Konva Stage 에 프레임 이미지 + bbox/polygon 라벨을 카테고리별 색상으로 오버레이한다.
// 마우스 호버 시 라벨명 칩 (`사람 #1`) 을 HTML overlay 로 표시.
//
// 보안:
// - 이미지는 srcSn 으로 useImageBlob 훅이 axios(Bearer 자동 첨부) 로 BE 인증 호출 →
//   blob URL 발급. <img src> 직접 사용 시 401 (인증 헤더 누락) 및 prefix 누락(404) 위험 회피.
// - 라벨명은 JSX 텍스트로만 출력 (자동 이스케이프, dangerouslySetInnerHTML 금지).
//
// 레이어 분리:
// - Layer 1 (listening=false): 이미지 — 라벨 hover 시 재렌더 회피.
// - Layer 2 (interactive): 라벨 shapes.

import { useEffect, useMemo, useRef, useState } from 'react';
import { Circle, Image as KonvaImage, Layer, Line, Rect, Stage } from 'react-konva';

import { Spinner } from '@/components/common/Spinner';
import { useImageBlob } from '@/features/label/hooks/useImageBlob';
import { useLabelMasters } from '@/features/label/hooks/useLabelMasters';
import type { LabelMaster } from '@/features/label/api/labelMaster';
import { COCO_SKELETON } from '@/features/label/types';
import { visibilityStyle } from '@/features/label/canvas/utils/keypointHelpers';

import type { FrameDetail, LabelItem } from '../types';
import { useReviewSelectionStore } from '../store/useReviewSelectionStore';
import { flattenPoints, getFitScale, pointsToBBox } from '../utils/coordinates';
import { reviewLabelColor, withAlpha } from '../utils/labelColor';

interface LabelCanvasProps {
  frame: FrameDetail | null;
  /**
   * 프레임 목록 조회 진행 중 여부. true 면 캔버스 중앙 스피너를 노출하고 "프레임이 없습니다"
   * 빈 상태 문구를 숨긴다(로딩=빈 상태 오인 방지). 상위(ReviewPage)가 useReviewFrames 의
   * isLoading 을 배선한다.
   */
  loading?: boolean;
}

/**
 * HTMLImageElement 비동기 로더 훅 — use-image 미설치 대체.
 * 빈 URL/로드 실패 시 null 반환.
 */
function useImageElement(src: string | undefined): HTMLImageElement | null {
  const [img, setImg] = useState<HTMLImageElement | null>(null);

  useEffect(() => {
    if (!src) {
      setImg(null);
      return;
    }
    let cancelled = false;
    const el = new Image();
    el.crossOrigin = 'anonymous';
    el.onload = () => {
      if (!cancelled) setImg(el);
    };
    el.onerror = () => {
      if (!cancelled) setImg(null);
    };
    el.src = src;
    return () => {
      cancelled = true;
      el.onload = null;
      el.onerror = null;
    };
  }, [src]);

  return img;
}

/**
 * 라벨 카테고리별 번호 매핑 — `사람 #1`, `사람 #2` 형태로 표시하기 위함.
 * id 가 아닌 카테고리 내 순번을 매긴다.
 *
 * BE 응답에서 `id` 가 null/undefined 로 내려오는 데이터 정합 이슈가 있어도 안전하게 동작하도록
 * 유효한 숫자 id 만 map 에 등록한다. null id 를 키로 넣으면 `map.get(id)` 가 오동작하거나
 * 서로 다른 라벨이 같은 키(null)로 덮어써지는 문제가 생긴다.
 */
function buildLabelIndexMap(labels: LabelItem[]): Map<number, number> {
  const counts: Record<string, number> = {};
  const map = new Map<number, number>();
  for (const l of labels) {
    counts[l.label] = (counts[l.label] ?? 0) + 1;
    if (typeof l.id === 'number' && Number.isFinite(l.id)) {
      map.set(l.id, counts[l.label]);
    }
  }
  return map;
}

/** 카테고리 순번을 `사람 #2` 형태의 접미사로 변환. 순번이 없으면(id null 등) "#" 생략. */
function labelIndexSuffix(indexMap: Map<number, number>, id: number | null | undefined): string {
  if (typeof id !== 'number' || !Number.isFinite(id)) return '';
  const idx = indexMap.get(id);
  return idx !== undefined ? ` #${idx}` : '';
}

interface ShapeProps {
  label: LabelItem;
  isHover: boolean;
  isSelected: boolean;
  scale: number;
  /** 라벨 마스터 목록 — 색상 판정의 단일 진실원(`getLabelDisplayColor` 2순위 lookup). */
  labelMasters: ReadonlyArray<LabelMaster> | undefined;
  onHoverIn: (id: number) => void;
  onHoverOut: () => void;
  onClick: (id: number) => void;
}

function strokeWidthFor(isSelected: boolean, isHover: boolean): number {
  if (isSelected) return 4;
  if (isHover) return 3;
  return 1.5;
}

function BBoxShape({
  label,
  isHover,
  isSelected,
  scale,
  labelMasters,
  onHoverIn,
  onHoverOut,
  onClick,
}: ShapeProps) {
  const box = useMemo(() => pointsToBBox(label.points), [label.points]);
  const stroke = reviewLabelColor(label, labelMasters);
  // strokeWidth 는 화면상 두께 유지를 위해 1/scale 보정.
  const baseWidth = strokeWidthFor(isSelected, isHover);
  const dash = isSelected ? [8, 4] : undefined;
  return (
    <Rect
      x={box.x}
      y={box.y}
      width={box.width}
      height={box.height}
      stroke={stroke}
      strokeWidth={baseWidth / Math.max(scale, 0.01)}
      dash={dash ? dash.map((d) => d / Math.max(scale, 0.01)) : undefined}
      fill={isSelected || isHover ? withAlpha(stroke, 0.2) : 'transparent'}
      perfectDrawEnabled={false}
      onMouseEnter={() => onHoverIn(label.id)}
      onMouseLeave={onHoverOut}
      onClick={() => onClick(label.id)}
      onTap={() => onClick(label.id)}
    />
  );
}

function PolygonShape({
  label,
  isHover,
  isSelected,
  scale,
  labelMasters,
  onHoverIn,
  onHoverOut,
  onClick,
}: ShapeProps) {
  const points = useMemo(() => flattenPoints(label.points), [label.points]);
  const stroke = reviewLabelColor(label, labelMasters);
  const baseWidth = strokeWidthFor(isSelected, isHover);
  const dash = isSelected ? [8, 4] : undefined;
  return (
    <Line
      points={points}
      stroke={stroke}
      strokeWidth={baseWidth / Math.max(scale, 0.01)}
      dash={dash ? dash.map((d) => d / Math.max(scale, 0.01)) : undefined}
      closed
      fill={isSelected || isHover ? withAlpha(stroke, 0.2) : withAlpha(stroke, 0.08)}
      perfectDrawEnabled={false}
      onMouseEnter={() => onHoverIn(label.id)}
      onMouseLeave={onHoverOut}
      onClick={() => onClick(label.id)}
      onTap={() => onClick(label.id)}
    />
  );
}

/**
 * 키포인트(COCO-17 포즈) 표시 전용 렌더.
 *
 * 사양: "스켈레톤(키포인트) 라벨이 있으면 그리기·이동 없이 표시 전용으로 함께 오버레이한다."
 * 라벨링 캔버스(`LabelsLayer`)의 키포인트 렌더와 같은 규칙을 쓰되 **편집(드래그·가시성 순환)은
 * 두지 않는다** — 검수 캔버스는 읽기 전용이다.
 *
 * 좌표는 이미지 픽셀 그대로다(부모 Layer 가 offset/scale 을 적용하므로 별도 변환이 필요 없다).
 * `points` 는 BE SKELETON 포맷인 17×[x, y, v] 삼중값이다.
 */
function KeypointShape({
  label,
  isHover,
  isSelected,
  scale,
  labelMasters,
  onHoverIn,
  onHoverOut,
  onClick,
}: ShapeProps) {
  const stroke = reviewLabelColor(label, labelMasters);
  const inv = 1 / Math.max(scale, 0.01);
  const baseWidth = strokeWidthFor(isSelected, isHover) * inv;
  // 삼중값이 아닌 행(레거시/손상)은 v=0(미표기)으로 간주해 조용히 흐리게 둔다 — 예외를 던지면
  // 프레임 전체 오버레이가 사라진다.
  const keypoints = label.points.map((p) => ({
    x: Number(p?.[0]) || 0,
    y: Number(p?.[1]) || 0,
    v: p?.[2] === 2 ? 2 : p?.[2] === 1 ? 1 : 0,
  }));

  return (
    <>
      {COCO_SKELETON.map((edge, i) => {
        const a = keypoints[edge[0] - 1];
        const b = keypoints[edge[1] - 1];
        if (!a || !b || a.v === 0 || b.v === 0) return null;
        return (
          <Line
            key={`kpt-edge-${i}`}
            points={[a.x, a.y, b.x, b.y]}
            stroke={stroke}
            strokeWidth={baseWidth}
            listening={false}
            perfectDrawEnabled={false}
          />
        );
      })}
      {keypoints.map((kp, i) => {
        const vs = visibilityStyle(kp.v);
        return (
          <Circle
            key={`kpt-${i}`}
            x={kp.x}
            y={kp.y}
            radius={5 * inv}
            fill={stroke}
            stroke="#FFFFFF"
            strokeWidth={inv}
            opacity={vs.opacity}
            dash={vs.dash?.map((d) => d * inv)}
            perfectDrawEnabled={false}
            onMouseEnter={() => onHoverIn(label.id)}
            onMouseLeave={onHoverOut}
            onClick={() => onClick(label.id)}
            onTap={() => onClick(label.id)}
          />
        );
      })}
    </>
  );
}

/**
 * 라벨 1건 → 적절한 shape 컴포넌트로 라우팅.
 * SEGMENT/TRACK 은 우선 POLYGON 형태로 표현 (RLE 디코딩은 다음 Phase).
 *
 * ⚠ `SKELETON`(키포인트) 분기를 지우면 그 라벨이 `default` 로 빠져 **화면에서 통째로 사라진다**
 *   — 실제로 그렇게 새어 나갔던 결함이다. `LabelType` 유니온이 이 분기의 누락을 막아 준다.
 */
function LabelShape(props: ShapeProps) {
  const { label } = props;
  switch (label.lblTypeCd) {
    case 'BBOX':
      return <BBoxShape {...props} />;
    case 'SKELETON':
      return <KeypointShape {...props} />;
    case 'POLYGON':
    case 'SEGMENT':
    case 'TRACK':
      return <PolygonShape {...props} />;
    default:
      return null;
  }
}

/**
 * SCR-REVIEW-002 검수 캔버스 (읽기 전용).
 *
 * - aspect-fit + 중앙 정렬로 이미지 배치.
 * - 컨테이너 리사이즈 시 ResizeObserver 로 동기화.
 * - 라벨은 카테고리별 stroke 색상으로 오버레이.
 * - hover 시 strokeWidth 증가 + 라벨명 칩 표시.
 */
export function LabelCanvas({ frame, loading = false }: LabelCanvasProps) {
  const containerRef = useRef<HTMLDivElement>(null);
  const [size, setSize] = useState({ width: 0, height: 0 });
  const [pointerPos, setPointerPos] = useState<{ x: number; y: number } | null>(null);

  // Zustand selector 패턴 — 필요한 값만 구독.
  const selectedLabelId = useReviewSelectionStore((s) => s.selectedLabelId);
  const hoverId = useReviewSelectionStore((s) => s.hoverLabelId);
  const setSelected = useReviewSelectionStore((s) => s.setSelected);
  const setHover = useReviewSelectionStore((s) => s.setHover);
  // Phase 6 — 이슈 추가 모드: 캔버스 cursor 변경 + 라벨 클릭 시 pending 이슈 누적.
  const issueMode = useReviewSelectionStore((s) => s.issueMode);
  const addPendingIssue = useReviewSelectionStore((s) => s.addPendingIssue);

  useEffect(() => {
    const el = containerRef.current;
    if (!el) return;
    // 초기 1회 측정.
    setSize({ width: el.clientWidth, height: el.clientHeight });
    if (typeof ResizeObserver === 'undefined') return;
    const ro = new ResizeObserver((entries) => {
      const entry = entries[0];
      if (!entry) return;
      const { width, height } = entry.contentRect;
      setSize({ width, height });
    });
    ro.observe(el);
    return () => ro.disconnect();
  }, []);

  // useImageBlob 가 axios(Bearer) 로 BE 인증 fetch → blob URL 발급. 직접 <img src=imageUrl> 호출은
  // 인증 헤더 누락(401) + context-path(/api) 미적용 위험이 있어 사용하지 않는다.
  // (REVIEWER 도 기본 DEID 이미지 사용 — 필요 시 추후 raw=true 옵션 추가)
  // 라벨 마스터 — 색상 판정의 단일 진실원(하드코딩 색상표 폐지). 라벨링 화면과 같은 쿼리 키를
  // 공유하므로(staleTime 5분) 추가 요청은 사실상 발생하지 않는다.
  const { data: labelMasters } = useLabelMasters();

  const { url: imageBlobUrl, loading: imageLoading } = useImageBlob(frame?.srcSn);
  const img = useImageElement(imageBlobUrl ?? undefined);
  const imgW = img?.naturalWidth ?? 0;
  const imgH = img?.naturalHeight ?? 0;

  const { scale, offsetX, offsetY } = useMemo(
    () => getFitScale(imgW, imgH, size.width, size.height),
    [imgW, imgH, size.width, size.height],
  );

  const labels = frame?.labels ?? [];
  const indexMap = useMemo(() => buildLabelIndexMap(labels), [labels]);

  const hoverLabel = useMemo(
    () => (hoverId == null ? null : labels.find((l) => l.id === hoverId) ?? null),
    [hoverId, labels],
  );

  // 배경(bg-gray-200)은 UI 크롬이 아니라 영상 프레임을 얹는 미디어 매트다. 앱 전역이 라이트로
  // 통일됐지만 이 한 곳만 중립 회색을 유지한다 — 순백 매트는 어두운 CCTV 프레임과 대비가 극심해
  // 눈부심이 생긴다. 라벨링 캔버스와 같은 값을 쓴다(두 화면의 매트 색 일치가 요구사항).
  return (
    <div
      ref={containerRef}
      className={`relative h-full w-full overflow-hidden bg-gray-200 ${
        issueMode ? 'cursor-crosshair' : ''
      }`}
      data-testid="review-label-canvas"
      data-issue-mode={issueMode ? 'true' : 'false'}
      onMouseMove={(e) => {
        const rect = containerRef.current?.getBoundingClientRect();
        if (!rect) return;
        setPointerPos({ x: e.clientX - rect.left, y: e.clientY - rect.top });
      }}
      onMouseLeave={() => {
        setPointerPos(null);
        setHover(null);
      }}
    >
      {/* 프레임 목록 로딩 중 중앙 스피너 — 로드 전 "프레임이 없습니다" 오표시를 대체한다. */}
      {loading && (
        <div
          data-testid="review-frames-loading"
          role="status"
          className="flex h-full w-full flex-col items-center justify-center gap-3 text-gray-700"
        >
          <Spinner label="프레임 로딩" />
          <p className="text-body-md">프레임 로드 중...</p>
        </div>
      )}

      {!loading && !frame && (
        <div
          className="flex h-full w-full items-center justify-center text-body-md text-gray-700"
          data-testid="review-label-canvas-empty"
        >
          프레임이 없습니다
        </div>
      )}

      {/* 프레임 이미지 blob 로드 중 중앙 스피너 오버레이 (라벨링 CanvasShell 과 대칭).
          로드 완료/실패 시 useImageBlob 이 loading=false 로 전이해 자동 해제된다. */}
      {frame && imageLoading && (
        <div
          data-testid="review-canvas-image-spinner"
          role="status"
          className="absolute inset-0 z-10 flex items-center justify-center bg-black/20"
        >
          <Spinner size="lg" label="이미지 로딩 중" />
        </div>
      )}

      {/* 이미지 비동기 로드 전(imgW/imgH=0)에는 렌더하지 않는다 — getFitScale 이 scale≈0 을
          반환해 strokeWidth(=base/scale)가 비정상적으로 커지는 문제를 방지. */}
      {frame && size.width > 0 && size.height > 0 && imgW > 0 && imgH > 0 && (
        <Stage width={size.width} height={size.height}>
          {/* Layer 1 — 이미지 (정적, listening=false 로 hover 이벤트 격리) */}
          <Layer x={offsetX} y={offsetY} scaleX={scale} scaleY={scale} listening={false}>
            {img && <KonvaImage image={img} />}
          </Layer>
          {/* Layer 2 — 라벨 shapes (interactive) */}
          <Layer x={offsetX} y={offsetY} scaleX={scale} scaleY={scale}>
            {labels.map((label, i) => (
              <LabelShape
                // 동일 id 라벨이 BE 응답에 섞여 들어오는 경우(데이터 정합 이슈)에도
                // React key 충돌(react-konva 중복 key warning)을 방지하기 위해 인덱스 폴백 포함.
                key={`${label.id}-${label.lblTypeCd}-${i}`}
                label={label}
                isHover={hoverId === label.id}
                isSelected={selectedLabelId === label.id}
                scale={scale}
                labelMasters={labelMasters}
                onHoverIn={(id) => {
                  if (selectedLabelId === id) return;
                  setHover(id);
                }}
                onHoverOut={() => setHover(null)}
                onClick={(id) => {
                  // Phase 6 — 이슈 추가 모드: 클릭한 라벨에 대해 pending 이슈 카드를 누적.
                  // 카드 텍스트는 사용자가 ReviewMemoPanel 에서 수정 가능.
                  if (issueMode) {
                    const labelMeta = labels.find((l) => l.id === id);
                    const placeholder = labelMeta
                      ? `${labelMeta.label}${labelIndexSuffix(indexMap, id)} 에 대한 이슈를 입력하세요`
                      : '이슈를 입력하세요';
                    addPendingIssue(placeholder, id);
                    return;
                  }
                  setSelected(id);
                }}
              />
            ))}
          </Layer>
        </Stage>
      )}

      {/* HTML 칩 — 라벨명 표시 (Konva Text 가 아닌 HTML 로 폰트 일관성 확보) */}
      {hoverLabel && pointerPos && (
        <div
          className="pointer-events-none absolute z-10 inline-flex items-center gap-1 rounded-md px-2 py-1 text-label font-medium text-white shadow-lg"
          style={{
            left: pointerPos.x + 12,
            top: pointerPos.y + 12,
            backgroundColor: reviewLabelColor(hoverLabel, labelMasters),
          }}
          data-testid="review-label-chip"
        >
          {hoverLabel.label}
          {labelIndexSuffix(indexMap, hoverLabel.id)}
        </div>
      )}
    </div>
  );
}
