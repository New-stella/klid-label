// Phase 8 — ObjectAttributePanel 라벨 마스터 통합 테스트.
//
// 1. useLabelMasters 가 채워졌을 때 드롭다운 자동 표시
// 2. 라벨 변경 시 store update + dirty 마킹
// 3. classId/className null 인 객체도 깨지지 않음

import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import MockAdapter from 'axios-mock-adapter';

import { apiClient } from '@/lib/api/client';
import { useLabelStore } from '@/stores/useLabelStore';
import { renderWithProviders } from '@/test/renderWithProviders';
import { selectRadixOption } from '@/test/selectTestUtils';

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
    const user = userEvent.setup();
    renderWithProviders(<ObjectAttributePanel labels={[sample]} />);

    await waitFor(() => {
      expect(screen.queryByLabelText(/라벨 선택|라벨$/)).not.toBeNull();
    });

    const select = screen.getByLabelText(/라벨 선택|라벨$/);
    await user.click(select);
    // 옵션 3개: 사람/차량/자전거
    const options = await screen.findAllByRole('option');
    expect(options.length).toBe(3);
  });

  it('드롭다운_변경시_store_업데이트_+_dirty_마킹', async () => {
    mock.onGet('/manage/labels').reply(200, samplePayload);

    useLabelStore.getState().setLabels([sample]);
    useLabelStore.getState().selectLabel('p1');
    const user = userEvent.setup();
    renderWithProviders(<ObjectAttributePanel labels={[sample]} />);

    await waitFor(() => {
      expect(screen.queryByLabelText(/라벨 선택|라벨$/)).not.toBeNull();
    });
    const select = screen.getByLabelText(/라벨 선택|라벨$/);
    await selectRadixOption(user, select, /자전거/);

    const updated = useLabelStore.getState().labels.find((l) => l.id === 'p1');
    expect(updated?.classId).toBe(5);
    expect(updated?.className).toBe('자전거');
    expect(useLabelStore.getState().dirtyLabels.has('p1')).toBe(true);
  });

  // 회귀(2026-08-06) — 분류 변경이 classId 만 바꾸고 labelId 를 두면 저장 왕복에서
  // 마스터 조인이 끊기거나(labelId=null) 이전 분류로 저장된다. color 를 두면 캔버스가
  // 옛 분류의 색을 계속 그린다(getLabelDisplayColor 는 label.color 를 최우선 참조).
  it('드롭다운_변경시_labelId와_color도_새_마스터로_함께_갱신된다', async () => {
    mock.onGet('/manage/labels').reply(200, samplePayload);

    // given: 서버에서 로드돼 이전 분류(사람)의 labelId/color 를 달고 있는 라벨
    const loaded: Label = { ...sample, labelId: 1, color: '#EF4444' };
    useLabelStore.getState().setLabels([loaded]);
    useLabelStore.getState().selectLabel('p1');
    const user = userEvent.setup();
    renderWithProviders(<ObjectAttributePanel labels={[loaded]} />);

    await waitFor(() => {
      expect(screen.queryByLabelText(/라벨 선택|라벨$/)).not.toBeNull();
    });

    // when: '자전거'(labelId=5, #10B981)로 변경
    const select = screen.getByLabelText(/라벨 선택|라벨$/);
    await selectRadixOption(user, select, /자전거/);

    // then: 세 축이 함께 움직인다 — 저장 왕복(labelId) + 캔버스 렌더(color) 모두 정합
    const updated = useLabelStore.getState().labels.find((l) => l.id === 'p1');
    expect(updated?.labelId).toBe(5);
    expect(updated?.classId).toBe(5);
    expect(updated?.className).toBe('자전거');
    expect(updated?.color).toBe('#10B981');
    // 이전 분류(사람)의 흔적이 남지 않는다.
    expect(updated?.labelId).not.toBe(1);
    expect(updated?.color).not.toBe('#EF4444');
  });
});
