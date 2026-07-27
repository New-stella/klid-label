import { describe, expect, it } from 'vitest';

import { markingFrameIndex, resolveMarkingFps } from '../markingFps';

/**
 * C-ISSUE-01 / DEV_FIX(H10) — BE·FE frameIndex 기준 정합 회귀 테스트.
 *
 * BE 수동 마킹 상한(MarkingService.manualFrameIndexLimit):
 *   limit = round(durationSec × fps) + ceil(fps)   (frameIndex < limit 이어야 통과)
 * 이 테스트는 같은 공식을 명시해 두고, FE 가 만드는 frameIndex 가 그 상한 안에 들어오는지 확인한다.
 * FE 가 fps 를 하드코딩(30)하면 25fps 영상에서 상한을 넘겨 400 이 나던 실측 결함을 고정한다.
 */
const beManualFrameIndexLimit = (durationSec: number, fps: number) =>
  Math.round(durationSec * fps) + Math.ceil(fps);

describe('markingFps — BE·FE frameIndex 기준 정합', () => {
  it('서버가_내려준_fps를_사용하고_없을때만_BE와_동일한_폴백30을_쓴다', () => {
    expect(resolveMarkingFps(25)).toBe(25);
    expect(resolveMarkingFps(29.97)).toBe(29.97);
    // 미제공/비정상 → BE VideoFpsResolver.DEFAULT_FPS 와 동일한 30
    expect(resolveMarkingFps(undefined)).toBe(30);
    expect(resolveMarkingFps(null)).toBe(30);
    expect(resolveMarkingFps(0)).toBe(30);
    expect(resolveMarkingFps(Number.NaN)).toBe(30);
  });

  it('25fps_영상_끝부분_마킹이_BE_상한_안에_들어온다', () => {
    const durationSec = 60;
    const fps = resolveMarkingFps(25);
    const limit = beManualFrameIndexLimit(durationSec, fps); // 1500 + 25 = 1525

    // 영상 뒤 구간(55초·59.9초) — 결함 재현 구간
    expect(markingFrameIndex(55, fps)).toBeLessThan(limit);
    expect(markingFrameIndex(59.9, fps)).toBeLessThan(limit);
  });

  it('fps를_30으로_하드코딩하면_25fps_영상에서_BE_상한을_넘는다_회귀_증거', () => {
    const durationSec = 60;
    const realFps = 25;
    const limit = beManualFrameIndexLimit(durationSec, realFps);

    // 구 FE 동작(30 하드코딩) — 55초 지점에서 1650 > 1525 → BE 400
    expect(markingFrameIndex(55, 30)).toBeGreaterThan(limit);
    // 수정 후(서버 fps 사용) — 통과
    expect(markingFrameIndex(55, resolveMarkingFps(realFps))).toBeLessThan(limit);
  });

  it('29_97fps_와_정수초_저장오차_구간도_상한_안에_들어온다', () => {
    // VDO_LEN_SEC 은 정수 초로 반올림 저장된다(60.4초 → 60). 실제 재생 끝부분(60.4초) 마킹이
    // 거부되지 않아야 한다 — BE 상한의 1초 마진이 이 오차를 흡수한다.
    const storedDurationSec = 60;
    const fps = resolveMarkingFps(29.97);
    const limit = beManualFrameIndexLimit(storedDurationSec, fps);
    expect(markingFrameIndex(60.4, fps)).toBeLessThan(limit);
  });

  it('영상_길이를_크게_벗어난_값은_여전히_상한_밖이다', () => {
    // 마진이 "무제한 허용"으로 변질되지 않았는지 — 실측 결함값(999999999)은 여전히 거부 대상.
    const limit = beManualFrameIndexLimit(60, 30);
    expect(999999999).toBeGreaterThan(limit);
  });
});
