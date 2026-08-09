// 라벨링 우측 패널이 공통 컴포넌트에 넘기는 className override 가드.
//
// 배경: 이 패널들은 예전에 다크 톤이었고, 라이트 기본값인 공통 컴포넌트
// (Button/Textarea/Select/Checkbox)를 색 override(META_DARK_*)로 덮어썼다.
// 라이트로 통일하면서 그 색 override 는 전부 폐기됐고, 지금 패널이 넘기는 className 은
// 레이아웃/여백/크기(`resize-y`·`mt-3`·`flex-1`·`text-sm`)뿐이다 — <b>색은 하나도 없다</b>.
//
// 이 파일이 지키는 것은 두 가지다.
//
//  ① 공통 컴포넌트의 <b>라이트 기본값</b>이 override 에 지워지지 않는다.
//  ② ★<b>twMerge 폰트 크기 삼킴 — 2026-08-06 근본 수정됨</b>.
//
//     [구 동작 → 폐기] tailwind-merge 기본 설정은 이 프로젝트의 커스텀 fontSize 토큰
//     (`text-body`/`text-sub` 등)을 <b>색 그룹으로 오인</b>해, 같은 병합에서 뒤따르는 색 클래스에
//     밀려 <b>조용히 지웠다</b>. 그래서 공통 `Textarea`/`Select` 는 자기 기본 문자열
//     (`... text-body text-gray-900 ...`)이 <b>스스로 충돌</b>해 크기 클래스 없이 렌더됐고,
//     이 파일은 그 사실(크기 소멸)을 "정상"으로 단언하고 있었다.
//
//     [현 동작] `src/lib/cn.ts` 가 `extendTailwindMerge` 로 커스텀 토큰을 `font-size` 그룹에
//     등록한다. 이제 크기와 색은 <b>다른 그룹이라 공존</b>하고, 크기끼리만 정상 충돌한다.
//     → 공통 `Textarea`/`Select` 는 <b>기본 크기 `text-body`(17px)를 실제로 갖는다</b>.
//     → 밀집 패널(w-72)의 <b>명시 크기 override 는 여전히 필요</b>하다 —
//       예전엔 twMerge 가 크기를 인식하지 못해 "우연히" 살아남았지만, 지금은 나중에 와서
//       <b>정상적으로 기본값을 이긴다</b>. 그 명시를 지우면 밀집 패널이 17px 로 커진다.
//     → 공통 컨트롤에 색을 덧칠해도 더 이상 크기가 사라지지 않는다(마지막 테스트가 고정).
//       계약 원본은 `src/lib/__tests__/cn.test.ts`.

import { render } from '@testing-library/react';
import { describe, expect, it } from 'vitest';

import { Button } from '@/components/common/Button';
import { Checkbox } from '@/components/common/Checkbox';
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/common/Select';
import { Textarea } from '@/components/common/Textarea';
// ★ 앱이 실제로 쓰는 병합기로 단언한다. 라이브러리의 raw `twMerge` 로 단언하면
//   커스텀 그룹 등록(`cn.ts`)을 우회해 <b>고쳐진 뒤에도 구 동작이 통과</b>한다.
import { cn } from '@/lib/cn';

// 진실원은 tailwind 설정이다 — 크기 토큰 목록을 여기 하드코딩하면 ladder step 이 늘어날 때
// 가드가 조용히 뒤처진다(실제로 `text-label` 도입 시 이 목록이 못 알아봐 오탐이 났다).
// eslint-disable-next-line @typescript-eslint/ban-ts-comment
// @ts-expect-error — 설정 파일에 타입 선언이 없다(의도적). 아래에서 필요한 형태로 좁혀 쓴다.
import tailwindConfig from '../../../../../tailwind.config.js';

/**
 * 라벨링 패널이 실제로 공통 컨트롤에 넘기는 override — 레이아웃/여백/크기 전용(<b>색 없음</b>).
 * 밀집 패널(w-72)이라 공통 기본값(`text-body` 17px)에 크기를 명시로 덮어쓴다.
 *
 * ⚠ 2026-08-06: 원시 스케일(`text-sm`/`text-xs`)에서 DS-001 ladder step 으로 재배정됐다 —
 *   `body-md`(17px, Textarea 본문) / `caption`(14px, 밀집 Select). <b>크기 값은 그대로</b>다.
 */
const PANEL_OVERRIDES = [
  'resize-y text-body-md',
  'text-body-md',
  'mt-3 text-body-md',
  'rounded px-2 text-caption',
];

/** 크기를 싣지 않는 순수 레이아웃 override — 크기는 없어도 되지만 색은 절대 없어야 한다. */
const LAYOUT_ONLY_OVERRIDES = ['mt-3', 'flex-1'];

function classesOf(el: Element | null): string[] {
  return (el?.getAttribute('class') ?? '').split(/\s+/).filter(Boolean);
}

