// 폼 컨트롤 모서리 반경 일관성 가드.
//
// ★ 왜 필요한가
//   `Input` 만 `rounded-lg`(8px)이고 나머지 넷(`Select`/`Textarea`/`FileInput`/`DatePicker`)은
//   `rounded-md`(6px)였다. 같은 폼에서 나란히 놓이면 텍스트 입력만 혼자 둥글어 보인다.
//   사람 눈으로는 2px 차이를 리뷰에서 잡아내기 어렵고, 새 컨트롤을 만들 때 옆 파일을 보고
//   복사하는 습관 탓에 한 번 갈라지면 계속 번진다. 그래서 기계로 고정한다.
//
// ★ 값 자체가 아니라 "다섯이 같다"를 검사한다
//   확정 결정은 "다수(md)에 맞춘다"이지 "6px 이어야 한다"가 아니다. 따라서 토큰을 통째로
//   바꾸는 향후 결정(예: 전부 lg)은 이 가드를 건드리지 않고 통과해야 정상이다 — 다만 그 경우
//   `EXPECTED` 한 줄만 바꾸면 되고, **한 컨트롤만 몰래 갈라지는 것**은 반드시 실패한다.
//
// ⚠ 이 가드가 못 보는 것
//   - 필드 본체가 아닌 부속 요소(Select 의 드롭다운 패널·옵션 항목, DatePicker 의 달력 팝업)는
//     대상이 아니다. 그것들은 필드가 아니라 오버레이라 반경 축이 다르다.
//   - 런타임 실제 렌더 결과가 아니라 **소스의 클래스 문자열**을 본다. `className` prop 으로
//     바깥에서 덮어쓰는 호출부는 잡히지 않는다.
//   - tailwind 토큰 값(md=6px)의 검증은 여기가 아니라 designTokens.test.ts 소관이다.
//
// ⚠ mutation 확인 절차: Input.tsx 의 `rounded-md` 를 `rounded-lg` 로 되돌리면
//   '다섯_폼_컨트롤이_같은_모서리_반경을_쓴다' 가 Input.tsx 를 지목하며 FAIL 해야 한다. (실제로 확인함)

import fs from 'node:fs';
import path from 'node:path';

import { describe, expect, it } from 'vitest';

const COMMON_DIR = path.resolve(__dirname, '../components/common');

/** 폼 컨트롤 5종의 **필드 본체** 클래스 줄을 특정하는 앵커. */
const FIELD_CONTROLS: ReadonlyArray<{ file: string; anchor: string }> = [
  { file: 'Input.tsx', anchor: 'h-11 w-full' },
  { file: 'Textarea.tsx', anchor: 'min-h-11 max-h-[50vh] w-full' },
  { file: 'Select.tsx', anchor: 'flex w-full items-center justify-between' },
  { file: 'DatePicker.tsx', anchor: 'h-11 w-full' },
  // 파일 입력은 <input type="file"> 자체에 테두리가 없고 **선택 버튼**(::file-selector-button)이
  // 시각적 필드 역할을 한다. 그래서 앵커도 반경도 `file:` 접두 쪽을 본다.
  { file: 'FileInput.tsx', anchor: 'file:mr-3' },
];

const EXPECTED = 'md';

/** 앵커가 있는 줄에서 (file: 접두 포함) 반경 토큰 하나를 뽑는다. */
function fieldRadiusOf(file: string, anchor: string): string {
  const src = fs.readFileSync(path.join(COMMON_DIR, file), 'utf-8');
  const line = src.split('\n').find((l) => l.includes(anchor));
  if (!line) throw new Error(`${file}: 앵커 '${anchor}' 를 찾지 못했다 — 클래스 구성이 바뀌었다면 앵커를 갱신할 것`);

  const match = /(?:^|\s)(?:file:)?rounded-([a-z]+)/.exec(line);
  if (!match) throw new Error(`${file}: 필드 줄에 rounded-* 가 없다`);
  return match[1];
}

describe('폼 컨트롤 — 모서리 반경 일관성', () => {
  it('다섯_폼_컨트롤이_같은_모서리_반경을_쓴다', () => {
    // given/when: 각 컨트롤의 필드 본체 반경
    const actual = Object.fromEntries(
      FIELD_CONTROLS.map(({ file, anchor }) => [file, fieldRadiusOf(file, anchor)]),
    );

    // then: 다섯이 모두 같은 토큰이어야 한다. 어긋난 파일이 값과 함께 드러난다.
    expect(actual).toEqual({
      'Input.tsx': EXPECTED,
      'Textarea.tsx': EXPECTED,
      'Select.tsx': EXPECTED,
      'DatePicker.tsx': EXPECTED,
      'FileInput.tsx': EXPECTED,
    });
  });

  it('Input_이_혼자_lg로_되돌아가지_않는다', () => {
    // 위 테스트가 이미 덮지만, 되돌림의 구체적 형태를 이름으로 남겨 둔다
    // (실제로 발생했던 드리프트라 리뷰어가 검색으로 찾을 수 있어야 한다).
    //
    // ⚠ 파일 전체에서 'rounded-lg' 문자열을 찾는 방식은 쓰지 않는다 — 구 값을 설명하는
    //    <b>주석</b>에도 그 문자열이 들어 있어 오탐이 난다(실제로 이 가드를 처음 쓸 때 걸렸다).
    //    검사 대상은 언제나 필드 본체의 클래스 줄이다.
    expect(fieldRadiusOf('Input.tsx', 'h-11 w-full')).not.toBe('lg');
  });
});
