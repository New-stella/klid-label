// 회귀 가드 — 임베드 안쪽 여백은 «우리» 책임이다. [@design SHELL-002] [@design DS-002]
//
// ## 무엇을 지키나 (2026-09-16 사용자 신고)
//
// *"포털향 우리 저작도구가 컨테이너에 너무 딱 달라붙었다"*
//
// 임베드 정렬선이 `w-full` 하나였다 — 거터도 최대폭도 두지 않았다. 그 근거였던 2026-09-15 실측
// (*"Host 슬롯이 이미 자기 좌우 여백 24px 를 갖고 있다"*)은 **마운트 전** 슬롯을 잰 것이고,
// Host 는 마운트되는 순간 `.klid-authoring-slot[data-state='mounted'] { padding: 0 }` 로 여백을
// 스스로 걷는다. Host 가 그 규칙에 붙인 주석이 계약을 그대로 말한다 —
// *"여백은 탭 줄 · 콘텐츠 자리가 각자 갖는다"*.
//
// ## 왜 「같다」를 시험하나
//
// 두 채널의 정렬선이 **우연히 같은 것이 아니라 같아야 한다.** Host 자신의 저작도구 화면이
// 쓰는 값(탭 줄 `padding-inline:24` · 콘텐츠 `padding:24` · 판 `max-width:1152`)이 곧 우리
// 독립 앱 정렬선(최대폭 1200 + 거터 24 → 안쪽 1152)과 같은 값이기 때문이다.
// 임베드만 따로 좁히거나 넓히면 Host 화면과 시작선이 어긋난다.
//
// ⚠ 이 가드는 «보이는 여백»을 재지 않는다(jsdom 에 Host CSS 가 없다). 고정하는 것은 «구성»이다.

import { afterEach, describe, expect, it, vi } from 'vitest';

import {
  PORTAL_SHELL_ALIGN_EMBED,
  PORTAL_SHELL_ALIGN_STANDALONE,
  PORTAL_TABS_TOP_EMBED,
  portalShellAlign,
  portalTabsTopPadding,
} from '@/lib/portalShellLayout';

/** 채널은 빌드 시점 환경값이라 스텁으로 갈아 끼운다(`isPortalEmbedChannel` 이 매번 다시 읽는다). */
function stubChannel(channel: 'portal' | 'control') {
  vi.stubEnv('VITE_BUILD_CHANNEL', channel);
}

afterEach(() => {
  vi.unstubAllEnvs();
});

describe('포털 셸 가로 정렬선', () => {
  describe('① 임베드가 여백을 «갖는다» — 이것이 신고된 결함의 축이다', () => {
    it('★거터를 둔다 — 없으면 카드 모서리에 그대로 붙는다', () => {
      stubChannel('portal');
      expect(portalShellAlign()).toMatch(/\bpx-/);
    });

    it('★★폭은 묶지 «않는다» — 읽기 폭은 Host 가 이미 슬롯에서 막는다', () => {
      // Host 시안 실측: 슬롯 자체가 `max-width:1200` + 가운데. 우리가 한 번 더 묶으면 두 겹이
      // 되어, 그보다 넓은 실배포 Host 본문에서 화면이 가운데로 몰린다(2026-09-16 사용자 신고).
      stubChannel('portal');
      expect(portalShellAlign()).not.toMatch(/\bmax-w-/);
      expect(portalShellAlign()).not.toMatch(/\bmx-auto\b/);
    });

    it('★★구 동작으로 되돌아가지 않는다 — 정렬선이 `w-full` 하나이면 안 된다', () => {
      // 되돌림을 낱말이 아니라 «형태»로 막는다. 거터·최대폭 둘 중 하나만 빠져도 위 둘이 잡는다.
      expect(PORTAL_SHELL_ALIGN_EMBED.trim()).not.toBe('w-full');
    });
  });

  describe('② 폭의 주인이 채널마다 다르다', () => {
    it('★독립 앱만 읽기 폭을 막는다 — 그쪽은 우리가 문서를 소유한다', () => {
      expect(PORTAL_SHELL_ALIGN_STANDALONE).toContain('max-w-wrap');
      expect(PORTAL_SHELL_ALIGN_EMBED).not.toContain('max-w-wrap');
    });

    it('★임베드 거터가 독립 앱보다 한 단 좁다 — 흰 카드 안이라 여백이 겹쳐 보인다', () => {
      // 사용자 판단(2026-09-16): 24 는 카드 안에서 깊다 → 토큰 한 칸 아래인 16.
      expect(PORTAL_SHELL_ALIGN_EMBED).toContain('px-4');
      expect(PORTAL_SHELL_ALIGN_EMBED).not.toContain('px-column');
      expect(PORTAL_SHELL_ALIGN_STANDALONE).toContain('px-column');
    });

    it('★★거터를 0 으로 되돌리지 않는다 — 그 동작이 「딱 달라붙었다」 신고의 원인이었다', () => {
      stubChannel('portal');
      expect(portalShellAlign()).toMatch(/\bpx-/);
      expect(PORTAL_SHELL_ALIGN_EMBED.trim()).not.toBe('w-full');
    });
  });

  describe('③ 탭 줄 윗 여백은 임베드에만 붙는다', () => {
    it('★윗 여백이 좌우 거터와 «같은 값»이다 — 한쪽만 줄면 탭이 비뚤어 보인다', () => {
      stubChannel('portal');
      expect(portalTabsTopPadding()).toBe(PORTAL_TABS_TOP_EMBED);
      // 값을 옮겨 적지 않고 «같은 칸인가»로 본다 — 거터를 바꾸면 이 단언이 함께 따라온다.
      const gutter = /\bpx-([\w.[\]]+)\b/.exec(PORTAL_SHELL_ALIGN_EMBED)?.[1];
      const top = /\bpt-([\w.[\]]+)\b/.exec(PORTAL_TABS_TOP_EMBED)?.[1];
      expect(gutter).toBeDefined();
      expect(top).toBe(gutter);
    });

    it('★독립 앱은 주지 않는다 — 위에 자체 머리 영역이 있어 머리와 탭이 멀어진다', () => {
      stubChannel('control');
      expect(portalTabsTopPadding()).toBe('');
    });
  });
});
