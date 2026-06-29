import { screen, waitFor } from '@testing-library/react';
import MockAdapter from 'axios-mock-adapter';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { VideoFilters } from '@/features/video/components/VideoFilters';
import { apiClient } from '@/lib/api/client';
import { renderWithProviders } from '@/test/renderWithProviders';

const CATEGORIES = [
  { categoryKey: '010001', label: '침수(범람)', memberCodes: ['EV01000101'] },
  { categoryKey: '010002', label: '산사태', memberCodes: ['EV01000201'] },
  { categoryKey: '020001', label: '화재', memberCodes: ['EV02000101'] },
  { categoryKey: '020002', label: '쓰러짐', memberCodes: ['EV02000201'] },
  { categoryKey: '030001', label: '파손', memberCodes: ['EV03000101'] },
  { categoryKey: '040001', label: '교통사고', memberCodes: ['EV04000101'] },
  { categoryKey: '050001', label: '싸움', memberCodes: ['EV05000101'] },
  { categoryKey: '060001', label: '흉기소지', memberCodes: ['EV06000101'] },
  { categoryKey: '070001', label: '납치(유괴)', memberCodes: ['EV07000101'] },
];

describe('VideoFilters', () => {
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

  it('필터_드롭다운이_9카테고리를_label로_렌더한다', async () => {
    renderWithProviders(
      <VideoFilters initial={{ page: 0, size: 20 }} onApply={vi.fn()} />,
    );

    // 전체 이벤트 + 9 카테고리
    expect(screen.getByRole('option', { name: '전체 이벤트' })).toBeInTheDocument();
    const opt = await screen.findByRole('option', { name: '침수(범람)' });
    expect(opt).toBeInTheDocument();
    expect((opt as HTMLOptionElement).value).toBe('010001');
    expect(screen.getByRole('option', { name: '납치(유괴)' })).toBeInTheDocument();

    const eventSelect = screen.getByLabelText('이벤트 유형');
    await waitFor(() =>
      // 전체(1) + 9 카테고리 = 10
      expect(eventSelect.querySelectorAll('option')).toHaveLength(10),
    );
  });

  it('이벤트_옵션_로딩중에는_select가_비활성화된다', async () => {
    // given — /event-types 응답을 지연시켜 로딩 상태를 관찰
    mock.onGet('/event-types').reply(
      () =>
        new Promise((resolve) =>
          setTimeout(
            () =>
              resolve([
                200,
                { success: true, data: CATEGORIES, message: null, errorCode: null },
              ]),
            50,
          ),
        ),
    );

    // when
    renderWithProviders(
      <VideoFilters initial={{ page: 0, size: 20 }} onApply={vi.fn()} />,
    );

    // then — 초기 로딩 동안 disabled, "전체 이벤트" 옵션은 유지
    const eventSelect = screen.getByLabelText('이벤트 유형') as HTMLSelectElement;
    expect(eventSelect.disabled).toBe(true);
    expect(screen.getByRole('option', { name: '전체 이벤트' })).toBeInTheDocument();

    // 로드 완료 후 활성화
    await waitFor(() => expect(eventSelect.disabled).toBe(false));
  });
});
