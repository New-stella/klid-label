// 회귀 가드 — 포털 임베드 스타일 격리. [@design INT-013]
//
// ## 무엇을 지키나 (2026-09-10 개발망 실측 사고)
//
// 포털 Host 안에서 저작도구 화면이 «통째로 눌려» 보였다. 원인은 둘이고 <서로 독립>이다:
//
//   ① Host 의 리셋이 «레이어 밖»에 있다 — CSS 캐스케이드에서 레이어 없는 스타일은 레이어
//      안의 스타일을 <무조건> 이긴다(우선순위와 무관). 우리 유틸리티가 `@layer utilities`
//      안에 있어서 «전부» 졌다. 인라인 `!important` 로도 이기지 못했다.
//   ② Host 가 `html { font-size: 10px }`(62.5% 관례)를 쓴다 — 우리 간격 유틸리티가
//      `calc(var(--spacing) * N)` 이고 `--spacing` 이 rem 이라 모든 간격이 62.5% 로 줄었다.
//
// ## 왜 «소스»를 검사하나 — 이 결함은 실행 시험으로 잡히지 않는다
//
// jsdom 은 캐스케이드 레이어를 구현하지 않고, 애초에 Host 의 CSS 가 시험 환경에 없다. 즉
// «두 CSS 가 만나는 순간»을 단위 시험이 재현할 수 없다. 그래서 그 만남을 견디게 하는
// <구성 자체>를 고정한다 — 구성이 되돌아가면 다음 배포에서 같은 사고가 난다.
//
// ⚠ 이 가드는 «화면이 예쁜지»를 말하지 않는다. 그건 실배포 육안 확인의 몫이다.

import { readFileSync } from 'node:fs';
import path from 'node:path';

import { describe, expect, it } from 'vitest';

import { PORTAL_EMBED_ANCHOR_CLASS } from '@/lib/portalEmbedAnchor';

const SRC_ROOT = path.resolve(__dirname, '../..');
const GLOBAL_CSS = readFileSync(path.join(SRC_ROOT, 'styles', 'global.css'), 'utf-8');
const REMOTE_ENTRY = readFileSync(path.join(SRC_ROOT, 'remote', 'AuthoringRemote.tsx'), 'utf-8');

/**
 * 주석을 걷어낸 사본 — 검사 대상은 «실제 규칙»이지 설명문이 아니다.
 *
 * ⚠ 이 단계를 빼면 가드가 «자기 주석»을 보고 판정한다. 실제로 그렇게 짰다가 아래 ③ 가드가
 *   자기 파일의 설명문에 걸려 거짓 실패를 냈다 — 설명하려고 적은 이름이 금지어 검사에
 *   잡히는 형태다.
 */
