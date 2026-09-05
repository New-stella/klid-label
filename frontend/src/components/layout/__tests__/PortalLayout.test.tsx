import { afterEach, describe, expect, it, vi } from 'vitest';
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

/** 이동 탭 배선 확인용 — 레이아웃 아래의 임의 자식 경로에서 렌더한다. */
function renderLayoutAt(pathname: string) {
  return render(
    <MemoryRouter initialEntries={[pathname]}>
      <Routes>
        <Route path="/portal" element={<PortalLayout />}>
          <Route index element={<div data-testid="portal-content">CHILD</div>} />
          <Route path="uploads" element={<div data-testid="portal-content">UPLOADS</div>} />
          <Route path="label/:id" element={<div data-testid="portal-content">LABELING</div>} />
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

  /*
   * ★포털 채널(Module Federation 임베드) 빌드에서의 머리 영역 토글 (2026-08-26 신설).
   *
   * 저작도구가 포털 Host 셸 안에 Remote 로 임베드될 예정이라, `VITE_BUILD_CHANNEL=portal`
   * 로 빌드된 산출물에서는 `PortalLayout` 자체 헤더를 렌더하지 않는다(Host 가 이미 자기
   * 헤더를 갖고 있어, 그대로 두면 한 화면에 머리 영역이 두 벌 겹친다).
   *
   * 기본값(환경변수 미설정 = `control` 채널)에서는 지금처럼 헤더가 렌더된다 — 이 사실은
   * 이 파일의 다른 테스트들(예: `포털_레이아웃_GNB_단순화_LNB_없음`)이 이미 지키고 있고,
   * 여기서는 "채널을 명시하지 않으면 지금과 같다"는 것을 한 번 더 직접 단언한다.
   */
  describe('빌드 채널에 따른 머리 영역 토글', () => {
    afterEach(() => {
      vi.unstubAllEnvs();
    });

    it('채널_미설정_기본값이면_지금처럼_헤더가_렌더된다', () => {
      vi.stubEnv('VITE_BUILD_CHANNEL', '');

      renderLayout();

      expect(document.querySelector('header')).not.toBeNull();
      expect(screen.getByText('AI 학습데이터 포털')).toBeInTheDocument();
    });

    it('control_채널이면_헤더가_렌더된다', () => {
      vi.stubEnv('VITE_BUILD_CHANNEL', 'control');

      renderLayout();

      expect(document.querySelector('header')).not.toBeNull();
    });

    it('portal_채널이면_자체_헤더를_렌더하지_않는다_Host가_자기헤더를_갖는다', () => {
      vi.stubEnv('VITE_BUILD_CHANNEL', 'portal');

      renderLayout();

      // 자체 헤더(제목·사용자 메뉴)는 없다.
      expect(document.querySelector('header')).toBeNull();
      expect(screen.queryByText('AI 학습데이터 포털')).toBeNull();
      // 그 아래 자식 콘텐츠(Outlet)는 여전히 정상 렌더된다 — 헤더만 빠질 뿐 나머지는 불변.
      expect(screen.getByTestId('portal-content')).toBeInTheDocument();
    });
  });

  /*
   * ★본문 상단 이동 탭 배선 (2026-09-02 사용자 확정, 구속 — 사양 SHELL-002 `portal-content-tabs`).
   *
   * Host 가 머리 영역과 좌측 주 메뉴를 **둘 다** 소유하므로 목적지 이동은 본문 상단 탭이 맡는다.
   * 탭 자체의 항목·노출 판정은 `PortalContentTabs.test.tsx` 가 지키고, 여기서는 **레이아웃이
   * 그것을 실제로 마운트하는가**(배선)만 본다 — 컴포넌트만 만들고 붙이지 않으면 화면에서는
   * 아무 일도 일어나지 않는데 그쪽 시험은 전부 초록이다.
   */
  describe('본문 상단 이동 탭', () => {
    it('목적지_화면의_본문_맨_위에_이동_탭이_붙는다', () => {
      renderLayoutAt('/portal');

      const nav = screen.getByRole('navigation', { name: '포털 이동 탭' });
      const main = document.querySelector('main');
      expect(main).not.toBeNull();
      // 본문 안에 있고(머리 영역이 아니다) 자식 콘텐츠보다 앞선다.
      expect(main?.contains(nav)).toBe(true);
      expect(
        nav.compareDocumentPosition(screen.getByTestId('portal-content')) &
          Node.DOCUMENT_POSITION_FOLLOWING,
      ).toBeTruthy();
    });

    it('몰입_편집_화면에서는_레이아웃이_이동_탭을_그리지_않는다', () => {
      renderLayoutAt('/portal/label/42');

      expect(screen.queryByRole('navigation', { name: '포털 이동 탭' })).toBeNull();
      expect(screen.getByTestId('portal-content')).toBeInTheDocument();
    });
  });

  /*
   * ★좌측 레일 미노출 가드 (2026-09-02 사용자 확정, 구속 — 사양 SHELL-002 `sidenav.enabled=false`).
   *
   * Host 가 좌측 주 메뉴를 소유한다. 우리가 레일을 그리면 한 화면에 왼쪽 레일이 두 벌이 되어
   * Host 화면과 부딪힌다. **이 축은 두 번 뒤집혔고 지금이 세 번째 확정이라**, 부재를 결손으로
   * 오인해 다시 붙이는 것을 막는 것이 이 가드의 목적이다.
   */
  it('포털_좌측_레일_미노출', () => {
    const { container } = renderLayout();

    expect(container.querySelector('aside')).toBeNull();
    expect(screen.queryByRole('navigation', { name: '좌측 메뉴' })).toBeNull();
    // 이동 탭은 본문 안의 가로 탭이라 이 가드에 걸리지 않는다(같은 `nav` 요소지만 이름이 다르다).
    expect(screen.getByRole('navigation', { name: '포털 이동 탭' })).toBeInTheDocument();
  });
});
