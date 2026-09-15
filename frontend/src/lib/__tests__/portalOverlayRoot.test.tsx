// 회귀 가드 — 덧띄움(모달·서랍·떠 있는 창·도구 안내)이 «스타일 격리 앵커 안»에 붙는다.
// [@design INT-013]
//
// ## 무엇을 지키나
//
// `createPortal(node, document.body)` 로 붙인 것은 우리 React 트리 안에 있어도 «DOM 상으로는»
// 앵커 밖이다. 포털 채널에서 그것이 둘을 동시에 일으킨다:
//
//   ① (이 변경 이전부터 있던 결함) 앵커가 주는 `--spacing: 4px` 를 못 받는다. Host 가 루트
//      글꼴을 62.5% 로 쓰므로 기본값 `0.25rem` 이 2.5px 로 떨어져 여백이 전부 눌린다.
//   ② (이 변경으로 새로 생길 결함) 전역 리셋을 앵커 하위로 좁히는 순간, 앵커 밖인 이것들은
//      리셋마저 못 받아 브라우저 기본 스타일로 되돌아간다.
//
// ⚠ ①②는 «화면을 열어 봐야» 보이는 종류라 실행 시험이 직접 재현하지 못한다(jsdom 에는 Host
//   CSS 도 캐스케이드 레이어도 없다). 그래서 여기서는 그 결과를 떠받치는 **구조**를 고정한다 —
//   덧띄움이 앵커 «안»에 붙는가, 그리고 다섯 곳이 빠짐없이 같은 창구를 쓰는가.

import { readFileSync } from 'node:fs';
import path from 'node:path';

