/**
 * 포털 업로드 영상 마킹의 순수 계산 회귀 가드. [@design SCREEN-045] [@design API-240]
 *
 * 이 파일이 고정하는 계약:
 *  1. 자동 지점 산출 규칙이 **서버와 같다** — 총 프레임 수 `round(round(길이) × fps)`, 지점은 0 부터
 *     간격만큼 총 프레임 수 **미만**까지, 표시용 시각은 `frameIndex / fps` 의 정수부를 `mm:ss` 로.
 *     갈리면 화면이 예고한 장수와 실제로 뽑히는 장수가 어긋난다.
 *  2. 간격 단위는 **프레임 수**이고 환산 시간은 초당 프레임 수를 알 때만 낸다(지어내지 않는다).
 *  3. 추출 장수 상한을 **모르면 절단을 주장하지 않는다** — 화면이 그 값을 갖지 않기 때문이다.
 *  4. 저장 요청 본문은 방식에 쓰이지 않는 칸을 **아예 만들지 않는다**.
 */
import { describe, expect, it } from 'vitest';

import {
  AUTO_MARK_RENDER_LIMIT,
  PORTAL_AUTO_INTERVAL_DEFAULT_FRAMES,
  autoMarkCount,
  buildAutoMarks,
  buildMarkingSaveRequest,
  capApplied,
  formatMarkTimestamp,
  intervalSeconds,
  totalFrameCount,
} from '../markingPlan';
import { PortalMarkingMode } from '../markingTypes';

describe('markingPlan — 자동 지점 산출', () => {
  it('기본_간격은_300프레임이다', () => {
    // 관제 채널 자동 마킹의 기본값과 같은 값 — 두 채널이 같은 눈금에서 시작한다.
    expect(PORTAL_AUTO_INTERVAL_DEFAULT_FRAMES).toBe(300);
  });

  it('총_프레임_수는_길이를_정수로_반올림한_뒤_초당프레임을_곱해_반올림한다', () => {
    expect(totalFrameCount(60, 30)).toBe(1800);
    // 소수 길이를 그대로 곱하지 않는다 — 서버가 정수 초로 다루므로 경계에서 장수가 갈린다.
    expect(totalFrameCount(59.6, 30)).toBe(1800);
    expect(totalFrameCount(10, 29.97)).toBe(300);
  });

  it.each([
    [null, 30],
    [0, 30],
    [-1, 30],
    [60, 0],
  ])('길이나_초당프레임을_모르면_총_프레임_수는_0이다 (%s, %s)', (dur, fps) => {
    expect(totalFrameCount(dur as number | null, fps)).toBe(0);
  });

  it('지점은_0부터_간격만큼_총_프레임_수_미만까지다', () => {
    // 길이 10초·30fps → 총 300프레임, 간격 100 → 0 / 100 / 200 세 지점(300 은 없는 프레임이다).
    const { marks, count, renderSampled } = buildAutoMarks(10, 30, 100);

    expect(count).toBe(3);
    expect(renderSampled).toBe(false);
    expect(marks.map((m) => m.frameIndex)).toEqual([0, 100, 200]);
  });

  it('표시용_시각은_프레임을_초당프레임으로_나눈_정수부를_mm_ss_로_적는다', () => {
    const { marks } = buildAutoMarks(120, 30, 900);

    // 0프레임=00:00 / 900프레임=30초 / 1800프레임=60초 / 2700프레임=90초
    expect(marks.map((m) => m.timestamp)).toEqual(['00:00', '00:30', '01:00', '01:30']);
  });

  it('장수는_올림이다', () => {
    expect(autoMarkCount(1000, 300)).toBe(4);
    expect(autoMarkCount(900, 300)).toBe(3);
    expect(autoMarkCount(0, 300)).toBe(0);
    expect(autoMarkCount(1000, 0)).toBe(0);
  });

  it('★표시_한도를_넘으면_앞에서_자르지_않고_전_구간에_고르게_솎되_장수는_정확한_값을_유지한다', () => {
    // 간격 1 · 총 6000프레임 → 6000지점. 표시 한도를 넘는다.
    const { marks, count, renderSampled } = buildAutoMarks(200, 30, 1);

    expect(count).toBe(6000); // 안내에 쓰이는 값은 언제나 정확한 장수다
    expect(renderSampled).toBe(true);
    expect(marks).toHaveLength(AUTO_MARK_RENDER_LIMIT);
    // 앞에서 자르면 마지막 지점이 한도 근처에 머문다 — 전 구간에 퍼져 있어야 한다.
    expect(marks[marks.length - 1]!.frameIndex).toBeGreaterThan(5000);
    expect(marks[0]!.frameIndex).toBe(0);
  });
});

describe('markingPlan — 환산 시간', () => {
  it('초당_프레임_수를_알_때만_환산한다', () => {
    expect(intervalSeconds(300, 30)).toBe(10);
    expect(intervalSeconds(300, 25)).toBe(12);
  });

  it.each([[null], [undefined], [0], [-5]])('초당_프레임_수가_%s_면_환산하지_않는다', (fps) => {
    expect(intervalSeconds(300, fps as number | null | undefined)).toBeNull();
  });
});

describe('markingPlan — 추출 장수 상한', () => {
  it('★상한을_모르면_절단을_주장하지_않는다', () => {
    const out = capApplied(9999, null);

    expect(out.known).toBe(false);
    expect(out.truncated).toBe(false);
    // 모른다고 장수를 지어내지 않는다 — 요청한 수를 그대로 말한다.
    expect(out.effectiveCount).toBe(9999);
  });

  it('상한을_알고_넘으면_잘린_장수와_요청한_장수를_함께_돌려준다', () => {
    const out = capApplied(2500, 2000);

    expect(out).toEqual({ known: true, truncated: true, effectiveCount: 2000, requestedCount: 2500 });
  });

  it('상한_이하면_그대로다', () => {
    expect(capApplied(10, 2000)).toEqual({
      known: true,
      truncated: false,
      effectiveCount: 10,
      requestedCount: 10,
    });
  });
});

describe('markingPlan — 저장 요청 조립', () => {
  it('★자동은_간격만_싣고_지점_배열을_만들지_않는다', () => {
    const body = buildMarkingSaveRequest(PortalMarkingMode.AUTO, 300, [
      { frameIndex: 0, timestamp: '00:00' },
    ]);

    expect(body).toEqual({ mode: 'AUTO', interval: 300 });
    expect('marks' in body).toBe(false);
  });

  it('★수동은_지점만_싣고_간격을_만들지_않는다', () => {
    const marks = [
      { frameIndex: 30, timestamp: '00:01' },
      { frameIndex: 90, timestamp: '00:03' },
    ];

    const body = buildMarkingSaveRequest(PortalMarkingMode.MANUAL, 300, marks);

    expect(body).toEqual({ mode: 'MANUAL', marks });
    expect('interval' in body).toBe(false);
  });
});

describe('markingPlan — 표시용 시각 형식', () => {
  it.each([
    [0, '00:00'],
    [9.9, '00:09'],
    [61, '01:01'],
    [3600, '60:00'],
  ])('%s초는 %s', (sec, expected) => {
    expect(formatMarkTimestamp(sec)).toBe(expected);
  });
});
