// 회귀 가드 — 캔버스 옵션바의 포털 꼴. [@design SCREEN-029]
//
// 줄의 «구성»은 두 채널이 원래 같았다(되돌리기 · 프레임 이동 · 확대 · 표시숨김 · 저장).
// 이번에 갈린 것은 **부품**뿐이다 — 포털은 시안대로 킷 아이콘 버튼을 쓴다.
//
// ★그래서 이 가드가 보는 것은 «모양이 갈린다»와 «배선은 같다» 둘이다.
//   배선까지 갈리면 두 벌을 유지하는 것과 같아진다.

import { describe, expect, it, vi } from 'vitest';
import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { renderWithProviders } from '@/test/renderWithProviders';
import { useLabelStore } from '@/stores/useLabelStore';

import { CanvasOptionBar } from '../CanvasOptionBar';

function renderBar(portalMode: boolean, extra: Record<string, unknown> = {}) {
  useLabelStore.getState().reset();
  return renderWithProviders(
    <CanvasOptionBar
      frameIndex={0}
      frameCount={23}
      portalMode={portalMode}
      onRequestGoTo={vi.fn()}
      srcSn={555}
      labels={[]}
      onRequestSave={vi.fn()}
      {...extra}
    />,
  );
}

/** 킷 아이콘 버튼인가 — 킷이 제 클래스를 단다. */
const isKit = (el: HTMLElement) => el.classList.contains('klid-icon-btn');

describe('캔버스 옵션바 — 포털 꼴', () => {
  describe('① 부품이 갈린다', () => {
    it.each(['label-option-zoom-out', 'label-option-zoom-in', 'label-option-visibility'])(
      '★포털은 킷 아이콘 버튼이다 — %s',
      (testId) => {
        renderBar(true);
        expect(isKit(screen.getByTestId(testId))).toBe(true);
      },
    );

    it.each(['label-option-zoom-out', 'label-option-zoom-in', 'label-option-visibility'])(
      '★관제는 종전 버튼 그대로다 — %s',
      (testId) => {
        renderBar(false);
        expect(isKit(screen.getByTestId(testId))).toBe(false);
      },
    );
  });

  describe('② 배선은 두 채널이 같다', () => {
    it.each([true, false])('★확대가 같은 스토어를 부른다 (portalMode=%s)', async (portalMode) => {
      const user = userEvent.setup();
      renderBar(portalMode);
      const before = useLabelStore.getState().zoom;
      await user.click(screen.getByTestId('label-option-zoom-in'));
      expect(useLabelStore.getState().zoom).toBeGreaterThan(before);
    });

    it.each([true, false])(
      '★고른 객체가 없으면 표시숨김이 잠기고 까닭을 말한다 (portalMode=%s)',
      (portalMode) => {
        renderBar(portalMode);
        const btn = screen.getByTestId('label-option-visibility');
        expect(btn).toBeDisabled();
        // 눌러 봐야 아는 제약이 아니다 — 말풍선이 미리 말한다.
        expect(btn.getAttribute('title') ?? '').not.toBe('');
      },
    );
  });

  describe('③ 채널마다 서는 걸음이 다르다 — 종전 계약 그대로', () => {
    it('포털에는 옵션바 삭제를 두지 않는다 — 객체 패널의 행별 삭제가 그 자리를 갖는다', () => {
      renderBar(true);
      expect(screen.queryByTestId('label-option-delete')).toBeNull();
    });

    it('양성 대조 — 관제에서는 그 걸음이 실제로 선다', () => {
      renderBar(false);
      expect(screen.getByTestId('label-option-delete')).toBeInTheDocument();
    });

    it('포털에는 프레임 폐기·버전 걸음이 오지 않는다 — 호출부가 주지 않는다', () => {
      renderBar(true);
      expect(screen.queryByTestId('frame-discard-toggle')).toBeNull();
      expect(screen.queryByTestId('start-version-open')).toBeNull();
    });

    it('양성 대조 — 핸들러를 주면 그 걸음이 실제로 선다(관제)', () => {
      renderBar(false, { onToggleDiscard: vi.fn(), onOpenStartVersion: vi.fn() });
      expect(screen.getByTestId('frame-discard-toggle')).toBeInTheDocument();
      expect(screen.getByTestId('start-version-open')).toBeInTheDocument();
    });
  });
});
