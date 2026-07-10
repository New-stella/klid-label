// AppLayout — 인증 앱(manage/labeling) 전 화면에 근거법령·문의처 Footer 가 렌더되어야 한다.
// 기존엔 "Footer는 mock에 없으므로 제거" 되어 공공 필수 정보가 인증 앱 전체에서 누락됐다.

import { afterEach, describe, expect, it } from 'vitest';
import { cleanup, screen } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { render } from '@testing-library/react';

import { AppLayout } from '../AppLayout';
import { useAuthStore } from '@/stores/useAuthStore';

function renderLayout() {
  useAuthStore.setState({
    claims: { sub: '1', role: 'REVIEWER', channel: 'INTERNAL', exp: 9999999999 },
  });
  return render(
    <MemoryRouter initialEntries={['/dashboard']}>
      <Routes>
        <Route path="/" element={<AppLayout />}>
          <Route path="dashboard" element={<div data-testid="app-content">CHILD</div>} />
        </Route>
      </Routes>
    </MemoryRouter>,
  );
}

describe('AppLayout', () => {
  afterEach(() => {
    useAuthStore.getState().clear();
    cleanup();
  });

  it('AppLayout_Footer_렌더', () => {
    const { container } = renderLayout();

    // 인증 앱 하단에 footer(근거법령·문의처) 존재
    const footer = container.querySelector('footer');
    expect(footer).not.toBeNull();
    expect(footer?.textContent).toMatch(/근거법령/);
    expect(footer?.textContent).toMatch(/문의처/);

    // 자식 컨텐츠는 여전히 렌더
    expect(screen.getByTestId('app-content')).toBeInTheDocument();
  });
});