/** 이 파일 전용 최소 Select 조립 — 트리거 className override 검증에만 쓴다. */
function TestSelect({ className }: { className?: string }) {
  return (
    <Select value="a" onValueChange={() => {}}>
      <SelectTrigger className={className}>
        <SelectValue />
      </SelectTrigger>
      <SelectContent>
        <SelectItem value="a">a</SelectItem>
      </SelectContent>
    </Select>
  );
}

/** Select 트리거(button) 조회 — `data-slot="select-trigger"` 로 고정 식별한다. */
function selectTriggerOf(container: HTMLElement): Element | null {
  return container.querySelector('[data-slot="select-trigger"]');
}

/**
 * 글자 크기 클래스가 최종 렌더에 남았는지 확인한다(위 ② 함정 가드).
 *
 * 인정 범위는 `tailwind.config.js` 의 `fontSize` 키 <b>전량</b>이다 — DS-001 ladder 15단
 * (`label`·`caption`·`button`·`title-*` …)과 레거시 별칭(`body`·`sub` …), 표준 스케일까지
 * 모두 포함된다. 하드코딩 목록이 아니라 설정에서 파생하므로 step 이 늘어도 드리프트가 없다.
 */
const FONT_SIZE_TOKENS: string[] = Object.keys(
  (tailwindConfig as { theme: { extend: { fontSize: Record<string, unknown> } } }).theme.extend
    .fontSize,
);
const FONT_SIZE_RE = new RegExp(
  `^text-(${FONT_SIZE_TOKENS.map((t) => t.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')).join('|')})$`,
);

function expectHasFontSizeClass(cls: string[], label: string) {
  const sizeClasses = cls.filter((c) => FONT_SIZE_RE.test(c));
  expect(
    sizeClasses.length,
    `${label} 에 글자 크기 클래스가 없다(twMerge 가 삼킴)`,
  ).toBeGreaterThan(0);
}

/** 라이트 기본값이 override 에 지워지지 않았는지 확인한다. */
function expectKeepsLightDefaults(cls: string[], label: string, expected: string[]) {
  expect(cls, `${label} 의 라이트 기본값이 override 에 지워졌다`).toEqual(
    expect.arrayContaining(expected),
  );
}

