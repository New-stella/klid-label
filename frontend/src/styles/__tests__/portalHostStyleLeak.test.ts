// 회귀 가드 — 포털 모습 CSS 가 Host 문서로 새지 않는다. [@design INT-013]
//
// ## 무엇을 지키나 (2026-09-16 사용자 신고 · 개발망)
//
// *"부모 포털 스타일이 좀 깨질때가 있는거같은데 header라던지 저작도구 진입하면 깨지네"*
//
// 원인은 **스코프 플러그인의 시야가 좁았던 것**이다. 그 플러그인은 `@layer base` 안만 훑는데,
// KRDS 킷(`krds-react/dist/index.css`)과 우리 포털 테마(`src/styles/portal/`)는 **레이어 밖
// 평범한 CSS** 라 한 번도 지나가지 않았다. 산출물 실측으로 좁혀지지 않은 문서 수준·맨요소
// 선택자가 **134개** 남아 있었고, 그것이 Host 문서 전체를 다시 칠했다.
//
// 브라우저 실측으로 확인한 파손(수정 전):
//   · Host `h1` 의 위아래 여백이 `0` 이 되고 `box-sizing` 이 `border-box` 로 바뀐다
//   · Host `ul` 의 목록 점이 사라지고 들여쓰기가 `0` 이 된다
//   · Host 가 읽는 KRDS 색 토큰이 우리 코발트 값으로 갈아치워진다
//
// ## 왜 이 축을 따로 두나 — 기존 가드는 이것을 못 본다
//
// `portalBaseLayerScoping.test.ts` 는 **`@layer base` 안**만 검사한다. 그 가드가 전부 초록인
// 채로 이 사고가 났다. 즉 「0건」이 아니라 「보지 않은 층」이 문제였다.
// 여기서는 **레이어 밖 평범한 CSS** 를 대상으로 같은 질문을 던진다.
//
// ⚠ 이 가드는 실제 산출물을 읽지 않는다(시험이 빌드를 돌리지 않는다). 대신 **플러그인이 그
//   모양의 입력을 받았을 때 무엇을 내놓는지**를 고정한다. 실제 산출물 확인은 배포 전 육안·
//   빌드 후 grep 의 몫이다.

import { existsSync } from 'node:fs';
import path from 'node:path';

import postcss from 'postcss';
import { describe, expect, it } from 'vitest';

import { PORTAL_EMBED_ANCHOR_CLASS } from '@/lib/portalEmbedAnchor';

import scopePortalBaseLayer, { __testing } from '../../../postcss/scope-portal-base-layer.js';

const SRC_ROOT = path.resolve(__dirname, '../..');
const REPO_FRONTEND = path.resolve(SRC_ROOT, '..');

/** 포털 모습 CSS 로 취급되는 경로에서 돌린다 — 그 판정 자체가 검사 대상이다. */
const PORTAL_LOOK_FILE = path.join(SRC_ROOT, 'styles', 'portal', 'krds-theme.css');

function run(css: string, from: string = PORTAL_LOOK_FILE): string {
  return postcss([scopePortalBaseLayer({ enabled: true })]).process(css, { from }).css;
}

/** 서식에 가드가 깨지지 않게 — 공백을 걷어낸 사본으로 본다. */
const compact = (css: string) => css.replace(/\s+/g, '');

