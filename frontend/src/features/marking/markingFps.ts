import { MARKING_FALLBACK_FPS } from '@/features/video/types';

/**
 * 마킹 frameIndex 산출의 <b>단일 진실원</b> (C-ISSUE-01 / DEV_FIX H10).
 *
 * <h3>왜 필요한가 (실측 결함)</h3>
 * FE 는 마킹 시점의 frameIndex 를 `round(currentTime × 30)` 으로 <b>30fps 하드코딩</b>해 만들었는데,
 * BE 의 수동 마킹 상한은 영상의 <b>실 fps</b>(LS_DATA_META `video.fps`, 미상 시 30)로 계산한다.
 * 25fps·60초 영상이면 BE 상한은 1500 인데 FE 는 55초 지점에 1650 을 만들어 보내 400 으로 거부됐다
 * (영상 뒤 16.7% 구간을 마킹할 수 없음). 정합 방법은 "서버가 fps 를 내려주고 FE 가 그 값을 쓴다" 이며,
 * 이 모듈이 그 값 해석과 frameIndex 공식을 한 곳에 모은다.
 *
 * <p>반올림 규칙(`Math.round`)은 BE `MarkingService.generateAutoMarks` / `manualFrameIndexLimit` 의
 * `Math.round(durationSec × fps)` 와 동일하다 — 양쪽이 같은 fps + 같은 반올림을 쓰므로 경계가 어긋나지 않는다.
 */

/**
 * 서버가 내려준 영상 fps 를 마킹 계산용 값으로 정규화한다.
 * 값이 없거나 비정상(0 이하·NaN)이면 BE 와 동일한 폴백(30)을 쓴다 — 양쪽 폴백이 갈리면 다시 어긋난다.
 */
export function resolveMarkingFps(fps?: number | null): number {
  if (typeof fps === 'number' && Number.isFinite(fps) && fps > 0) {
    return fps;
  }
  return MARKING_FALLBACK_FPS;
}

/** 재생 위치(초) → frameIndex. BE 와 동일한 fps·반올림 규칙을 쓴다. */
export function markingFrameIndex(currentTimeSec: number, fps: number): number {
  return Math.round(currentTimeSec * fps);
}
