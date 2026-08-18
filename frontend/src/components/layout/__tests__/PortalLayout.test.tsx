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

  /*
   * ★푸터 미노출 가드 (2026-08-18 사용자 확정 — 사양 SHELL-002 `footer.enabled=false`).
   *   노출 여부와 문안(근거법령·운영기관·문의처)이 확정되기 전까지 자리표시 문구를 내보내지 않는다.
   *   **부재는 결손이 아니라 이 결정의 결과다** — 근거 없이 다시 마운트되는 것을 막는 가드이며,
   *   문안이 확정되면 사양을 먼저 되돌린 뒤 이 가드를 재노출 단언으로 교체한다.
   */
  it('포털_푸터_미노출', () => {
    const { container } = renderLayout();

    expect(container.querySelector('footer')).toBeNull();
    expect(screen.queryByText(/근거법령/)).toBeNull();
    expect(screen.queryByText(/문의처/)).toBeNull();
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

  /*
   * ★ 반전된 가드 — 구 케이스 `다운로드_메뉴_미존재_V1_5` 를 대체한다(지우지 않고 뒤집는다).
   *
   * 구 단언: 포털 레이아웃 전체 텍스트에 «다운로드» 가 **한 글자도 없어야 한다**.
   * 새 단언: 자식 화면이 그린 다운로드 UI 가 **레이아웃을 통과해 보인다**. 다만 GNB 자체에는
   *          전역 다운로드 메뉴를 두지 않는다 — 다운로드는 「영상 1건의 작업 데이터」 단위 행위라
   *          대상 없이 누를 수 있는 전역 메뉴가 성립하지 않기 때문이다(사양 SCREEN-028: 버튼은
   *          영상 카드에 붙는다).
   *
   * 왜 뒤집혔나 — 구 V1.5 "포털 다운로드는 포털 시스템 자체 책임" 정책이 폐기되고 저작도구가
   * 제공하는 것으로 확정됐다. 구 단언은 문서 전체를 훑으므로 **자식 화면의 정상 다운로드 버튼까지
   * 실패로 만든다**(레이아웃 안에서 렌더되는 순간 걸린다).
   */
  it('자식_화면의_다운로드_UI를_가리지_않되_GNB에_전역_다운로드_메뉴는_두지_않는다_V1_5_미제공_정책_폐기', () => {
    render(
      <MemoryRouter initialEntries={['/portal']}>
        <Routes>
          <Route path="/portal" element={<PortalLayout />}>
            <Route
              index
              element={
                <button type="button" data-testid="portal-content">
                  작업 데이터 다운로드
                </button>
              }
            />
          </Route>
        </Routes>
      </MemoryRouter>,
    );

    // 자식 화면이 제공하는 다운로드 UI 는 그대로 보인다(구 가드는 이것을 실패로 만들었다).
    expect(screen.getByRole('button', { name: '작업 데이터 다운로드' })).toBeInTheDocument();
    // 레이아웃 자신(GNB)은 전역 다운로드 메뉴를 갖지 않는다 — 대상 영상 없이 누를 수 없는 행위다.
    const header = document.querySelector('header');
    expect(header).not.toBeNull();
    expect(header?.textContent ?? '').not.toMatch(/다운로드/);
  });
});