describe('라벨링 패널 — 공통 컨트롤 override', () => {
  it('override_없는_공통_컨트롤은_라이트_기본값을_유지한다', () => {
    const { container: ta } = render(<Textarea />);
    const taCls = classesOf(ta.querySelector('textarea'));
    expectKeepsLightDefaults(taCls, 'Textarea', ['bg-white', 'text-gray-900', 'border-gray-300']);
    // 포커스링(KRDS)도 그대로 살아 있어야 한다.
    expect(taCls).toContain('focus-visible:ring-primary-500');

    const { container: se } = render(<TestSelect />);
    const seCls = classesOf(selectTriggerOf(se));
    expectKeepsLightDefaults(seCls, 'Select', ['bg-white', 'text-gray-900', 'border-gray-300']);

    // Checkbox 의 라이트 기본값은 컨트롤(button)이 아니라 그 안의 시각 사각형(span)이 그린다.
    // 히트영역(44px)과 시각 크기를 분리 책임지기 때문이다(UI-024).
    const { container: cb } = render(<Checkbox aria-label="x" />);
    expectKeepsLightDefaults(classesOf(cb.querySelector('[role="checkbox"] span')), 'Checkbox', [
      'bg-white',
      'border-gray-300',
    ]);
    expect(classesOf(cb.querySelector('[role="checkbox"]'))).toContain(
      'focus-visible:ring-primary-500',
    );

    const { container: bt } = render(
      <Button size="sm" fullWidth>
        저장
      </Button>,
    );
    const btCls = classesOf(bt.querySelector('button'));
    expectHasFontSizeClass(btCls, 'Button(save)');
    // 라이트 기본 disabled 톤 — 구 다크 override(`disabled:bg-gray-500`)가 덮던 자리.
    expectKeepsLightDefaults(btCls, 'Button(save)', [
      'bg-primary-600',
      'w-full',
      'disabled:bg-primary-300',
    ]);
  });

  it('★공통_컨트롤의_기본_크기_토큰이_자기충돌에_사라지지_않는다', () => {
    // [구 계약 → 폐기] 예전엔 여기서 `toEqual([])`(크기 소멸)을 <b>정상</b>으로 단언했다.
    // `cn.ts` 의 font-size 그룹 등록으로 자기충돌이 사라져, 이제 기본 크기가 실제로 남는다.
    // 이것이 이번 수정의 실제 시각 변화다 — Textarea/Select 가 상속값이 아니라 17px 로 렌더된다.
    for (const [name, node] of [
      ['Textarea', render(<Textarea />).container.querySelector('textarea')],
      ['Select', selectTriggerOf(render(<TestSelect />).container)],
    ] as const) {
      const cls = classesOf(node);
      expectHasFontSizeClass(cls, `공통 ${name}(override 없음)`);
      expect(cls, `공통 ${name} 의 기본 크기 토큰이 자기 색 클래스에 삼켜졌다`).toContain(
        'text-body',
      );
    }
  });

  it('★패널_override_의_명시_크기가_공통_기본값을_이긴다', () => {
    // 밀집 패널은 기본값(17px)보다 작은 크기를 명시한다. 예전엔 twMerge 가 `text-body` 를
    // 크기로 인식하지 못해 "우연히" 살아남았고, 이제는 같은 그룹의 <b>뒤엣것</b>이라
    // 정상적으로 이긴다 — 결과는 override 하나만 남는 것이다.
    for (const override of PANEL_OVERRIDES) {
      const expectedSize = override.split(' ').filter((t) => FONT_SIZE_RE.test(t));
      const { container } = render(<Textarea className={override} />);
      const cls = classesOf(container.querySelector('textarea'));
      expect(
        cls.filter((c) => FONT_SIZE_RE.test(c)),
        `Textarea(className="${override}") 의 명시 크기가 기본값을 이기지 못했다`,
      ).toEqual(expectedSize);
      expectKeepsLightDefaults(cls, `Textarea(className="${override}")`, [
        'bg-white',
        'text-gray-900',
      ]);
    }
  });

  it('밀집_Select_override_도_크기를_명시하고_라이트_기본값을_지킨다', () => {
    // ObjectAttributePanel·ObjectAttributeSection 의 DENSE_SELECT_CLASS.
    const { container } = render(<TestSelect className="rounded px-2 text-caption" />);
    const cls = classesOf(selectTriggerOf(container));
    // 명시 크기 하나만 남아야 한다(기본 `text-body` 는 같은 그룹이라 밀려난다).
    expect(
      cls.filter((c) => FONT_SIZE_RE.test(c)),
      'Select(dense) 의 명시 크기',
    ).toEqual(['text-caption']);
    expectKeepsLightDefaults(cls, 'Select(dense)', [
      'bg-white',
      'text-gray-900',
      'border-gray-300',
    ]);
  });

  it('LabelHeader_아이콘버튼의_테두리_override_는_크기_클래스를_삼키지_않는다', () => {
    // ghost 변형에는 테두리가 없어 헤더가 직접 얹는다. 색이 함께 오므로 크기 유실을 확인한다.
    for (const override of [
      'h-8 w-8 border border-gray-300 p-0',
      'border border-primary-600 bg-primary-50 text-primary-700 hover:bg-primary-50',
    ]) {
      const { container } = render(
        <Button variant="ghost" size="sm" className={override}>
          히스토리
        </Button>,
      );
      expectHasFontSizeClass(classesOf(container.querySelector('button')), `Button(${override})`);
    }
  });

  it('순수_레이아웃_override_는_라이트_기본값을_건드리지_않는다', () => {
    for (const override of LAYOUT_ONLY_OVERRIDES) {
      const { container } = render(<Textarea className={override} />);
      const cls = classesOf(container.querySelector('textarea'));
      expectKeepsLightDefaults(cls, `Textarea(className="${override}")`, [
        'bg-white',
        'text-gray-900',
      ]);
      for (const token of override.split(' ')) {
        expect(cls, `override "${token}" 자체가 유실됐다`).toContain(token);
      }
    }
  });

  it('★색_override_가_더는_글자크기_클래스를_삼키지_않는다', () => {
    // [구 계약 → 폐기] 예전엔 `twMerge('text-body text-gray-900') === 'text-gray-900'`
    // (크기 소멸)을 단언하고, 그 사실을 근거로 "공통 컨트롤에 색 덧칠 금지" 규칙을 뒀다.
    // `cn.ts` 가 커스텀 토큰을 font-size 그룹으로 등록해 크기·색이 다른 축이 되었으므로
    // 둘은 공존한다. 계약 원본은 `src/lib/__tests__/cn.test.ts`.
    expect(cn('text-body', 'text-gray-900').split(' ')).toEqual(
      expect.arrayContaining(['text-body', 'text-gray-900']),
    );
    expect(cn('text-sub', 'text-gray-500').split(' ')).toEqual(
      expect.arrayContaining(['text-sub', 'text-gray-500']),
    );
    // 레이아웃 전용 override 도 종전대로 안전하다.
    expect(cn('text-body', 'resize-y').split(' ')).toEqual(['text-body', 'resize-y']);

    // 실렌더로도 같은 결과: 색 override 를 줘도 Textarea 의 기본 크기(text-body)가 살아남는다.
    const { container } = render(<Textarea className="text-gray-900" />);
    const cls = classesOf(container.querySelector('textarea'));
    expectHasFontSizeClass(cls, 'Textarea(className="text-gray-900")');
    expect(cls).toContain('text-body');
    expect(cls).toContain('text-gray-900');
  });
});
