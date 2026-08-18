import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';

import {
  DisplayNameSourceChip,
  type DisplayNameSource,
} from '@/components/common/DisplayNameSourceChip';

/**
 * 표시명 출처 칩 (UI-126) — 4 variant.
 *
 * 이 컴포넌트는 <서버가 내려준 출처 표기를 그대로 보여줄 뿐> 해석을 다시 계산하지 않는다.
 * 그래서 props 가 source 하나다 — 원본 이름 필드를 받지 않는 것이 계약이다.
 */
describe('DisplayNameSourceChip', () => {
  const cases: [DisplayNameSource, string][] = [
    ['operator', '운영자 지정'],
    ['control', '관제 수신명'],
    ['category', '카테고리명'],
    ['code', '유형코드 그대로'],
  ];

  it.each(cases)('%s variant 는 %s 로 표기된다', (source, label) => {
    // given / when
    render(<DisplayNameSourceChip source={source} />);

    // then: 칩 글자가 곧 뜻이다(색상 단독 구분 금지)
    expect(screen.getByText(label)).toBeInTheDocument();
  });

  it('4_variant_의_배경과_글자색이_서로_구분된다', () => {
    // given / when
    const classes = cases.map(([source]) => {
      const { container, unmount } = render(<DisplayNameSourceChip source={source} />);
      const cls = container.firstElementChild?.className ?? '';
      unmount();
      return cls;
    });

    // then: variant 별로 다른 색 조합을 갖는다(같은 클래스면 구분이 없다)
    expect(new Set(classes).size).toBe(4);
  });
});
