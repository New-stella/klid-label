import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import MockAdapter from 'axios-mock-adapter';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { apiClient } from '@/lib/api/client';
import { ImportPage } from '@/pages/manage/ImportPage';
import { renderWithProviders } from '@/test/renderWithProviders';
import { useAuthStore } from '@/stores/useAuthStore';

const EMPTY_HISTORY = {
  success: true,
  data: { content: [], totalElements: 0, totalPages: 0, number: 0, size: 20 },
  message: null,
  errorCode: null,
};
const EMPTY_MAPPINGS = {
  success: true,
  data: { items: [], page: 0, size: 20, totalElements: 0, totalPages: 0 },
  message: null,
  errorCode: null,
};

function scanBody(data: Record<string, unknown>) {
  return {
    success: true,
    data: {
      frameCount: 120,
      declaredFrameCount: 120,
      labelCount: 480,
      videoFileName: '00000073.mp4',
      duplicate: null,
      unmappedCategories: [],
      warnings: [],
      importable: true,
      ...data,
    },
    message: null,
    errorCode: null,
  };
}

describe('SCREEN-039 외부 산출물 이관 — 화면 흐름', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
    mock.onGet('/imports').reply(200, EMPTY_HISTORY);
    mock.onGet('/import-mappings').reply(200, EMPTY_MAPPINGS);
    mock.onGet('/manage/labels').reply(200, { success: true, data: [], message: null, errorCode: null });
    mock
      .onGet('/manage/event-types')
      .reply(200, { success: true, data: [], message: null, errorCode: null });
    useAuthStore.setState({
      token: 'tok',
      claims: { sub: '1', role: 'REVIEWER', channel: 'INTERNAL', exp: 9999999999 },
    });
  });

  afterEach(() => {
    mock.restore();
    useAuthStore.getState().clear();
  });

  it('비식별_여부의_기본값은_원본이고_경고가_뜨지_않는다', async () => {
    renderWithProviders(<ImportPage />);
    await screen.findByTestId('import-scan-form');

    expect(screen.getByRole('radio', { name: '원본이다' })).toBeChecked();
    expect(screen.queryByTestId('import-deident-warning')).not.toBeInTheDocument();
  });

  /**
   * ★비식별이 끝난 것으로 고르면 그 산출물은 그대로 학습데이터로 나간다. 잘못 고르면 승인 뒤에
   * 되돌릴 수단이 사실상 없으므로 그 자리에서 알린다.
   */
  it('★비식별이_끝난_것으로_바꾸면_주의_문구가_뜬다', async () => {
    const user = userEvent.setup();
    renderWithProviders(<ImportPage />);
    await screen.findByTestId('import-scan-form');

    await user.click(screen.getByRole('radio', { name: '비식별이 끝난 것이다' }));

    expect(screen.getByTestId('import-deident-warning')).toHaveTextContent('되돌릴 수단');
  });

  it('경로를_비운_채로는_검사할_수_없다', async () => {
    renderWithProviders(<ImportPage />);
    expect(await screen.findByTestId('import-scan-button')).toBeDisabled();
  });

  it('검사하면_미리보기가_나오고_적재_요청은_비식별_지정을_함께_싣는다', async () => {
    mock.onPost('/imports/scan').reply(200, scanBody({}));
    mock.onPost('/imports').reply(201, {
      success: true,
      data: { rawSn: 42, trnsfSn: 7, frameCount: 120, labelCount: 480 },
      message: null,
      errorCode: null,
    });

    const user = userEvent.setup();
    renderWithProviders(<ImportPage />);
    await screen.findByTestId('import-scan-form');

    await user.type(screen.getByLabelText(/산출물 폴더 경로/), '/nas-storage/handover/00000073');
    await user.click(screen.getByTestId('import-scan-button'));

    expect(await screen.findByTestId('import-preview')).toBeInTheDocument();
    // 검사는 아무것도 저장하지 않는다 — 이 시점에 적재 요청은 나가지 않았다
    expect(mock.history.post.filter((r) => r.url === '/imports')).toHaveLength(0);

    await user.click(screen.getByTestId('import-execute-button'));

    await waitFor(() =>
      expect(mock.history.post.filter((r) => r.url === '/imports')).toHaveLength(1),
    );
    const body = JSON.parse(mock.history.post.find((r) => r.url === '/imports')?.data as string);
    expect(body).toMatchObject({
      folderPath: '/nas-storage/handover/00000073',
      deidentified: false,
    });
    expect(await screen.findByTestId('import-execute-result')).toHaveTextContent('42');
  });

  it('★적재가_409면_서버가_준_기존_영상_번호를_그대로_보여준다', async () => {
    mock.onPost('/imports/scan').reply(200, scanBody({}));
    mock.onPost('/imports').reply(409, {
      success: false,
      data: null,
      message: '이미 가져온 산출물입니다. 기존 영상 번호: 42',
      errorCode: 'CONFLICT',
    });

    const user = userEvent.setup();
    renderWithProviders(<ImportPage />);
    await screen.findByTestId('import-scan-form');

    await user.type(screen.getByLabelText(/산출물 폴더 경로/), '/nas-storage/handover/00000073');
    await user.click(screen.getByTestId('import-scan-button'));
    await screen.findByTestId('import-preview');
    await user.click(screen.getByTestId('import-execute-button'));

    expect(await screen.findByTestId('import-execute-error')).toHaveTextContent(
      '기존 영상 번호: 42',
    );
  });

  /**
   * ★대응이 정해지지 않은 분류가 남아 있으면 서버가 적재 가능 여부를 세우지 않는다. 화면은 그
   * 값을 그대로 읽어 버튼을 비활성으로 두고, 사람이 확정할 표를 함께 보여준다.
   *
   * 추천 후보는 **비어 있을 수 있다** — 그때는 사람이 직접 고르며 오류로 그리지 않는다.
   */
  it('★대응_미확정이_남으면_적재_버튼이_비활성이고_확정할_표가_함께_나온다', async () => {
    mock.onPost('/imports/scan').reply(
      200,
      scanBody({
        importable: false,
        unmappedCategories: [
          { kind: 'LABEL', externalCode: 'person', externalName: '사람', suggestions: [] },
        ],
      }),
    );

    const user = userEvent.setup();
    renderWithProviders(<ImportPage />);
    await screen.findByTestId('import-scan-form');

    await user.type(screen.getByLabelText(/산출물 폴더 경로/), '/nas-storage/handover/00000073');
    await user.click(screen.getByTestId('import-scan-button'));

    expect(await screen.findByTestId('import-unmapped-section')).toBeInTheDocument();
    expect(screen.getByTestId('import-execute-button')).toBeDisabled();
    // 후보가 비어도 오류로 그리지 않는다 — 행은 그대로 나오고 사람이 고른다
    expect(screen.getByTestId('import-unmapped-row-LABEL:person')).toBeInTheDocument();
    // 아무도 확인하지 않았으므로 확정 버튼은 비활성이다(추천을 자동 확정하지 않는다)
    expect(screen.getByTestId('import-mapping-confirm-button')).toBeDisabled();
  });

  it('대응이_없으면_그_표_자체가_뜨지_않는다', async () => {
    mock.onPost('/imports/scan').reply(200, scanBody({}));
    const user = userEvent.setup();
    renderWithProviders(<ImportPage />);
    await screen.findByTestId('import-scan-form');

    await user.type(screen.getByLabelText(/산출물 폴더 경로/), '/nas-storage/handover/00000073');
    await user.click(screen.getByTestId('import-scan-button'));

    await screen.findByTestId('import-preview');
    expect(screen.queryByTestId('import-unmapped-section')).not.toBeInTheDocument();
  });

  it('확정된_대응과_이관_이력_구획은_검사_전에도_보인다', async () => {
    renderWithProviders(<ImportPage />);
    expect(await screen.findByTestId('import-mappings-section')).toBeInTheDocument();
    expect(screen.getByTestId('import-history-section')).toBeInTheDocument();
  });
});
