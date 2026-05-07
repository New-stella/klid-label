import { describe, expect, it } from 'vitest';
import { render, screen } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';

import { PortalLayout } from '../PortalLayout';

function renderLayout() {
  return render(
    <MemoryRouter initialEntries={['/portal']}>
      <Routes>
        <Route path="/portal" element={<PortalLayout />}>
          <Route index element={<div data-testid="portal-content">CHILD</div>} />
        </Route>
      </Routes>
    </MemoryRouter>,
  );
}

describe('PortalLayout', () => {
  it('포털_레이아웃_GNB_단순화_LNB_없음', () => {
    renderLayout();

    // GNB 제목 노출
    expect(screen.getByText(/포털/)).toBeInTheDocument();
    // 자식 컨텐츠 렌더
    expect(screen.getByTestId('portal-content')).toBeInTheDocument();
    // 내부 메뉴 (영상/작업관리/통계/관리) 미노출
    expect(screen.queryByText('작업관리')).toBeNull();
    expect(screen.queryByText('관리')).toBeNull();
  });

  it('Mobile_768px_뷰포트에서_포털_레이아웃_정상_렌더', () => {
    // jsdom: window.innerWidth 직접 설정
    Object.defineProperty(window, 'innerWidth', { value: 375, writable: true, configurable: true });
    window.dispatchEvent(new Event('resize'));

    renderLayout();
    expect(screen.getByTestId('portal-content')).toBeInTheDocument();

    // 원복
    Object.defineProperty(window, 'innerWidth', { value: 1024, writable: true, configurable: true });
  });

  it('다운로드_메뉴_미존재_V1_5', () => {
    const { container } = renderLayout();
    expect(container.textContent).not.toMatch(/다운로드/);
  });
});
