// 2026-08-03 회귀 가드 — 라벨명 한글 표시는 **렌더 단계에만** 적용한다.
//
// 저장/전송되는 라벨 식별자·이름 값(LS_DATA_LBL 로 가는 className)에 한글 치환이 섞이면 회귀다.
// (BE 는 라벨 마스터 원문 이름을 기대한다 — 표시명은 화면에서만 바뀐다.)

import { fireEvent, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import MockAdapter from 'axios-mock-adapter';

import { apiClient } from '@/lib/api/client';
import { useLabelStore } from '@/stores/useLabelStore';
import { renderWithProviders } from '@/test/renderWithProviders';

import { ObjectAttributePanel } from '../components/ObjectAttributePanel';
import { resolveLabelDisplayName } from '../utils/labelDisplayName';
import type { Label } from '../types';

// 'bus' 는 COCO 한글 사전에 있어 화면에는 '버스'로 보이지만 저장 값은 'bus' 여야 한다.
const mastersPayload = {
  success: true,
  data: [
    { labelId: 11, name: '사람', color: '#EF4444', type: 'BBOX', sortNo: 1, useYn: 'Y', dtctTypeCd: 'person' },
    { labelId: 22, name: 'bus', color: '#3B82F6', type: 'BBOX', sortNo: 2, useYn: 'Y', dtctTypeCd: 'bus' },
  ],
  message: null,
  errorCode: null,
};

const sample: Label = {
  id: 'p1',
  frameNo: 1,
  classId: 11,
  className: '사람',
  source: 'MANUAL',
  shape: { type: 'BBOX', left: 0, top: 0, right: 100, bottom: 80 },
};

describe('라벨명 한글 표시 — 저장 payload 미혼입 가드', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
    useLabelStore.getState().reset();
  });

  afterEach(() => {
    mock.restore();
  });

  it('전제_bus는_화면에서_버스로_표시된다', () => {
    expect(resolveLabelDisplayName('bus', 'bus')).toBe('버스');
  });

  it('라벨_드롭다운은_한글로_보이지만_저장값은_마스터_원문_이름이다', async () => {
    mock.onGet('/manage/labels').reply(200, mastersPayload);
    useLabelStore.getState().setLabels([sample]);
    useLabelStore.getState().selectLabel('p1');
    renderWithProviders(<ObjectAttributePanel labels={[sample]} />);

    const select = (await waitFor(() =>
      screen.getByLabelText('라벨 선택'),
    )) as HTMLSelectElement;

    // 표시는 한글
    expect(select.textContent).toContain('버스');

    // 선택 → store 에 들어가는 className 은 원문 'bus'
    fireEvent.change(select, { target: { value: '22' } });

    const updated = useLabelStore.getState().labels.find((l) => l.id === 'p1');
    expect(updated?.className).toBe('bus');
    expect(updated?.classId).toBe(22);
  });
});
