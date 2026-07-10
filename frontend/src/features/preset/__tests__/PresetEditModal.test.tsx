import { fireEvent, screen, waitFor } from '@testing-library/react';
import MockAdapter from 'axios-mock-adapter';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { PresetEditModal } from '@/features/preset/components/PresetEditModal';
import type { Preset } from '@/features/preset/types';
import { apiClient } from '@/lib/api/client';
import { renderWithProviders } from '@/test/renderWithProviders';

// 관제 마스터 기반 카테고리 옵션 (value=categoryKey, 표시=label).
const CATEGORIES = [
  { categoryKey: '010001', label: '침수(범람)', memberCodes: ['EV01000101'] },
  { categoryKey: '020002', label: '쓰러짐', memberCodes: ['EV02000201'] },
  { categoryKey: '040001', label: '교통사고', memberCodes: ['EV04000101'] },
];

describe('PresetEditModal', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
    mock.onGet('/event-types').reply(200, {
      success: true,
      data: CATEGORIES,
      message: null,
      errorCode: null,
    });
  });

  afterEach(() => mock.restore());

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

  it('이벤트_타입_select_옵션_9카테고리_label로_표시', async () => {
    renderWithProviders(
      <PresetEditModal open onClose={() => undefined} onSubmit={vi.fn()} />,
    );

    const select = screen.getByLabelText(/매핑 이벤트 타입/);
    expect(select).toBeInTheDocument();
    // 미선택 옵션 + 관제 카테고리(label 표시) — API 로드 후 노출
    expect(screen.getByRole('option', { name: /선택 안 함/ })).toBeInTheDocument();
    expect(
      await screen.findByRole('option', { name: '침수(범람)' }),
    ).toBeInTheDocument();
    expect(screen.getByRole('option', { name: '쓰러짐' })).toBeInTheDocument();
    expect(screen.getByRole('option', { name: '교통사고' })).toBeInTheDocument();
    // 하드코딩 EVT_ 코드는 옵션에 노출되지 않는다.
    expect(screen.queryByRole('option', { name: /EVT_/ })).toBeNull();
  });

  it('초기값_eventTypeCd_categoryKey_select_반영', async () => {
    const initial: Preset = {
      id: 2,
      name: '낙상',
      description: null,
      labelCodes: ['PERSON'],
      labelCodeOptions: [
        { code: 'PERSON', bboxEnabled: true, polygonEnabled: true },
      ],
      eventTypeCd: '020002',
      createdAt: '2026-05-01T00:00:00Z',
      updatedAt: '2026-05-10T00:00:00Z',
    };
    renderWithProviders(
      <PresetEditModal open onClose={() => undefined} onSubmit={vi.fn()} initial={initial} />,
    );

    // 옵션 로드 후 select value 가 categoryKey 로 반영
    await screen.findByRole('option', { name: '쓰러짐' });
    const select = screen.getByLabelText(/매핑 이벤트 타입/) as HTMLSelectElement;
    await waitFor(() => expect(select.value).toBe('020002'));
  });

  it('프리셋_저장시_categoryKey가_payload에_담긴다', async () => {
    const onSubmit = vi.fn();
    renderWithProviders(
      <PresetEditModal open onClose={() => undefined} onSubmit={onSubmit} />,
    );

    fireEvent.change(screen.getByLabelText(/프리셋 이름/), {
      target: { value: '낙상 표준' },
    });
    // 빠른 추가에서 PERSON 클릭
    fireEvent.click(screen.getByRole('button', { name: /\+ PERSON/ }));
    // 옵션 로드 대기 후 categoryKey 선택
    await screen.findByRole('option', { name: '쓰러짐' });
    fireEvent.change(screen.getByLabelText(/매핑 이벤트 타입/), {
      target: { value: '020002' },
    });
    fireEvent.click(screen.getByText('만들기'));

    await waitFor(() => {
      expect(onSubmit).toHaveBeenCalledTimes(1);
    });
    expect(onSubmit.mock.calls[0]![0]).toMatchObject({
      name: '낙상 표준',
      labelCodes: ['PERSON'],
      eventTypeCd: '020002',
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
      expect(chip.className).toMatch(/border-danger/);
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