import { render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { Modal } from '@/components/common/Modal';
import { PORTAL_EMBED_ANCHOR_CLASS } from '@/lib/portalEmbedAnchor';
import { PORTAL_OVERLAY_ROOT_ATTR, getPortalOverlayRoot } from '@/lib/portalOverlayRoot';

const SRC_ROOT = path.resolve(__dirname, '../..');

/**
 * `document.body` 직하에 붙던 다섯 곳 — **전수**다.
 * 하나만 빠져도 그 덧띄움은 조용히 격리 밖으로 떨어진다.
 */
const OVERLAY_CALL_SITES = [
  'components/common/Modal.tsx',
  'components/common/Drawer.tsx',
  'components/common/FloatingWindow.tsx',
  'features/label/components/ToolBar.tsx',
  'features/task/components/HistoryDrawer.tsx',
];

/** 주석을 걷어낸 코드 본문 — 이 저장소는 주석에 결정 근거를 싣는다. */
function codeOf(relative: string): string {
  return readFileSync(path.join(SRC_ROOT, relative), 'utf-8')
    .replace(/\/\*[\s\S]*?\*\//g, '')
    .replace(/\/\/[^\n]*/g, '');
}

afterEach(() => {
  vi.unstubAllEnvs();
});

describe('덧띄움 마운트 자리', () => {
  describe('① 다섯 곳이 빠짐없이 공용 창구를 쓴다', () => {
    it('검사기가_실제로_돌았다', () => {
      // 파일 경로가 바뀌어 읽기가 비면 아래 「0건」이 공짜로 통과한다.
      for (const site of OVERLAY_CALL_SITES) {
        expect(codeOf(site).length).toBeGreaterThan(500);
        expect(codeOf(site)).toContain('createPortal');
      }
    });

    it('★createPortal_의_대상이_document_body_가_아니다', () => {
      for (const site of OVERLAY_CALL_SITES) {
        expect(codeOf(site)).not.toContain('document.body');
      }
    });

    it('★다섯_곳_모두_같은_헬퍼를_부른다', () => {
      for (const site of OVERLAY_CALL_SITES) {
        expect(codeOf(site)).toContain('getPortalOverlayRoot()');
      }
    });
  });

  describe('② 채널에 따라 앵커 클래스가 갈린다', () => {
    it('포털_채널이면_앵커_클래스가_붙는다', () => {
      vi.stubEnv('VITE_BUILD_CHANNEL', 'portal');
      expect(getPortalOverlayRoot().classList.contains(PORTAL_EMBED_ANCHOR_CLASS)).toBe(true);
    });

    it('★관제_채널이면_붙지_않는다_음성_대조', () => {
      // 둘 다 붙으면 「채널로 가른다」가 검증되지 않는다.
      vi.stubEnv('VITE_BUILD_CHANNEL', 'control');
      expect(getPortalOverlayRoot().classList.contains(PORTAL_EMBED_ANCHOR_CLASS)).toBe(false);
    });

    it('채널_판정을_매번_다시_읽는다', () => {
      // 만들 때 한 번만 보면 첫 호출 시점 값에 고정돼, 같은 실행 안에서 채널이 바뀐 경우를
      // 재현할 수 없다(시험이 두 채널을 다 보려면 필요하다).
      vi.stubEnv('VITE_BUILD_CHANNEL', 'portal');
      const first = getPortalOverlayRoot();
      vi.stubEnv('VITE_BUILD_CHANNEL', 'control');
      const second = getPortalOverlayRoot();
      expect(second).toBe(first);
      expect(second.classList.contains(PORTAL_EMBED_ANCHOR_CLASS)).toBe(false);
    });
  });

  describe('③ 쌓임 맥락(stacking context)을 만들지 않는다', () => {
    it('★상자를_만드는_속성을_하나도_주지_않는다', () => {
      vi.stubEnv('VITE_BUILD_CHANNEL', 'portal');
      const root = getPortalOverlayRoot();
      // 이 중 하나라도 있으면 새 쌓임 맥락이 생겨 안쪽 z-50 이 바깥과 겨루지 못한다 —
      // 모달이 배경 뒤로 들어가거나 잘린다.
      // ⚠ `style[prop]` 이 아니라 `getPropertyValue` 로 읽는다 — jsdom 이 모르는 속성은
      //   전자가 `undefined` 를 줘서 「빈 값」과 구분되지 않는다(거짓 실패가 난다).
      // ⚠⚠ 속성 이름을 «하이픈 표기 리터럴»로 적지 않는다(주석에도 적지 않는다) —
      //    Tailwind 스캐너는 소스를 «평문»으로 훑어 그런 문자열을 유틸리티 후보로 집고,
      //    **관제 산출물에 없던 클래스를 만들어 낸다**. 실측: 흐림 효과 계열 속성 이름을
      //    하이픈 표기로 한 줄 적었더니 관제 CSS 가 +1,369 B 로 달라졌다 — 두 번 겪었고
      //    두 번째는 「그러지 말라」고 적은 «주석 자신»이 원인이었다.
      //    관제 산출물 무변경은 이 라운드의 불변 조건이다.
      const toKebab = (name: string) => name.replace(/[A-Z]/g, (c) => `-${c.toLowerCase()}`);
      for (const prop of [
        'transform',
        'filter',
        'opacity',
        'position',
        'zIndex',
        'willChange',
        'contain',
        'isolation',
        'perspective',
        'backdropFilter',
        'mixBlendMode',
      ]) {
        expect(root.style.getPropertyValue(toKebab(prop))).toBe('');
      }
      // 상자 자체를 만들지 않는다 — Host 의 body 가 flex/grid 여도 항목으로 끼어들지 않는다.
      expect(root.style.display).toBe('contents');
      expect(root.getAttribute('style')).toBe('display: contents;');
    });

    it('표식_속성은_스타일이_아니라_찾기용이다', () => {
      expect(getPortalOverlayRoot().hasAttribute(PORTAL_OVERLAY_ROOT_ATTR)).toBe(true);
    });
  });

  describe('④ 실제 모달이 그 안에 붙는다', () => {
    it('포털_채널에서_모달이_앵커_안에_있다', () => {
      vi.stubEnv('VITE_BUILD_CHANNEL', 'portal');
      render(
        <Modal open onClose={() => {}} title="격리 확인">
          <p>본문</p>
        </Modal>,
      );
      const dialog = screen.getByRole('dialog');
      expect(dialog.closest(`.${PORTAL_EMBED_ANCHOR_CLASS}`)).not.toBeNull();
    });

    it('★관제_채널에서는_기존처럼_body_하위에_있고_앵커는_없다', () => {
      vi.stubEnv('VITE_BUILD_CHANNEL', 'control');
      render(
        <Modal open onClose={() => {}} title="격리 확인">
          <p>본문</p>
        </Modal>,
      );
      const dialog = screen.getByRole('dialog');
      expect(document.body.contains(dialog)).toBe(true);
      expect(dialog.closest(`.${PORTAL_EMBED_ANCHOR_CLASS}`)).toBeNull();
    });
  });
});
