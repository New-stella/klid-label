import { screen } from '@testing-library/react';
import MockAdapter from 'axios-mock-adapter';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';

import { PresetListPage } from '@/pages/manage/PresetListPage';
import { apiClient } from '@/lib/api/client';
import { renderWithProviders } from '@/test/renderWithProviders';

const ok = (data: unknown) => ({ success: true, data, message: null, errorCode: null });

// BE 카테고리 옵션 — categoryKey→label 변환 소스 (useEventTypes).
const CATEGORIES = [
  { categoryKey: '010001', label: '침수(범람)', memberCodes: ['EV01000101'] },
  { categoryKey: '020002', label: '쓰러짐', memberCodes: ['EV02000201'] },
];

// 프리셋은 categoryKey 를 eventTypeCd 로 저장한다 (Phase 4a/4b 확정).
const PRESETS = [
  {
    id: 1,
    name: '프리셋A',
    description: null,
    labelCodes: ['PERSON'],
    labelCodeOptions: [{ code: 'PERSON', bboxEnabled: true, polygonEnabled: true }],
    eventTypeCd: '020002',
    createdAt: '2026-06-01T00:00:00',
    updatedAt: '2026-06-01T00:00:00',
  },
  {
    id: 2,
    name: '프리셋B',
    description: null,
    labelCodes: ['CAR'],
    labelCodeOptions: [{ code: 'CAR', bboxEnabled: true, polygonEnabled: true }],
    eventTypeCd: null,
    createdAt: '2026-06-01T00:00:00',
    updatedAt: '2026-06-01T00:00:00',
  },
];

describe('PresetListPage', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
    mock.onGet('/manage/presets').reply(200, ok(PRESETS));
    mock.onGet('/event-types').reply(200, ok(CATEGORIES));
    // 회귀 가드 — 라벨 맵 엔드포인트는 더 이상 프리셋 표시에 쓰이지 않아야 한다.
    mock.onGet('/event-types/labels').reply(200, ok({ EV02000201: '쓰러짐(EVcode)' }));
  });

  afterEach(() => mock.restore());

  it('프리셋목록_categoryKey가_카테고리라벨로_표시된다', async () => {
    // given / when
    renderWithProviders(<PresetListPage />);

    // then — eventTypeCd="020002"(categoryKey) → "쓰러짐"(카테고리 label)
    const badge = await screen.findByTestId('preset-event-1');
    expect(badge).toHaveTextContent('쓰러짐');
    // EV-코드 맵 폴백 원문("020002")이 노출되면 안 된다.
    expect(badge).not.toHaveTextContent('020002');
  });

  it('미매핑_프리셋은_미매핑으로_표시된다', async () => {
    // given / when
    renderWithProviders(<PresetListPage />);

    // then
    const badge = await screen.findByTestId('preset-event-2');
    expect(badge).toHaveTextContent('미매핑');
  });
});
