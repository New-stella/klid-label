import { fireEvent, screen, waitFor } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';

import { PresetEditModal } from '@/features/preset/components/PresetEditModal';
import type { Preset } from '@/features/preset/types';
import { renderWithProviders } from '@/test/renderWithProviders';

describe('PresetEditModal', () => {
  it('초기값_라벨_코드_렌더링', () => {
    const initial: Preset = {
      id: 1,
      name: '교통사고 표준',
      description: '교통사고용',
      labelCodes: ['PERSON', 'VEHICLE'],
      labelCodeOptions: [
        { code: 'PERSON', bboxEnabled: true, polygonEnabled: true },
        { code: 'VEHICLE', bboxEnabled: true, polygonEnabled: true },
      ],
      eventTypeCd: null,
      createdAt: '2026-05-01T00:00:00Z',
      updatedAt: '2026-05-10T00:00:00Z',
    };
    renderWithProviders(
      <PresetEditModal open onClose={() => undefined} onSubmit={vi.fn()} initial={initial} />,
    );

    expect(screen.getByTestId('preset-labels-count').textContent).toContain('2');
    expect(screen.getByText('PERSON')).toBeInTheDocument();
    expect(screen.getByText('VEHICLE')).toBeInTheDocument();
  });

  it('라벨_코드_미입력_시_zod_에러_노출', async () => {
    const onSubmit = vi.fn();
    renderWithProviders(
      <PresetEditModal open onClose={() => undefined} onSubmit={onSubmit} />,
    );

    fireEvent.change(screen.getByLabelText(/프리셋 이름/), {
      target: { value: '새 프리셋' },
    });
    fireEvent.click(screen.getByText('만들기'));

    await waitFor(() => {
      expect(screen.getByText(/1개 이상/)).toBeInTheDocument();
    });
    expect(onSubmit).not.toHaveBeenCalled();
  });

  it('이벤트_타입_select_옵션_표시', () => {
    renderWithProviders(
      <PresetEditModal open onClose={() => undefined} onSubmit={vi.fn()} />,
    );

    const select = screen.getByLabelText(/매핑 이벤트 타입/);
    expect(select).toBeInTheDocument();
    // 미선택 옵션 + SoT 6 종 (EVT_FALL/VIOLENCE/ACCIDENT/ABNORMAL/FLOOD/FIRE)
    expect(screen.getByRole('option', { name: /선택 안 함/ })).toBeInTheDocument();
    expect(screen.getByRole('option', { name: /EVT_FALL/ })).toBeInTheDocument();
    expect(screen.getByRole('option', { name: /EVT_VIOLENCE/ })).toBeInTheDocument();
    expect(screen.getByRole('option', { name: /EVT_ACCIDENT/ })).toBeInTheDocument();
    expect(screen.getByRole('option', { name: /EVT_ABNORMAL/ })).toBeInTheDocument();
    expect(screen.getByRole('option', { name: /EVT_FLOOD/ })).toBeInTheDocument();
    expect(screen.getByRole('option', { name: /EVT_FIRE/ })).toBeInTheDocument();
    // EVT_TRASH 는 SoT 에서 제거됨 (V1.8 — 6 종 운영)
    expect(screen.queryByRole('option', { name: /EVT_TRASH/ })).toBeNull();
  });

  it('초기값_eventTypeCd_select_반영', () => {
    const initial: Preset = {
      id: 2,
      name: '낙상',
      description: null,
      labelCodes: ['PERSON'],
      labelCodeOptions: [
        { code: 'PERSON', bboxEnabled: true, polygonEnabled: true },
      ],
      eventTypeCd: 'EVT_FALL',
      createdAt: '2026-05-01T00:00:00Z',
      updatedAt: '2026-05-10T00:00:00Z',
    };
    renderWithProviders(
      <PresetEditModal open onClose={() => undefined} onSubmit={vi.fn()} initial={initial} />,
    );

    const select = screen.getByLabelText(/매핑 이벤트 타입/) as HTMLSelectElement;
    expect(select.value).toBe('EVT_FALL');
  });

  it('eventTypeCd_선택값이_onSubmit_payload에_포함', async () => {
    const onSubmit = vi.fn();
    renderWithProviders(
      <PresetEditModal open onClose={() => undefined} onSubmit={onSubmit} />,
    );

    fireEvent.change(screen.getByLabelText(/프리셋 이름/), {
      target: { value: '낙상 표준' },
    });
    // 빠른 추가에서 PERSON 클릭
    fireEvent.click(screen.getByRole('button', { name: /\+ PERSON/ }));
    fireEvent.change(screen.getByLabelText(/매핑 이벤트 타입/), {
      target: { value: 'EVT_FALL' },
    });
    fireEvent.click(screen.getByText('만들기'));

    await waitFor(() => {
      expect(onSubmit).toHaveBeenCalledTimes(1);
    });
    expect(onSubmit.mock.calls[0]![0]).toMatchObject({
      name: '낙상 표준',
      labelCodes: ['PERSON'],
      eventTypeCd: 'EVT_FALL',
    });
  });

  it('eventTypeCd_미선택시_빈_문자열_제출', async () => {
    const onSubmit = vi.fn();
    renderWithProviders(
      <PresetEditModal open onClose={() => undefined} onSubmit={onSubmit} />,
    );

    fireEvent.change(screen.getByLabelText(/프리셋 이름/), {
      target: { value: '미매핑' },
    });
    fireEvent.click(screen.getByRole('button', { name: /\+ PERSON/ }));
    fireEvent.click(screen.getByText('만들기'));

    await waitFor(() => {
      expect(onSubmit).toHaveBeenCalledTimes(1);
    });
    expect(onSubmit.mock.calls[0]![0]).toMatchObject({
      eventTypeCd: '',
    });
  });

  // ── Phase 3: BBOX/POLYGON 옵션 ──
  describe('Phase 3 — BBOX/POLYGON 옵션', () => {
    it('PresetEditModal_빠른_추가_chip_은_기본값_BBOX_POLYGON_모두_활성', () => {
      renderWithProviders(
        <PresetEditModal open onClose={() => undefined} onSubmit={vi.fn()} />,
      );
      fireEvent.click(screen.getByRole('button', { name: /\+ PERSON/ }));

      const bbox = screen.getByRole('checkbox', {
        name: /PERSON.*BBOX|BBOX.*PERSON/,
      }) as HTMLInputElement;
      const poly = screen.getByRole('checkbox', {
        name: /PERSON.*POLYGON|POLYGON.*PERSON/,
      }) as HTMLInputElement;
      expect(bbox.checked).toBe(true);
      expect(poly.checked).toBe(true);
    });

    it('PresetEditModal_BBOX_체크박스_해제_시_옵션_갱신', () => {
      renderWithProviders(
        <PresetEditModal open onClose={() => undefined} onSubmit={vi.fn()} />,
      );
      fireEvent.click(screen.getByRole('button', { name: /\+ PERSON/ }));

      const bbox = screen.getByRole('checkbox', {
        name: /PERSON.*BBOX|BBOX.*PERSON/,
      }) as HTMLInputElement;
      fireEvent.click(bbox);
      expect(bbox.checked).toBe(false);
      // POLYGON 은 여전히 true
      const poly = screen.getByRole('checkbox', {
        name: /PERSON.*POLYGON|POLYGON.*PERSON/,
      }) as HTMLInputElement;
      expect(poly.checked).toBe(true);
    });

    it('PresetEditModal_BBOX_POLYGON_모두_해제_시_chip_빨간_테두리_표시', () => {
      renderWithProviders(
        <PresetEditModal open onClose={() => undefined} onSubmit={vi.fn()} />,
      );
      fireEvent.click(screen.getByRole('button', { name: /\+ PERSON/ }));

      const bbox = screen.getByRole('checkbox', {
        name: /PERSON.*BBOX|BBOX.*PERSON/,
      }) as HTMLInputElement;
      const poly = screen.getByRole('checkbox', {
        name: /PERSON.*POLYGON|POLYGON.*PERSON/,
      }) as HTMLInputElement;
      fireEvent.click(bbox);
      fireEvent.click(poly);

      const chip = screen.getByTestId('preset-chip-PERSON');
      expect(chip.className).toMatch(/border-red-500|border-red-400/);
    });

    it('PresetEditModal_모든_chip_정상일_때_저장_버튼_활성화', () => {
      renderWithProviders(
        <PresetEditModal open onClose={() => undefined} onSubmit={vi.fn()} />,
      );
      fireEvent.change(screen.getByLabelText(/프리셋 이름/), {
        target: { value: '정상' },
      });
      fireEvent.click(screen.getByRole('button', { name: /\+ PERSON/ }));

      const saveBtn = screen.getByText('만들기') as HTMLButtonElement;
      expect(saveBtn.disabled).toBe(false);
    });

    it('PresetEditModal_chip_하나라도_둘_다_off_면_저장_버튼_disabled', () => {
      renderWithProviders(
        <PresetEditModal open onClose={() => undefined} onSubmit={vi.fn()} />,
      );
      fireEvent.change(screen.getByLabelText(/프리셋 이름/), {
        target: { value: '잘못된 프리셋' },
      });
      fireEvent.click(screen.getByRole('button', { name: /\+ PERSON/ }));

      const bbox = screen.getByRole('checkbox', {
        name: /PERSON.*BBOX|BBOX.*PERSON/,
      }) as HTMLInputElement;
      const poly = screen.getByRole('checkbox', {
        name: /PERSON.*POLYGON|POLYGON.*PERSON/,
      }) as HTMLInputElement;
      fireEvent.click(bbox);
      fireEvent.click(poly);

      const saveBtn = screen.getByText('만들기') as HTMLButtonElement;
      expect(saveBtn.disabled).toBe(true);
    });

    it('PresetEditModal_저장_payload_에_labelCodeOptions_가_정확히_포함', async () => {
      const onSubmit = vi.fn();
      renderWithProviders(
        <PresetEditModal open onClose={() => undefined} onSubmit={onSubmit} />,
      );

      fireEvent.change(screen.getByLabelText(/프리셋 이름/), {
        target: { value: 'PERSON BBOX only' },
      });
      fireEvent.click(screen.getByRole('button', { name: /\+ PERSON/ }));
      // POLYGON 해제 → bboxEnabled=true, polygonEnabled=false
      const poly = screen.getByRole('checkbox', {
        name: /PERSON.*POLYGON|POLYGON.*PERSON/,
      }) as HTMLInputElement;
      fireEvent.click(poly);
      fireEvent.click(screen.getByText('만들기'));

      await waitFor(() => {
        expect(onSubmit).toHaveBeenCalledTimes(1);
      });
      const payload = onSubmit.mock.calls[0]![0];
      expect(payload.labelCodeOptions).toEqual([
        { code: 'PERSON', bboxEnabled: true, polygonEnabled: false },
      ]);
    });

    it('PresetEditModal_initial_에_labelCodeOptions_가_있으면_체크박스_반영', () => {
      const initial = {
        id: 3,
        name: 'PERSON BBOX',
        description: null,
        labelCodes: ['PERSON'],
        labelCodeOptions: [
          { code: 'PERSON', bboxEnabled: true, polygonEnabled: false },
        ],
        eventTypeCd: null,
        createdAt: '2026-05-01T00:00:00Z',
        updatedAt: '2026-05-10T00:00:00Z',
      };
      renderWithProviders(
        <PresetEditModal
          open
          onClose={() => undefined}
          onSubmit={vi.fn()}
          initial={initial}
        />,
      );
      const bbox = screen.getByRole('checkbox', {
        name: /PERSON.*BBOX|BBOX.*PERSON/,
      }) as HTMLInputElement;
      const poly = screen.getByRole('checkbox', {
        name: /PERSON.*POLYGON|POLYGON.*PERSON/,
      }) as HTMLInputElement;
      expect(bbox.checked).toBe(true);
      expect(poly.checked).toBe(false);
    });
  });
});