describe('포털 모습 CSS 가 Host 로 새지 않는다', () => {
  describe('① 실제로 샜던 규칙 셋 — 그대로 재현해 좁혀지는지 본다', () => {
    it('★전역 리셋의 맨요소 목록이 전부 앵커 안으로 좁혀진다', () => {
      // 산출물에 있던 실제 규칙(줄임). Host 의 모든 요소에서 여백을 걷어내던 자리다.
      const out = run('body,div,p,h1,h2,ul,ol,li,table,th,td{box-sizing:border-box;margin:0;padding:0}');

      // `body` 는 앵커 «자신»으로 접힌다 — 문서 몸통이 아니라 우리 판이 그 역할을 한다.
      expect(compact(out)).toContain(`.${PORTAL_EMBED_ANCHOR_CLASS},`);
      // 맨요소는 앵커 «자신 + 하위»로 좁혀진다.
      for (const el of ['div', 'p', 'h1', 'h2', 'ul', 'ol', 'li', 'table', 'th', 'td']) {
        expect(compact(out)).toContain(
          compact(`${el}:where(.${PORTAL_EMBED_ANCHOR_CLASS},.${PORTAL_EMBED_ANCHOR_CLASS} *)`),
        );
      }
      // 좁혀지지 않은 맨요소가 하나도 남지 않는다.
      expect(out).not.toMatch(/(^|,)\s*(div|p|h1|h2|ul|ol|li|table|th|td)\s*[,{]/);
    });

    it('★`font-size:inherit` 을 Host 버튼·입력칸에 걸지 않는다', () => {
      const out = run('input,textarea,a,button,select,span,label{font-size:inherit;font-weight:inherit}');
      expect(out).not.toMatch(/(^|,)\s*(input|button|label|select)\s*[,{]/);
      expect(compact(out)).toContain(
        compact(`input:where(.${PORTAL_EMBED_ANCHOR_CLASS},.${PORTAL_EMBED_ANCHOR_CLASS} *)`),
      );
    });

    it('★★KRDS 색 토큰이 Host 로 새지 않는다 — Host 도 같은 토큰 이름을 읽는다', () => {
      // 이것이 가장 큰 파손이었다. Host 는 KRDS 앱이라 `--krds-color-*` 를 그대로 소비한다.
      const out = run(':root{--krds-color-light-primary-50:#2e45dc;--krds-gap-3:8px}');
      expect(compact(out)).toContain(
        compact(`.${PORTAL_EMBED_ANCHOR_CLASS}{--krds-color-light-primary-50:#2e45dc;--krds-gap-3:8px}`),
      );
      expect(out).not.toMatch(/(^|})\s*:root\s*\{/);
    });
  });

  describe('② 문서 루트에 남아야 하는 «둘» — 이것까지 좁히면 우리 화면이 1.6배가 된다', () => {
    it('★`html{font-size}` 는 전역으로 남는다 — rem 은 앵커가 아니라 문서 루트를 본다', () => {
      const out = run('html{font-size:var(--krds-font-size-base)}');
      expect(compact(out)).toBe(compact('html{font-size:var(--krds-font-size-base)}'));
    });

    it('★그 값을 나르는 토큰도 전역으로 남되, 같은 블록의 «나머지»는 앵커로 간다', () => {
      const out = run(':root{--krds-font-size-base:62.5%;--krds-color-light-primary-50:#2e45dc}');
      // 루트 글꼴 토큰만 전역으로 떨어져 나온다.
      expect(compact(out)).toContain(compact(':root{--krds-font-size-base:62.5%}'));
      // 색 토큰은 앵커 안에 갇힌다.
      expect(compact(out)).toContain(
        compact(`.${PORTAL_EMBED_ANCHOR_CLASS}{--krds-color-light-primary-50:#2e45dc}`),
      );
      // 색 토큰이 `:root` 에 남아 있지 않다.
      expect(out).not.toMatch(/:root\s*\{[^}]*--krds-color-light-primary-50/);
    });
  });

  describe('③ 좁히면 안 되는 것을 좁히지 않는다', () => {
    it('`@keyframes` 안의 `from`/`to`/`50%` 는 선택자가 아니다', () => {
      const out = run('@keyframes spin{from{transform:rotate(0)}50%{opacity:.5}to{transform:rotate(360deg)}}');
      expect(compact(out)).toBe(
        compact('@keyframes spin{from{transform:rotate(0)}50%{opacity:.5}to{transform:rotate(360deg)}}'),
      );
    });

    it('이미 좁혀진 규칙을 두 번 좁히지 않는다', () => {
      const already = `.klid-card:where(.${PORTAL_EMBED_ANCHOR_CLASS},.${PORTAL_EMBED_ANCHOR_CLASS} *){color:red}`;
      expect(compact(run(already))).toBe(compact(already));
    });

    it('`@media` 안의 규칙도 좁힌다 — 화면 폭에 따라서만 새는 구멍을 두지 않는다', () => {
      const out = run('@media (max-width:767px){h1{font-size:2.4rem}}');
      expect(compact(out)).toContain(
        compact(`h1:where(.${PORTAL_EMBED_ANCHOR_CLASS},.${PORTAL_EMBED_ANCHOR_CLASS} *)`),
      );
    });

    it('★관제 채널에서는 아무 일도 하지 않는다 — 산출물이 그대로여야 한다', () => {
      const css = 'body,div,h1{margin:0}:root{--krds-gap-3:8px}';
      const out = postcss([scopePortalBaseLayer({ enabled: false })]).process(css, {
        from: PORTAL_LOOK_FILE,
      }).css;
      expect(compact(out)).toBe(compact(css));
    });
  });

  describe('④ 「어느 파일을 훑는가」가 곧 격리 범위다', () => {
    it('★포털 모습 CSS 가 아닌 파일은 레이어 밖을 건드리지 않는다', () => {
      // 관제와 함께 쓰는 CSS 까지 좁히면 관제 화면이 깨진다. 판정 축은 경로다.
      const elsewhere = path.join(SRC_ROOT, 'styles', 'global.css');
      const out = run('h1{margin:0}', elsewhere);
      expect(compact(out)).toBe(compact('h1{margin:0}'));
    });

    it('★판별 경로 셋이 실재한다 — 폴더가 옮겨지면 조용히 새므로 존재를 못박는다', () => {
      const dirs = [
        path.join(REPO_FRONTEND, 'node_modules', 'krds-react'),
        path.join(SRC_ROOT, 'styles', 'portal'),
        path.join(SRC_ROOT, 'components', 'portal'),
      ];
      for (const dir of dirs) {
        expect(existsSync(dir), `${dir} 가 없다 — 판별 경로를 갱신해야 한다`).toBe(true);
        expect(__testing.PORTAL_LOOK_PATH.test(path.join(dir, 'x.css'))).toBe(true);
      }
    });

    it('양성 대조 — 판별 경로 밖은 실제로 걸리지 않는다', () => {
      // 위 단언이 「무엇이든 참」이 되어 거짓 초록이 되지 않게 반대쪽을 함께 본다.
      expect(__testing.PORTAL_LOOK_PATH.test(path.join(SRC_ROOT, 'features', 'label', 'x.css'))).toBe(
        false,
      );
    });
  });
});
