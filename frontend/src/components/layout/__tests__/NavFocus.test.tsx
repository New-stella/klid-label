// R2 — 모든 인터랙티브 focus 3px+2offset(WCAG). GNB/LNB 링크에 KRDS_FOCUS 적용 검증.
// KRDS_FOCUS = focus-visible:ring-[3px] + focus-visible:ring-offset-2 + primary.

import { afterEach, describe, expect, it } from 'vitest';
import { cleanup, screen } from '@testing-library/react';

import { Gnb } from '../Gnb';
import { Lnb } from '../Lnb';
import { renderWithProviders } from '@/test/renderWithProviders';
import { useAuthStore } from '@/stores/useAuthStore';

const RING_3PX = /focus-visible:ring-\[3px\]/;
const OFFSET_2 = /focus-visible:ring-offset-2/;

describe('Gnb_LNB_링크_KRDS_focus_적용', () => {
  afterEach(() => {
    useAuthStore.getState().clear();
    cleanup();
  });

  function setReviewer() {
    useAuthStore.setState({
      claims: { sub: '1', role: 'REVIEWER', channel: 'INTERNAL', exp: 9999999999 },
    });
  }

  it('GNB_로고_링크에_KRDS_focus_3px_2offset_적용', () => {
    setReviewer();
    renderWithProviders(<Gnb />);

    const links = screen.getAllByRole('link');
    expect(links.length).toBeGreaterThan(0);
    // 로고 링크에 3px ring + 2 offset 적용
    const hasKrds = links.some(
      (l) => RING_3PX.test(l.className) && OFFSET_2.test(l.className),
    );
    expect(hasKrds).toBe(true);
  });

  it('LNB_네비_링크에_KRDS_focus_3px_2offset_적용', () => {
    setReviewer();
    renderWithProviders(<Lnb />);

    const links = screen.getAllByRole('link');
    expect(links.length).toBeGreaterThan(0);
    links.forEach((l) => {
      expect(RING_3PX.test(l.className)).toBe(true);
      expect(OFFSET_2.test(l.className)).toBe(true);
    });
  });
});
