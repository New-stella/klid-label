import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import MockAdapter from 'axios-mock-adapter';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { apiClient } from '@/lib/api/client';
import { ImportPage } from '@/pages/manage/ImportPage';
import { renderWithProviders } from '@/test/renderWithProviders';
import { useAuthStore } from '@/stores/useAuthStore';

const HISTORY_ONE = {
  success: true,
  data: {
    content: [
      {
        trnsfSn: 7,
        folderName: '00000073',
        rawSn: 1042,
        status: 'SUCCESS',
        frameCount: 120,
        labelCount: 480,
        approvalHeld: false,
        regId: 'admin',
        regDt: '2026-09-01T10:00:00',
      },
    ],
    totalElements: 1,
    totalPages: 1,
    number: 0,
    size: 20,
  },
  message: null,
  errorCode: null,
};
const EMPTY_MAPPINGS = {
  success: true,
  data: { items: [], page: 0, size: 20, totalElements: 0, totalPages: 0 },
  message: null,
  errorCode: null,
};
const OK_LIST = { success: true, data: [], message: null, errorCode: null };

/**
 * SCREEN-039 산출물 종류 갈래.
 *
 * ★두 갈래는 방향이 반대라 계약을 합치지 않고 화면에서만 고른다(ADR-053). 기본값은 라벨링
 * 완료이므로 기존 사용자에게는 아무것도 달라지지 않아야 한다.
 */
describe('SCREEN-039 산출물 종류 — 갈래 고르기', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
    mock.onGet('/imports').reply(200, HISTORY_ONE);
    mock.onGet('/import-mappings').reply(200, EMPTY_MAPPINGS);
    mock.onGet('/manage/labels').reply(200, OK_LIST);
    mock.onGet('/manage/event-types').reply(200, OK_LIST);
    mock.onGet('/event-types').reply(200, OK_LIST);
    useAuthStore.setState({
      token: 'tok',
      claims: { sub: '1', role: 'ADMIN', channel: 'INTERNAL', exp: 9999999999 },
    });
  });

  afterEach(() => {
    mock.restore();
    useAuthStore.getState().clear();
  });

  it('기본값은_라벨링_완료이고_기존_입력이_그대로_보인다', async () => {
    renderWithProviders(<ImportPage />);

    expect(await screen.findByRole('radio', { name: '라벨링 완료' })).toBeChecked();
    expect(screen.getByTestId('import-scan-form')).toBeInTheDocument();
    expect(screen.queryByTestId('marking-import-form')).not.toBeInTheDocument();
  });

  it('이벤트_마킹을_고르면_일괄_올리기가_나오고_라벨링_완료_입력은_사라진다', async () => {
    const user = userEvent.setup();
    renderWithProviders(<ImportPage />);
    await screen.findByTestId('import-scan-form');

    await user.click(screen.getByRole('radio', { name: '이벤트 마킹' }));

    expect(await screen.findByTestId('marking-import-form')).toBeInTheDocument();
    expect(screen.queryByTestId('import-scan-form')).not.toBeInTheDocument();
  });

  /**
   * ★가져온 내역은 산출물 종류 **바깥**에 둔다. 갈래를 바꿨다고 방금 가져온 내역이 사라지면
   * 안 된다(SCREEN-039). 이력 구획을 어느 한 갈래 안으로 옮기면 이 케이스가 깨진다.
   */
  it('★갈래를_바꿔도_가져온_내역은_사라지지_않는다', async () => {
    const user = userEvent.setup();
    renderWithProviders(<ImportPage />);

    // 라벨링 완료(기본값)에서 이력이 보인다
    expect(await screen.findByText('00000073')).toBeInTheDocument();

    await user.click(screen.getByRole('radio', { name: '이벤트 마킹' }));
    await screen.findByTestId('marking-import-form');

    // 이벤트 마킹으로 바꿔도 그대로 보인다
    expect(screen.getByText('00000073')).toBeInTheDocument();

    await user.click(screen.getByRole('radio', { name: '라벨링 완료' }));
    await screen.findByTestId('import-scan-form');
    expect(screen.getByText('00000073')).toBeInTheDocument();
  });

  /**
   * 두 갈래의 입력이 서로 섞이면 한쪽에 적은 경로가 다른 쪽 창구로 나간다. 갈래마다 상태를
   * 나눠 갖는지 확인한다.
   */
  it('★두_갈래의_폴더_입력이_서로_섞이지_않는다', async () => {
    const user = userEvent.setup();
    renderWithProviders(<ImportPage />);
    await screen.findByTestId('import-scan-form');

    await user.type(screen.getByLabelText(/산출물 폴더 경로/), '/nas-storage/labeled');
    await user.click(screen.getByRole('radio', { name: '이벤트 마킹' }));

    const markingPath = await screen.findByLabelText(/폴더 위치/);
    expect(markingPath).toHaveValue('');

    await user.type(markingPath, '/nas-storage/marking');
    await user.click(screen.getByRole('radio', { name: '라벨링 완료' }));

    expect(await screen.findByLabelText(/산출물 폴더 경로/)).toHaveValue('/nas-storage/labeled');
  });

  /**
   * 분류 대응은 라벨링 완료 갈래에만 있는 축이다(마킹 문서에는 분류가 없다).
   *
   * ★긍정 단언을 함께 둔다 — 「없다」만 보면 그 구획의 식별자가 바뀌어도 그대로 통과해,
   * 가드가 무엇을 보고 있는지 알 수 없게 된다.
   */
  it('분류_대응_구획은_라벨링_완료에만_있고_마킹_갈래에는_없다', async () => {
    const user = userEvent.setup();
    renderWithProviders(<ImportPage />);

    expect(await screen.findByTestId('import-mappings-section')).toBeInTheDocument();

    await user.click(screen.getByRole('radio', { name: '이벤트 마킹' }));
    await screen.findByTestId('marking-import-form');

    await waitFor(() =>
      expect(screen.queryByTestId('import-mappings-section')).not.toBeInTheDocument(),
    );
  });
});
