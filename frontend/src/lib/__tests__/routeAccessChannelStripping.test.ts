import { readFileSync } from 'node:fs';
import path from 'node:path';

import { describe, expect, it } from 'vitest';

import { INTERNAL_ROUTE_ACCESS, MENU_GROUP_ORDER, allowFor, buildMenuGroups } from '@/lib/routeAccess';
import { Role } from '@/lib/api/types';

// [@design INT-013]
/**
 * 내부 채널 IA 표가 **포털 산출물에서 걷힌다**의 회귀 가드.
 *
 * ## 무엇을 지키나
 * `lib/routeAccess` 는 내부(관제) 채널의 IA 그 자체다 — 메뉴 그룹·항목의 **한글 이름**, 전 내부
 * 경로, 경로별 **역할 허용목록**. 포털 채널 산출물은 그 채널의 라우트와 셸이 통째로 빠져 이 표를
 * 쓰는 곳이 하나도 없는데도, 번들러의 기본 가정(「import 된 모듈은 부수효과가 있을 수 있다」)
 * 때문에 **표만 실려 나갔다**(실측: `menu:{group:` 19건).
 * ⚠ 포털향은 이 시스템에서 **외부에 노출되는 유일한 향**이라 대상이 정확히 반대다.
 *
 * ## 이 파일이 지킬 수 있는 것과 없는 것
 * 「산출물에서 빠졌는가」는 **빌드를 떠서 재는 수밖에 없다** — 단위 시험은 번들을 만들지 않는다.
 * 그래서 여기서는 그 결과를 떠받치는 **두 전제**를 지킨다:
 *   ① 빌드 설정이 사슬 3모듈을 「부수효과 없음」으로 **선언하고 있다**(값 축)
 *   ② 그 선언이 **참이다** — 세 모듈의 최상위에 부수효과 문장이 없다
 * 그리고 반대 축으로 ③ **관제 채널에서는 표가 살아 있어야 한다**를 함께 본다. ①②만 보면
 * 「표를 통째로 지워서 0건」도 통과하는데 그건 성공이 아니라 관제향 메뉴 소실이다.
 *
 * ⚠ 절제 실험 기록(되풀이 방지): 호출 자리의 `#__PURE__` 표시는 이 결과에 **기여하지 않는다.**
 *   표시만 붙인 판은 19건 그대로였고, 이 선언만 두고 표시를 전부 떼도 0건이었다.
 */

const FRONTEND_ROOT = path.resolve(__dirname, '../../..');
const VITE_CONFIG = readFileSync(path.join(FRONTEND_ROOT, 'vite.config.ts'), 'utf-8');

/**
 * 선언 대상 3모듈 — **사슬 전체**다.
 *
 * 붙잡고 있던 것은 `router/index.tsx` → `AppLayout` → `Lnb` → `routeAccess` 사슬이라, 맨 끝만
 * 선언하면 앞의 둘이 「부수효과가 있을 수 있는 모듈」로 남아 사슬이 끊기지 않는다(실측).
 */
const DECLARED_MODULES = [
  '/src/lib/routeAccess.ts',
  '/src/components/layout/Lnb.tsx',
  '/src/components/layout/AppLayout.tsx',
];

/** 주석을 걷어낸 코드 본문 — 이 저장소는 주석에 결정 근거를 싣는다(설명이 위반으로 잡히면 안 된다). */
function codeOf(file: string): string {
  return readFileSync(file, 'utf-8')
    .split('\n')
    .filter((line) => {
      const t = line.trim();
      return !(t.startsWith('//') || t.startsWith('*') || t.startsWith('/*'));
    })
    .join('\n');
}

