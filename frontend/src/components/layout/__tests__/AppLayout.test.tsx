// AppLayout — 인증 앱(manage/labeling) 셸 렌더 계약.
//
// ★푸터는 렌더하지 않는다 (2026-08-18 사용자 확정 — 사양 SHELL-001 `footer.enabled=false`).
//   노출 여부와 문안(근거법령·운영기관·문의처)이 확정되기 전까지 자리표시 문구를 화면에 내보내지
//   않는다. 이 단언은 **부재가 의도임을 고정**하는 가드다 — 과거 "Footer 는 mock 에 없으므로 제거"
//   되었다가 누락 결함으로 되돌려진 이력이 있어, 근거 없이 다시 마운트되는 것을 막는다.
//   문안이 확정되면 사양(footer.enabled)을 먼저 되돌린 뒤 이 가드를 재노출 단언으로 교체한다.

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

  it('AppLayout_푸터_미노출', () => {
    const { container } = renderLayout();

    // 하단 푸터 자체가 없고, 자리표시 문안도 화면 어디에도 나가지 않는다.
    expect(container.querySelector('footer')).toBeNull();
    expect(screen.queryByText(/근거법령/)).toBeNull();
    expect(screen.queryByText(/운영기관/)).toBeNull();
    expect(screen.queryByText(/문의처/)).toBeNull();
  });

  it('AppLayout_자식_컨텐츠_렌더', () => {
    renderLayout();

    expect(screen.getByTestId('app-content')).toBeInTheDocument();
  });
});