const stripComments = (css: string) => css.replace(/\/\*[\s\S]*?\*\//g, '');

/** 주석·공백을 걷어낸 사본 — 서식(줄바꿈·들여쓰기) 변경에 가드가 깨지지 않게 한다. */
const CSS_COMPACT = stripComments(GLOBAL_CSS).replace(/\s+/g, '');
const REMOTE_CODE = REMOTE_ENTRY.replace(/\/\/[^\n]*/g, '').replace(/\/\*[\s\S]*?\*\//g, '');

describe('포털 스타일 격리 — 구성 고정', () => {
  describe('① 유틸리티는 레이어 «밖»에 있어야 한다', () => {
    it('유틸리티를 layer() 로 감싸지 않는다', () => {
      // `@import 'tailwindcss/utilities.css' layer(utilities)` 로 되돌리면 Host 의 레이어 밖
      // 리셋에 «전부» 진다. 그것이 이 사고의 원인 ① 이다.
      expect(CSS_COMPACT).toContain("@import'tailwindcss/utilities.css';");
      expect(CSS_COMPACT).not.toContain("utilities.css'layer(utilities)");
    });

    it('★ preflight 는 레이어 «안»에 남긴다 — 밖으로 빼면 우선순위 축에서도 Host 를 이긴다', () => {
      // 우리가 이겨야 하는 것은 «우리가 클래스를 붙인 요소»뿐이다. 리셋까지 레이어 밖으로
      // 나가면 그 범위를 넘어 우선순위 싸움을 걸게 된다.
      //
      // ⚠ **폐기된 근거(2026-09-15 반증)**: 이 케이스는 한때 *"리셋이 레이어 밖으로 나가면
      //   포털 헤더·좌측 메뉴까지 덮어쓴다"* 라고 적어 「레이어 «안»이면 Host 가 안전하다」를
      //   전제했다. 틀렸다 — 계층은 «같은 속성»을 두고 맞붙을 때만 작동하고, Host 가 선언하지
      //   않은 속성은 레이어 안의 우리 규칙이 그대로 먹는다(실제로 Host UI 가 깨졌다).
      //   **격리는 선택자 범위로만 얻어진다** — 그 축의 가드는 `portalBaseLayerScoping` 이다.
      //   이 케이스는 «우선순위» 축만 지킨다. 두 축을 섞지 말 것.
      expect(CSS_COMPACT).toContain("@import'tailwindcss/preflight.css'layer(base);");
    });

    it('구 형태(`@import \'tailwindcss\'` 한 줄)로 되돌아가지 않았다', () => {
      // 그 한 줄은 유틸리티까지 layer(utilities) 로 감싼다 — 되돌리면 사고가 재발한다.
      expect(CSS_COMPACT).not.toMatch(/@import'tailwindcss';/);
    });
  });

  describe('② 간격 기준을 우리 영역에서 절대값으로 못 박는다', () => {
    it('포털 앵커가 --spacing 을 px 로 고정한다', () => {
      expect(CSS_COMPACT).toContain('.klid-portal-embed{--spacing:4px;}');
    });

    it('포털 앵커가 바탕을 Host 카드와 같은 흰색으로 칠한다', () => {
      // 이 선언이 없으면 앵커로 좁혀진 `body` 리셋의 관제 축 회색(#f4f5f6)이 흰 Host 카드
      // 위에 한 겹 얹힌다(2026-09-15 개발망 실측).
      expect(CSS_COMPACT).toContain('.klid-portal-embed{background-color:#fff;}');
    });

    it('★ 그 규칙은 레이어 «밖»이다 — 레이어 안이면 Host 선언에 진다', () => {
      // ⚠ 주석을 걷어낸 사본에서 «규칙 자체»(`{` 가 뒤따르는 자리)를 찾는다. 원문에서 클래스
      //   «이름»을 찾으면 레이어 «안»의 설명 주석이 먼저 걸려, 규칙이 레이어 안으로 옮겨가도
      //   가드가 그 사실을 못 본다.
      const cssNoComments = stripComments(GLOBAL_CSS);
      const idx = cssNoComments.indexOf('.klid-portal-embed {');
      expect(idx).toBeGreaterThan(-1);
      // 앞쪽에 «닫히지 않은» @layer 블록이 없어야 한다.
      const before = cssNoComments.slice(0, idx);
      const opened = (before.match(/@layer[^;{]*\{/g) ?? []).length;
      const closed = (before.match(/\}/g) ?? []).length;
      expect(closed).toBeGreaterThanOrEqual(opened);
    });
  });

  describe('③ 앵커는 «우리가 소유한» 요소여야 한다', () => {
    it('Remote 진입점이 앵커 클래스를 붙인다', () => {
      // ⚠ 주석을 걷어낸 사본을 본다 — 원문을 보면 「이 요소에 붙인다」고 «적어 둔 설명»이
      //   걸려, 실제 JSX 에서 클래스가 빠져도 통과한다.
      expect(REMOTE_CODE).toContain('PORTAL_EMBED_ANCHOR_CLASS');
      expect(REMOTE_CODE).toContain("from '@/lib/portalEmbedAnchor'");
      // 그 상수의 «값»이 CSS 가 아는 이름과 같다 — 갈리면 격리가 조용히 풀린다.
      expect(PORTAL_EMBED_ANCHOR_CLASS).toBe('klid-portal-embed');
      expect(CSS_COMPACT).toContain(`.${PORTAL_EMBED_ANCHOR_CLASS}{--spacing:4px;}`);
    });

    it('★ Host 가 만든 요소 이름에 기대지 않는다', () => {
      // Host 의 마운트 슬롯(`klid-authoring-slot`)에 걸면 상대가 이름을 바꾸는 순간
      // «조용히» 깨지고 우리 시험으로는 잡히지 않는다.
      // ⚠ 주석은 검사 대상이 아니다 — 위 두 상수가 설명문을 걷어낸 사본이다.
      //   「쓰지 말라」고 «적어 둔 것»까지 위반으로 잡으면 가드가 문서화를 막는다.
      expect(CSS_COMPACT).not.toContain('klid-authoring-slot');
      expect(REMOTE_CODE).not.toContain('klid-authoring-slot');
    });
  });
});