describe('내부 IA 표 — 포털 산출물에서 걷히는 전제', () => {
  describe('① 빌드 설정이 사슬 3모듈을 선언한다', () => {
    it('스캔이_실제로_돌았다_설정을_읽어_왔다', () => {
      // 스캐너가 조용히 눈이 멀면 아래 「선언돼 있다」가 공짜로 통과한다.
      expect(VITE_CONFIG.length).toBeGreaterThan(500);
      expect(VITE_CONFIG).toContain('moduleSideEffects');
    });

    it('★사슬_3모듈이_빠짐없이_선언돼_있다', () => {
      // ⚠ 「선언이 하나라도 있다」가 아니라 **셋 전부**를 값으로 못 박는다 — 하나만 빠져도
      //   사슬이 이어져 표가 되살아난다(끝 하나만 선언한 판에서 19건 그대로였다).
      for (const mod of DECLARED_MODULES) {
        expect(VITE_CONFIG).toContain(`'${mod}'`);
      }
    });

    it('선언이_트리셰이킹_옵션_자리에_있다', () => {
      // 목록만 있고 배선이 없으면 아무 일도 하지 않는다.
      expect(VITE_CONFIG).toMatch(/treeshake:\s*\{[\s\S]*moduleSideEffects/);
      expect(VITE_CONFIG).toContain('SIDE_EFFECT_FREE_MODULES');
    });
  });

  describe('② 그 선언이 참이다 — 최상위에 부수효과 문장이 없다', () => {
    it.each(DECLARED_MODULES)('%s 의 최상위에 부수효과 문장이 없다', (mod) => {
      const code = codeOf(path.join(FRONTEND_ROOT, mod.replace(/^\//, '')));
      // 최상위에서 시작하는 문장(들여쓰기 0) 중 선언·제어구문이 아닌 것 = 부수효과 후보.
      const offenders = code
        .split('\n')
        .filter((line) => line.length > 0 && !/^\s/.test(line))
        .filter(
          (line) =>
            !/^(import|export|const|let|function|interface|type|class|enum|declare|\}|\)|\]|;|`)/.test(
              line.trim(),
            ),
        );

      expect(offenders).toEqual([]);
    });

    it('스캐너가_부수효과_문장을_실제로_잡아낸다_자기_가드', () => {
      // 위 판정식이 통째로 눈이 멀면 「위반 0건」이 공짜가 된다.
      const hostile = ['import { x } from "y";', 'window.addEventListener("load", x);'];
      const offenders = hostile.filter(
        (line) => !/^(import|export|const|let|function|interface|type|class|enum|declare|\}|\)|\]|;|`)/.test(line.trim()),
      );

      expect(offenders).toEqual(['window.addEventListener("load", x);']);
    });
  });

  describe('③ 관제 채널에서는 살아 있어야 한다 — 제거 축과 존치 축은 짝이다', () => {
    /**
     * ★ 이 절이 없으면 「표를 통째로 지워서 포털 0건」도 성공으로 읽힌다. 그건 성공이 아니라
     *   관제향 메뉴가 사라진 것이다. 포털 0건과 관제 존치는 **함께** 성립해야 한다.
     */
    it('★메뉴_선언이_비어_있지_않다', () => {
      expect(INTERNAL_ROUTE_ACCESS.length).toBeGreaterThan(20);
      expect(MENU_GROUP_ORDER.length).toBeGreaterThan(0);
    });

    it('★메뉴_트리가_실제로_만들어진다', () => {
      const groups = buildMenuGroups({ devUploadEnabled: true });

      expect(groups.length).toBeGreaterThan(0);
      expect(groups.flatMap((g) => g.items).length).toBeGreaterThan(10);
    });

    it('★경로별_역할_판정이_그대로_동작한다', () => {
      // 인가 축이 함께 죽지 않았음을 본다(표가 비면 여기서 전부 빈 목록이 된다).
      expect(allowFor('/dashboard').length).toBeGreaterThan(0);
      expect([...allowFor('/admin/users')]).toEqual([Role.ADMIN]);
      // 선언에 없는 경로는 종전대로 fail-closed.
      expect(allowFor('/nowhere')).toEqual([]);
    });
  });
});
