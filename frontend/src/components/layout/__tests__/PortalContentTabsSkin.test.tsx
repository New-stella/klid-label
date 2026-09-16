// 회귀 가드 — 이동 탭이 KRDS 탭의 «생김새»를 입되 «의미»는 내비게이션이다. [@design SHELL-002]
//
// 2026-09-16 사용자 지적 — 「탭 컴포넌트가 좀 다른거 같지않니」.
// 종전에는 같은 모양을 Tailwind 로 손수 그렸는데 밑줄 굵기·활성 글자색이 부모 포털과 갈려
// 있었다. 손으로 옮겨 적은 값은 테마가 바뀌어도 따라오지 않는다 — 킷 클래스를 입으면 토큰이
// 값을 정한다.
//
// ★그런데 **부품(`Tab`/`TabTrigger`)은 쓰지 않는다.** 이것은 탭이 아니라 이동이다:
//   ①킷 트리거는 `<button>` 이라 가운데 클릭 · 새 탭 · 주소 복사가 사라지고
//   ②ARIA `tablist`/`tab` 은 «같은 문서 안의 tabpanel» 을 전제한다 — 서로 다른 주소로 가는
//     길에 그 역할을 붙이면 보조기술에 거짓말이 된다.
//   이 가드는 그 둘(생김새는 킷 · 의미는 이동)을 동시에 고정한다.

import { describe, expect, it } from 'vitest';
import { screen } from '@testing-library/react';

import { renderWithProviders } from '@/test/renderWithProviders';
import { PortalContentTabs } from '../PortalContentTabs';

function renderTabs(path = '/portal') {
  return renderWithProviders(<PortalContentTabs />, {
    initialEntries: [path],
    routes: [{ path, element: <PortalContentTabs /> }],
  });
}

describe('포털 이동 탭 — 생김새는 킷, 의미는 이동', () => {
  describe('① 킷 생김새를 입는다', () => {
    it('★킷 탭 짜임(krds-tab-area > .tab.line > ul > li.tab-item)을 그대로 쓴다', () => {
      const { container } = renderTabs();
      expect(container.querySelector('.krds-tab-area')).not.toBeNull();
      expect(container.querySelector('.tab.line')).not.toBeNull();
      expect(container.querySelectorAll('li.tab-item').length).toBeGreaterThan(1);
    });

    it('★활성 항목만 `active` 를 진다 — 밑줄·색은 킷이 그 표식으로 그린다', () => {
      const { container } = renderTabs();
      const active = container.querySelectorAll('li.tab-item.active');
      expect(active).toHaveLength(1);
      expect(active[0].textContent).toContain('내 작업');
    });

    it('★모든 항목이 킷 트리거 클래스를 입는다', () => {
      const { container } = renderTabs();
      const items = container.querySelectorAll('li.tab-item');
      for (const li of items) {
        expect(li.querySelector('.btn-tab'), li.textContent ?? '').not.toBeNull();
      }
    });

    it('★★색·굵기를 손으로 옮겨 적지 않는다 — 토큰이 값을 정한다', () => {
      // 구 동작: `border-b-4 border-primary-500 text-primary-600` 을 직접 박았다.
      // 그 리터럴이 되살아나면 테마가 바뀌어도 따라오지 않아 부모 포털과 다시 갈린다.
      const { container } = renderTabs();
      const html = container.innerHTML;
      expect(html).not.toMatch(/border-b-4/);
      expect(html).not.toMatch(/border-primary-\d/);
    });
  });

  describe('② 의미는 내비게이션이다', () => {
    it('★★탭 역할을 붙이지 않는다 — 같은 문서의 tabpanel 이 아니다', () => {
      const { container } = renderTabs();
      expect(container.querySelector('[role="tablist"]')).toBeNull();
      expect(container.querySelector('[role="tab"]')).toBeNull();
      expect(container.querySelector('[role="presentation"]')).toBeNull();
    });

    it('★항목이 링크다 — 가운데 클릭 · 새 탭 · 주소 복사가 살아 있다', () => {
      renderTabs();
      const links = screen.getAllByRole('link');
      expect(links.length).toBeGreaterThan(1);
      for (const a of links) expect(a).toHaveAttribute('href');
    });

    it('★지금 어디인지를 `aria-current` 로 알린다', () => {
      renderTabs();
      const current = screen.getAllByRole('link').filter((a) => a.getAttribute('aria-current') === 'page');
      expect(current).toHaveLength(1);
      expect(current[0]).toHaveTextContent('내 작업');
    });

    it('★★화면 밖 글 「선택됨」을 두지 «않는다» — `aria-current` 가 이미 말한다', () => {
      renderTabs();
      // 킷 탭은 그 글을 두지만 이쪽은 이동이다. 더하면 링크 이름이 「내 작업 선택됨」이 되어
      // 같은 사실이 두 번 들린다(2026-09-16 기존 가드가 이름이 바뀐 것을 잡았다).
      expect(screen.queryByText('선택됨')).toBeNull();
      expect(screen.getByRole('link', { name: '내 작업' })).toHaveAttribute('aria-current', 'page');
    });

    it('묶음 이름은 그대로다 — 이동 수단임을 이름이 말한다', () => {
      renderTabs();
      expect(screen.getByRole('navigation', { name: '포털 이동 탭' })).toBeInTheDocument();
    });
  });
});
