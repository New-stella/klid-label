import { describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';

import { LabelHeader } from '../LabelHeader';

describe('LabelHeader', () => {
  it('LabelHeader_액션_primary_토큰', () => {
    // given: 다크 헤더의 저장 액션 버튼
    render(
      <MemoryRouter>
        <LabelHeader
          currentFrame={0}
          totalFrames={10}
          objectCount={3}
          dirty={false}
          showHistory={false}
          onSave={vi.fn()}
        />
      </MemoryRouter>,
    );

    // when: 저장 버튼 클래스 확인
    const save = screen.getByTestId('label-header-save');

    // then: raw blue 팔레트가 아닌 KRDS primary 토큰 사용 (다크 배경 대비 유지 셰이드)
    expect(save.className).toMatch(/bg-primary-/);
    expect(save.className).not.toMatch(/bg-blue-/);
  });
});
