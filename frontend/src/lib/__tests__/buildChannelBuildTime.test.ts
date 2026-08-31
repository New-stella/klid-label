import { afterEach, describe, expect, it, vi } from 'vitest';

// [@design INT-013]
/**
 * 채널 판정의 **두 형태가 같은 것을 말한다**는 회귀 가드.
 *
 * 이 저장소는 「같은 개념을 두 곳에서 판정하면 한쪽만 갱신될 때 조용히 갈린다」를 반복 경고해
 * 왔다(`lib/buildChannel` 주석). 그런데 채널 판정에는 **형태가 둘** 필요하다 —
 *
 *   · `isPortalEmbedChannel()` : 매 호출마다 다시 읽는 함수. 테스트가 값을 바꿔 가며 검증한다.
 *   · `IS_PORTAL_CHANNEL_BUILD` : 산출 시점에 접히는 상수. 반대 채널 코드를 번들에서 뺀다.
 *
 * 갈리는 것은 **판정 대상이 아니라 평가 시점**이고 읽는 키도 하나다. 그래도 「하나다」를 주석으로만
 * 두면 다음 사람이 한쪽만 고칠 수 있으므로, 두 형태의 결과가 **같다**를 값 축으로 못 박는다.
 *
 * ⚠ 상수는 모듈이 처음 평가될 때 한 번 굳는다 — `vi.stubEnv` 만으로는 바뀌지 않는다. 그래서
 *   케이스마다 `vi.resetModules()` 후 동적 import 로 **다시 평가**시킨다. 이 절차를 빠뜨리면
 *   모든 케이스가 첫 회차 값(미설정=관제)을 보게 되어 가드가 조용히 눈이 먼다.
 */

async function loadChannel(value: string) {
  vi.resetModules();
  vi.stubEnv('VITE_BUILD_CHANNEL', value);
  return import('@/lib/buildChannel');
}

describe('빌드 채널 판정 — 실행 중 형태와 산출 시점 형태', () => {
  afterEach(() => {
    vi.unstubAllEnvs();
    vi.resetModules();
  });

  it.each([
    ['portal', true],
    ['control', false],
    ['', false],
    ['portall', false],
    ['PORTAL', false],
  ])('채널값 %s 에서 두 형태가 같은 답을 낸다', async (value, expected) => {
    const mod = await loadChannel(value);

    expect(mod.IS_PORTAL_CHANNEL_BUILD).toBe(expected);
    expect(mod.isPortalEmbedChannel()).toBe(expected);
  });

  it('★두_형태는_언제나_서로_같다_한쪽만_고치면_여기서_깨진다', async () => {
    for (const value of ['portal', 'control', '', 'x', 'Portal', 'portal ']) {
      const mod = await loadChannel(value);
      expect(mod.IS_PORTAL_CHANNEL_BUILD).toBe(mod.isPortalEmbedChannel());
    }
  });

  it('상수는_기본값_정책을_그대로_물려받는다_미지의_값은_관제다', async () => {
    // fail-closed — 오타·미설정이 포털로 열리면 관제 산출물이 포털 형상으로 뜬다.
    const mod = await loadChannel('portal-embed');

    expect(mod.IS_PORTAL_CHANNEL_BUILD).toBe(false);
    expect(mod.DEFAULT_BUILD_CHANNEL).toBe('control');
  });
});
