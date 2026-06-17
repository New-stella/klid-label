import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import MockAdapter from 'axios-mock-adapter';
import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { apiClient } from '@/lib/api/client';
import { AugmentRequestPage } from '@/pages/AugmentRequestPage';
import { renderWithProviders } from '@/test/renderWithProviders';
import { useAuthStore } from '@/stores/useAuthStore';

/**
 * SCR-AUG-001 통합 단일 선택 UI (Phase 1) — 선택 상태/렌더링 검증.
 *
 * - 처리 종류 카드 4개(WINTER/NIGHT/RAIN/RESOLUTION) 단일 선택(라디오)
 * - 영상 1건 단일 선택
 * - RESOLUTION 선택 시에만 타겟 해상도 UI 노출, 증강 종류 시 숨김
 * - 종류 변경 시 preset 초기화
 */
describe('AugmentRequestPage 통합 단일 선택 (Phase 1)', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
    useAuthStore.setState({
      token: 'tok',
      claims: { sub: 'u', role: 'REVIEWER', channel: 'INTERNAL', exp: 9999999999 },
    });
    mock.onGet('/augments').reply(200, {
      success: true,
      data: { content: [], totalElements: 0, totalPages: 0, number: 0, size: 6 },
      message: null,
      errorCode: null,
    });
  });

  afterEach(() => {
    mock.restore();
    useAuthStore.getState().clear();
  });

  const replyVideos = (count: number) => {
    mock.onGet('/videos').reply(200, {
      success: true,
      data: {
        content: Array.from({ length: count }, (_, i) => ({
          id: i + 1,
          cctvName: `CCTV-${i + 1}`,
          vmsClipId: `V${i + 1}`,
          eventName: '쓰러짐',
          eventTypeCd: 'FALL',
          frameCount: 100,
          status: 'COMPLETED',
          capturedAt: '2026-05-01T12:00:00Z',
        })),
        totalElements: count,
        totalPages: 1,
        number: 0,
        size: 20,
      },
      message: null,
      errorCode: null,
    });
  };

  it('처리종류_카드를_클릭하면_해당_종류만_선택되고_이전선택은_해제된다', async () => {
    replyVideos(0);
    const user = userEvent.setup();
    renderWithProviders(<AugmentRequestPage />);

    const winter = await screen.findByTestId('process-kind-WINTER');
    const night = screen.getByTestId('process-kind-NIGHT');

    await user.click(winter);
    expect(winter).toHaveAttribute('aria-checked', 'true');
    expect(night).toHaveAttribute('aria-checked', 'false');

    // 다른 카드 선택 시 이전 선택 자동 해제 (단일 선택)
    await user.click(night);
    expect(night).toHaveAttribute('aria-checked', 'true');
    expect(winter).toHaveAttribute('aria-checked', 'false');
  });

  it('영상은_한_건만_선택할_수_있다', async () => {
    replyVideos(3);
    const user = userEvent.setup();
    renderWithProviders(<AugmentRequestPage />);

    const radio1 = await screen.findByRole('radio', { name: /CCTV-1 선택/ });
    const radio2 = screen.getByRole('radio', { name: /CCTV-2 선택/ });

    await user.click(radio1);
    expect(radio1).toBeChecked();

    await user.click(radio2);
    expect(radio2).toBeChecked();
    expect(radio1).not.toBeChecked();
  });

  it('해상도변경_카드를_선택하면_타겟해상도_선택UI가_노출된다', async () => {
    replyVideos(0);
    const user = userEvent.setup();
    renderWithProviders(<AugmentRequestPage />);

    const resolution = await screen.findByTestId('process-kind-RESOLUTION');
    await user.click(resolution);

    expect(
      await screen.findByLabelText('목표 해상도 선택'),
    ).toBeInTheDocument();
  });

  it('증강_카드를_선택하면_타겟해상도_선택UI가_숨겨진다', async () => {
    replyVideos(0);
    const user = userEvent.setup();
    renderWithProviders(<AugmentRequestPage />);

    const winter = await screen.findByTestId('process-kind-WINTER');
    await user.click(winter);

    expect(screen.queryByLabelText('목표 해상도 선택')).not.toBeInTheDocument();
  });

  it('해상도카드_선택후_증강카드로_바꾸면_선택했던_타겟해상도가_초기화된다', async () => {
    replyVideos(0);
    const user = userEvent.setup();
    renderWithProviders(<AugmentRequestPage />);

    const resolution = await screen.findByTestId('process-kind-RESOLUTION');
    await user.click(resolution);

    const presetSelect = (await screen.findByLabelText(
      '목표 해상도 선택',
    )) as HTMLSelectElement;
    await user.selectOptions(presetSelect, 'RES_480P');
    expect(presetSelect.value).toBe('RES_480P');

    // 증강 카드로 전환 → 해상도 UI 숨김 + preset 초기화
    const winter = screen.getByTestId('process-kind-WINTER');
    await user.click(winter);
    expect(screen.queryByLabelText('목표 해상도 선택')).not.toBeInTheDocument();

    // 다시 해상도 카드로 돌아오면 기본값(초기화됨)으로 복귀 — RES_480P 가 아님
    await user.click(resolution);
    const presetAgain = (await screen.findByLabelText(
      '목표 해상도 선택',
    )) as HTMLSelectElement;
    expect(presetAgain.value).not.toBe('RES_480P');
  });

  it('처리종류_카드_4개가_radiogroup으로_렌더된다', async () => {
    replyVideos(0);
    renderWithProviders(<AugmentRequestPage />);

    const group = await screen.findByRole('radiogroup', {
      name: /처리 종류/,
    });
    const radios = within(group).getAllByRole('radio');
    expect(radios).toHaveLength(4);
  });

  it('선택된_카드만_탭포커스를_받는다(tabindex)', async () => {
    replyVideos(0);
    const user = userEvent.setup();
    renderWithProviders(<AugmentRequestPage />);

    const winter = await screen.findByTestId('process-kind-WINTER');
    const night = screen.getByTestId('process-kind-NIGHT');
    const rain = screen.getByTestId('process-kind-RAIN');
    const resolution = screen.getByTestId('process-kind-RESOLUTION');

    // 미선택 상태: 첫 카드만 tab 진입(0), 나머지는 -1 (로빙 tabindex 진입점)
    expect(winter).toHaveAttribute('tabindex', '0');
    expect(night).toHaveAttribute('tabindex', '-1');
    expect(rain).toHaveAttribute('tabindex', '-1');
    expect(resolution).toHaveAttribute('tabindex', '-1');

    // 선택 후: 선택된 카드만 0, 나머지는 -1
    await user.click(night);
    expect(night).toHaveAttribute('tabindex', '0');
    expect(winter).toHaveAttribute('tabindex', '-1');
    expect(rain).toHaveAttribute('tabindex', '-1');
    expect(resolution).toHaveAttribute('tabindex', '-1');
  });

  it('종류_카드는_화살표키로_이동_선택된다', async () => {
    replyVideos(0);
    const user = userEvent.setup();
    renderWithProviders(<AugmentRequestPage />);

    const winter = await screen.findByTestId('process-kind-WINTER');
    const night = screen.getByTestId('process-kind-NIGHT');
    const rain = screen.getByTestId('process-kind-RAIN');

    // 첫 카드 포커스 → ArrowRight → 다음(NIGHT) 선택 + 포커스 이동
    winter.focus();
    await user.keyboard('{ArrowRight}');
    expect(night).toHaveAttribute('aria-checked', 'true');
    expect(night).toHaveFocus();

    // ArrowDown 도 다음으로 이동(RAIN)
    await user.keyboard('{ArrowDown}');
    expect(rain).toHaveAttribute('aria-checked', 'true');
    expect(rain).toHaveFocus();

    // ArrowLeft 는 이전(NIGHT)으로 이동
    await user.keyboard('{ArrowLeft}');
    expect(night).toHaveAttribute('aria-checked', 'true');
    expect(night).toHaveFocus();

    // ArrowUp 은 이전(WINTER)으로 이동
    await user.keyboard('{ArrowUp}');
    expect(winter).toHaveAttribute('aria-checked', 'true');
    expect(winter).toHaveFocus();
  });
});
