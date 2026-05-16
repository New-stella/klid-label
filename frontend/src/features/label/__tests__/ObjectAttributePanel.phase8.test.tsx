// Phase 8 — ObjectAttributePanel 라벨 마스터 통합 테스트.
//
// 1. useLabelMasters 가 채워졌을 때 드롭다운 자동 표시
// 2. 라벨 변경 시 store update + dirty 마킹
// 3. classId/className null 인 객체도 깨지지 않음

import { fireEvent, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import MockAdapter from 'axios-mock-adapter';

import { apiClient } from '@/lib/api/client';
import { useLabelStore } from '@/stores/useLabelStore';
import { renderWithProviders } from '@/test/renderWithProviders';

import { ObjectAttributePanel } from '../components/ObjectAttributePanel';
import type { Label } from '../types';

const samplePayload = {
  success: true,
  data: [
    { labelId: 1, name: '사람', color: '#EF4444', type: 'BBOX', sortNo: 1, useYn: 'Y' },
    { labelId: 2, name: '차량', color: '#3B82F6', type: 'BBOX', sortNo: 2, useYn: 'Y' },
    { labelId: 5, name: '자전거', color: '#10B981', type: 'BBOX', sortNo: 3, useYn: 'Y' },
  ],
  message: null,
  errorCode: null,
};

const sample: Label = {
  id: 'p1',
  frameNo: 1,
  classId: 1,
  className: '사람',
  source: 'MANUAL',
  shape: { type: 'BBOX', left: 0, top: 0, right: 100, bottom: 80 },
};

describe('ObjectAttributePanel — Phase 8 라벨 마스터 통합', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
    useLabelStore.getState().reset();
  });

  afterEach(() => {
    mock.restore();
  });

  it('useLabelMasters_가_채워지면_드롭다운_자동_표시', async () => {
    mock.onGet('/manage/labels').reply(200, samplePayload);

    useLabelStore.getState().setLabels([sample]);
    useLabelStore.getState().selectLabel('p1');
    renderWithProviders(<ObjectAttributePanel labels={[sample]} />);

    await waitFor(() => {
      const select = screen.queryByLabelText(/라벨 선택|라벨$/) as HTMLSelectElement | null;
      expect(select).not.toBeNull();
    });

    const select = screen.getByLabelText(/라벨 선택|라벨$/) as HTMLSelectElement;
    // 옵션 3개: 사람/차량/자전거
    expect(select.options.length).toBe(3);
  });

  it('드롭다운_변경시_store_업데이트_+_dirty_마킹', async () => {
    mock.onGet('/manage/labels').reply(200, samplePayload);

    useLabelStore.getState().setLabels([sample]);
    useLabelStore.getState().selectLabel('p1');
    renderWithProviders(<ObjectAttributePanel labels={[sample]} />);

    await waitFor(() => {
      expect(screen.queryByLabelText(/라벨 선택|라벨$/)).not.toBeNull();
    });
    const select = screen.getByLabelText(/라벨 선택|라벨$/) as HTMLSelectElement;
    fireEvent.change(select, { target: { value: '5' } });

    const updated = useLabelStore.getState().labels.find((l) => l.id === 'p1');
    expect(updated?.classId).toBe(5);
    expect(updated?.className).toBe('자전거');
    expect(useLabelStore.getState().dirtyLabels.has('p1')).toBe(true);
  });
});
