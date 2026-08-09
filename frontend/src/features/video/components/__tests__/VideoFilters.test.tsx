import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
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
    const user = userEvent.setup();
    renderWithProviders(
      <VideoFilters initial={{ page: 0, size: 20 }} onApply={vi.fn()} />,
    );

    const eventSelect = screen.getByLabelText('이벤트 유형');
    // 로드 완료(비활성 해제) 후 열어야 서버 카테고리가 옵션에 반영된다.
    await waitFor(() => expect(eventSelect).not.toBeDisabled());
    await user.click(eventSelect);

    // 전체 이벤트 + 9 카테고리 = 10
    const options = await screen.findAllByRole('option');
    expect(options).toHaveLength(10);
    expect(screen.getByRole('option', { name: '전체 이벤트' })).toBeInTheDocument();
    expect(screen.getByRole('option', { name: '침수(범람)' })).toBeInTheDocument();
    expect(screen.getByRole('option', { name: '납치(유괴)' })).toBeInTheDocument();
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

    // then — 초기 로딩 동안 disabled, "전체 이벤트" 는 트리거 텍스트로 이미 보인다
    const eventSelect = screen.getByLabelText('이벤트 유형');
    expect(eventSelect).toBeDisabled();
    expect(eventSelect).toHaveTextContent('전체 이벤트');

    // 로드 완료 후 활성화
    await waitFor(() => expect(eventSelect).not.toBeDisabled());
  });
});
