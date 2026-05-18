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
import { Image as KonvaImage, Layer, Line, Rect, Stage } from 'react-konva';

import { useImageBlob } from '@/features/label/hooks/useImageBlob';

import type { FrameDetail, LabelItem } from '../types';
import { useReviewSelectionStore } from '../store/useReviewSelectionStore';
import { flattenPoints, getFitScale, pointsToBBox } from '../utils/coordinates';
import { colorForLabel, fillForLabel } from '../utils/labelColor';

interface LabelCanvasProps {
  frame: FrameDetail | null;
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
 */
function buildLabelIndexMap(labels: LabelItem[]): Map<number, number> {
  const counts: Record<string, number> = {};
  const map = new Map<number, number>();
  for (const l of labels) {
    counts[l.label] = (counts[l.label] ?? 0) + 1;
    map.set(l.id, counts[l.label]);
  }
  return map;
}

interface ShapeProps {
  label: LabelItem;
  isHover: boolean;
  isSelected: boolean;
  scale: number;
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
  onHoverIn,
  onHoverOut,
  onClick,
}: ShapeProps) {
  const box = useMemo(() => pointsToBBox(label.points), [label.points]);
  const stroke = colorForLabel(label.label);
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
      fill={isSelected || isHover ? fillForLabel(label.label, 0.2) : 'transparent'}
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
  onHoverIn,
  onHoverOut,
  onClick,
}: ShapeProps) {
  const points = useMemo(() => flattenPoints(label.points), [label.points]);
  const stroke = colorForLabel(label.label);
  const baseWidth = strokeWidthFor(isSelected, isHover);
  const dash = isSelected ? [8, 4] : undefined;
  return (
    <Line
      points={points}
      stroke={stroke}
      strokeWidth={baseWidth / Math.max(scale, 0.01)}
      dash={dash ? dash.map((d) => d / Math.max(scale, 0.01)) : undefined}
      closed
      fill={
        isSelected || isHover
          ? fillForLabel(label.label, 0.2)
          : fillForLabel(label.label, 0.08)
      }
      perfectDrawEnabled={false}
      onMouseEnter={() => onHoverIn(label.id)}
      onMouseLeave={onHoverOut}
      onClick={() => onClick(label.id)}
      onTap={() => onClick(label.id)}
    />
  );
}

/**
 * 라벨 1건 → 적절한 shape 컴포넌트로 라우팅.
 * SEGMENT/TRACK 은 우선 POLYGON 형태로 표현 (RLE 디코딩은 다음 Phase).
 */
function LabelShape(props: ShapeProps) {
  const { label } = props;
  switch (label.lblTypeCd) {
    case 'BBOX':
      return <BBoxShape {...props} />;
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
export function LabelCanvas({ frame }: LabelCanvasProps) {
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
  const { url: imageBlobUrl } = useImageBlob(frame?.srcSn);
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

  return (
    <div
      ref={containerRef}
      className={`relative h-full w-full overflow-hidden bg-gray-900 ${
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
      {!frame && (
        <div
          className="flex h-full w-full items-center justify-center text-sm text-gray-500"
          data-testid="review-label-canvas-empty"
        >
          프레임이 없습니다
        </div>
      )}

      {frame && size.width > 0 && size.height > 0 && (
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
                    const idx = indexMap.get(id) ?? 0;
                    const placeholder = labelMeta
                      ? `${labelMeta.label} #${idx} 에 대한 이슈를 입력하세요`
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
          className="pointer-events-none absolute z-10 inline-flex items-center gap-1 rounded-md px-2 py-1 text-xs font-medium text-white shadow-lg"
          style={{
            left: pointerPos.x + 12,
            top: pointerPos.y + 12,
            backgroundColor: colorForLabel(hoverLabel.label),
          }}
          data-testid="review-label-chip"
        >
          {hoverLabel.label} #{indexMap.get(hoverLabel.id) ?? 0}
        </div>
      )}
    </div>
  );
}
