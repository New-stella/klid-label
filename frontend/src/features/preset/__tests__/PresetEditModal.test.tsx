import { fireEvent, screen, waitFor } from '@testing-library/react';
import MockAdapter from 'axios-mock-adapter';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { PresetEditModal } from '@/features/preset/components/PresetEditModal';
import type { Preset } from '@/features/preset/types';
import { apiClient } from '@/lib/api/client';
import { renderWithProviders } from '@/test/renderWithProviders';

const ok = (data: unknown) => ({ success: true, data, message: null, errorCode: null });

// 관제 마스터 기반 카테고리 옵션 (value=categoryKey, 표시=label).
const CATEGORIES = [
  { categoryKey: '010001', label: '침수(범람)', memberCodes: ['EV01000101'] },
  { categoryKey: '020002', label: '쓰러짐', memberCodes: ['EV02000201'] },
  { categoryKey: '040001', label: '교통사고', memberCodes: ['EV04000101'] },
];

// 라벨 마스터 — 프리셋 라벨의 단일 진실원.
const MASTERS = [
  { labelId: 10, name: '사람', color: '#EF4444', type: 'BBOX', sortNo: 1, useYn: 'Y' },
  { labelId: 20, name: '차량', color: '#3B82F6', type: 'POLYGON', sortNo: 2, useYn: 'Y' },
  { labelId: 30, name: '비활성', color: '#999999', type: 'BBOX', sortNo: 3, useYn: 'N' },
];

describe('PresetEditModal (마스터 연동)', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
    mock.onGet('/event-types').reply(200, ok(CATEGORIES));
    mock.onGet('/manage/labels').reply(200, ok(MASTERS));
  });

  afterEach(() => mock.restore());

  it('활성_마스터만_형태와_함께_렌더링', async () => {
    renderWithProviders(
      <PresetEditModal open onClose={() => undefined} onSubmit={vi.fn()} />,
    );

    // 활성 마스터(사람/차량)는 체크박스로 노출, 형태 배지 함께 표시
    expect(await screen.findByRole('checkbox', { name: /사람/ })).toBeInTheDocument();
    expect(screen.getByRole('checkbox', { name: /차량/ })).toBeInTheDocument();
    expect(screen.getByText('바운딩박스')).toBeInTheDocument();
    expect(screen.getByText('폴리곤')).toBeInTheDocument();
    // 비활성(useYn='N') 마스터는 노출되지 않는다.
    expect(screen.queryByRole('checkbox', { name: /비활성/ })).toBeNull();
  });

  it('라벨_미선택시_저장_disabled', async () => {
    renderWithProviders(
      <PresetEditModal open onClose={() => undefined} onSubmit={vi.fn()} />,
    );
    await screen.findByRole('checkbox', { name: /사람/ });

    fireEvent.change(screen.getByLabelText(/프리셋 이름/), {
      target: { value: '새 프리셋' },
    });
    const saveBtn = screen.getByText('만들기') as HTMLButtonElement;
    expect(saveBtn.disabled).toBe(true);
  });

  it('마스터_선택시_labelIds로_제출', async () => {
    const onSubmit = vi.fn();
    renderWithProviders(
      <PresetEditModal open onClose={() => undefined} onSubmit={onSubmit} />,
    );

    fireEvent.change(screen.getByLabelText(/프리셋 이름/), {
      target: { value: '보행자 프리셋' },
    });
    fireEvent.click(await screen.findByRole('checkbox', { name: /사람/ }));
    fireEvent.click(screen.getByRole('checkbox', { name: /차량/ }));
    // 이벤트 옵션 로드 후 매핑
    await screen.findByRole('option', { name: '쓰러짐' });
    fireEvent.change(screen.getByLabelText(/매핑 이벤트 타입/), {
      target: { value: '020002' },
    });
    fireEvent.click(screen.getByText('만들기'));

    await waitFor(() => expect(onSubmit).toHaveBeenCalledTimes(1));
    expect(onSubmit.mock.calls[0]![0]).toMatchObject({
      name: '보행자 프리셋',
      labelIds: [10, 20],
      eventTypeCd: '020002',
    });
  });

  it('선택_토글시_labelIds_에서_제거', async () => {
    const onSubmit = vi.fn();
    renderWithProviders(
      <PresetEditModal open onClose={() => undefined} onSubmit={onSubmit} />,
    );
    fireEvent.change(screen.getByLabelText(/프리셋 이름/), {
      target: { value: '토글' },
    });
    const person = await screen.findByRole('checkbox', { name: /사람/ });
    fireEvent.click(person); // 선택
    fireEvent.click(person); // 해제
    fireEvent.click(screen.getByRole('checkbox', { name: /차량/ }));
    fireEvent.click(screen.getByText('만들기'));

    await waitFor(() => expect(onSubmit).toHaveBeenCalledTimes(1));
    expect(onSubmit.mock.calls[0]![0]).toMatchObject({ labelIds: [20] });
  });

  it('초기값_연결_코드가_체크박스에_반영', async () => {
    const initial: Preset = {
      id: 1,
      name: '교통사고 표준',
      description: '교통사고용',
      codes: [
        {
          labelId: 10,
          code: null,
          labelName: '사람',
          labelType: 'BBOX',
          linked: true,
          bboxEnabled: true,
          polygonEnabled: false,
        },
      ],
      eventTypeCd: null,
      createdAt: '2026-05-01T00:00:00Z',
      updatedAt: '2026-05-10T00:00:00Z',
    };
    renderWithProviders(
      <PresetEditModal open onClose={() => undefined} onSubmit={vi.fn()} initial={initial} />,
    );

    const person = (await screen.findByRole('checkbox', {
      name: /사람/,
    })) as HTMLInputElement;
    await waitFor(() => expect(person.checked).toBe(true));
    const car = screen.getByRole('checkbox', { name: /차량/ }) as HTMLInputElement;
    expect(car.checked).toBe(false);
  });

  it('미연결_코드_있으면_경고_배너_표시', async () => {
    const initial: Preset = {
      id: 2,
      name: '레거시',
      description: null,
      codes: [
        {
          labelId: null,
          code: 'OLD_CODE',
          labelName: 'OLD_CODE',
          labelType: null,
          linked: false,
          bboxEnabled: false,
          polygonEnabled: false,
        },
      ],
      eventTypeCd: null,
      createdAt: '2026-05-01T00:00:00Z',
      updatedAt: '2026-05-10T00:00:00Z',
    };
    renderWithProviders(
      <PresetEditModal open onClose={() => undefined} onSubmit={vi.fn()} initial={initial} />,
    );

    const warning = await screen.findByTestId('preset-unlinked-warning');
    expect(warning).toHaveTextContent('OLD_CODE');
  });

  it('이벤트_타입_select_옵션_카테고리_label로_표시', async () => {
    renderWithProviders(
      <PresetEditModal open onClose={() => undefined} onSubmit={vi.fn()} />,
    );

    expect(screen.getByLabelText(/매핑 이벤트 타입/)).toBeInTheDocument();
    expect(screen.getByRole('option', { name: /선택 안 함/ })).toBeInTheDocument();
    expect(await screen.findByRole('option', { name: '침수(범람)' })).toBeInTheDocument();
    expect(screen.getByRole('option', { name: '교통사고' })).toBeInTheDocument();
    // 하드코딩 EVT_ 코드는 옵션에 노출되지 않는다.
    expect(screen.queryByRole('option', { name: /EVT_/ })).toBeNull();
  });
});
