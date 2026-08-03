// 2026-08-03 회귀 가드 — 표시명 해석은 **렌더 단계에만** 있고, 저장/전송 값은 마스터 원문이다.
//
// 재확정으로 표시명 = 마스터 등록명 그대로가 되어 지금은 표시값과 저장값이 같지만, 계약("표시
// 경로가 저장/전송 payload 를 바꾸지 않는다")은 그대로 유효하다. 표시 규칙이 다시 바뀌더라도
// LS_DATA_LBL 로 가는 className 은 마스터 원문이어야 한다 — 이 테스트가 그 경계를 고정한다.

import { fireEvent, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import MockAdapter from 'axios-mock-adapter';

import { apiClient } from '@/lib/api/client';
import { useLabelStore } from '@/stores/useLabelStore';
import { renderWithProviders } from '@/test/renderWithProviders';

import { ObjectAttributePanel } from '../components/ObjectAttributePanel';
import { resolveLabelDisplayName } from '../utils/labelDisplayName';
import type { Label } from '../types';

// 'bus' 는 COCO 한글 사전에 있는 이름이지만 화면·저장 값 모두 마스터 원문 'bus' 여야 한다.
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

describe('라벨 표시명 — 저장 payload 미혼입 가드', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
    useLabelStore.getState().reset();
  });

  afterEach(() => {
    mock.restore();
  });

  it('전제_bus는_화면에서도_bus로_표시된다', () => {
    expect(resolveLabelDisplayName('bus')).toBe('bus');
  });

  it('라벨_드롭다운_표시값과_저장값이_모두_마스터_원문_이름이다', async () => {
    mock.onGet('/manage/labels').reply(200, mastersPayload);
    useLabelStore.getState().setLabels([sample]);
    useLabelStore.getState().selectLabel('p1');
    renderWithProviders(<ObjectAttributePanel labels={[sample]} />);

    const select = (await waitFor(() =>
      screen.getByLabelText('라벨 선택'),
    )) as HTMLSelectElement;

    // 표시는 마스터 등록명 그대로 — 한글 치환 없음
    expect(select.textContent).toContain('bus');
    expect(select.textContent).not.toContain('버스');

    // 선택 → store 에 들어가는 className 도 원문 'bus'
    fireEvent.change(select, { target: { value: '22' } });

    const updated = useLabelStore.getState().labels.find((l) => l.id === 'p1');
    expect(updated?.className).toBe('bus');
    expect(updated?.classId).toBe(22);
  });
});
