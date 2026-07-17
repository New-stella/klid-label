import { COCO_SKELETON, KEYPOINT_NAMES } from '../types';
import {
  KEYPOINT_GUIDE_LAYOUT,
  KEYPOINT_NAMES_KO,
  KEYPOINT_SUBJECT_ORIENTATION_CAPTION,
} from '../canvas/utils/keypointHelpers';

interface KeypointGuideProps {
  /**
   * 현재 배치할 관절의 0-based 인덱스(0~16). 배치 중이 아니거나(툴 비활성)
   * 17점 완료 시에는 null 을 전달하면 가이드가 숨겨진다.
   */
  placingIndex: number | null;
}

// 표시 색상 — done(완료)/active(현재)/pending(미배치).
const COLOR_DONE = '#26A69A';
const COLOR_ACTIVE = '#F59E0B';
const COLOR_PENDING = '#94A3B8';
const COLOR_EDGE = '#CBD5E1';

/**
 * 키포인트(COCO-17) 순차 배치 중 "지금 어느 관절을 찍는지" 안내하는 인체 다이어그램 가이드.
 * - 표준 정면 포즈로 17관절을 배치({@link KEYPOINT_GUIDE_LAYOUT}) + 스켈레톤 선({@link COCO_SKELETON}).
 * - 현재 관절(placingIndex): 강조(확대·색상·점멸), 배치 완료: 완료색, 미배치: 흐림.
 * - 하단에 한글 관절명 + 진행률 "N/17" + 인물 기준 좌우 안내({@link KEYPOINT_SUBJECT_ORIENTATION_CAPTION}).
 *
 * 렌더 위치: 좌측 '라벨' 패널 내부(in-flow 블록). 과거 캔버스 절대위치 오버레이는 좁은 폭에서
 * 뷰포트 밖으로 넘쳐 잘리는 문제로 폐기 — 항상-보이는 좌측 패널로 이동해 화면 크기와 무관하게 노출.
 * 표시 전용(사용자 입력·네트워크 없음) — 한글명·캡션은 하드코딩 상수 텍스트 바인딩(XSS 안전).
 */
export function KeypointGuide({ placingIndex }: KeypointGuideProps) {
  // 배치 미진행 / 17점 완료(범위 밖) → 숨김.
  if (
    placingIndex === null ||
    placingIndex < 0 ||
    placingIndex >= KEYPOINT_NAMES_KO.length
  ) {
    return null;
  }

  const total = KEYPOINT_NAMES_KO.length;
  const currentName = KEYPOINT_NAMES_KO[placingIndex];
  const progress = `${placingIndex + 1}/${total}`;

  return (
    <div
      role="group"
      aria-label="스켈레톤 배치 가이드"
      className="mt-2 w-full rounded-md bg-gray-900/60 p-2 text-white"
    >
      <div className="mb-1 text-[10px] font-semibold uppercase tracking-wide text-gray-400">
        스켈레톤 가이드
      </div>
      <svg
        viewBox="0 0 100 105"
        className="mx-auto block h-32 w-24"
        role="img"
        aria-label={`인체 다이어그램 — 다음 관절: ${currentName}`}
      >
        {/* 스켈레톤 선 (COCO_SKELETON 은 1-indexed) */}
        {COCO_SKELETON.map((edge) => {
          const a = edge[0] - 1;
          const b = edge[1] - 1;
          const pa = KEYPOINT_GUIDE_LAYOUT[a];
          const pb = KEYPOINT_GUIDE_LAYOUT[b];
          if (!pa || !pb) return null;
          return (
            <line
              key={`edge-${KEYPOINT_NAMES[a]}-${KEYPOINT_NAMES[b]}`}
              data-testid={`kpt-edge-${a}-${b}`}
              x1={pa.x}
              y1={pa.y}
              x2={pb.x}
              y2={pb.y}
              stroke={COLOR_EDGE}
              strokeWidth={1.5}
              strokeOpacity={0.5}
            />
          );
        })}
        {/* 17 관절 노드 */}
        {KEYPOINT_GUIDE_LAYOUT.map((p, i) => {
          const isActive = i === placingIndex;
          const isDone = i < placingIndex;
          const fill = isActive ? COLOR_ACTIVE : isDone ? COLOR_DONE : COLOR_PENDING;
          return (
            <circle
              key={`node-${KEYPOINT_NAMES[i]}`}
              data-testid={`kpt-node-${i}`}
              data-active={String(isActive)}
              cx={p.x}
              cy={p.y}
              r={isActive ? 5 : 3}
              fill={fill}
              fillOpacity={isActive ? 1 : isDone ? 0.9 : 0.35}
              className={isActive ? 'animate-pulse' : undefined}
            />
          );
        })}
      </svg>
      <p
        data-testid="kpt-guide-caption"
        aria-live="polite"
        className="mt-1 text-center text-xs font-medium"
      >
        {currentName} · {progress}
      </p>
      <p
        data-testid="kpt-guide-orientation"
        className="mt-0.5 text-center text-[10px] leading-tight text-white/70"
      >
        {KEYPOINT_SUBJECT_ORIENTATION_CAPTION}
      </p>
    </div>
  );
}
