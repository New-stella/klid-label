/**
 * 회귀 가드 — 「다시 등록」의 **끝에서 끝까지**: 실패 응답 → 재착수 창구 호출 → 목록 재조회 → 등록 중 판.
 * [@design SCREEN-046] [@design API-262] [@design API-253] [@design AC-1132]
 *
 * <h3>왜 훅을 모의하지 않는가</h3>
 * `DatasetVideoSection.test.tsx` 는 목록 훅을 모의하므로 무효화가 실제로 재조회를 일으키는지 볼 수 없다.
 * 여기서는 훅 둘 다 실물이고 **요청만** 모의한다 — 무효화 배선을 지우면 이 파일의 첫 케이스가 죽는다
 * (변이 실증: `useRestartDatasetRegistration` 의 `onSuccess` 를 빼면 등록 중 판이 영영 나타나지 않는다).
 *
 * ★ 주소 축을 본문 축과 따로 단언한다 — 요청 모의는 미매치 요청도 이력에 남기므로 건수만 보면 창구를
 *   바꾸는 변이를 통과시킨다.
 */
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import MockAdapter from 'axios-mock-adapter';

import { apiClient } from '@/lib/api/client';
import { PORTAL_KEYS } from '@/lib/queryKeys';
import { renderWithProviders } from '@/test/renderWithProviders';

import { DatasetVideoSection } from '../components/DatasetVideoSection';
import { PortalDatasetRegistrationFailureReason } from '../types';

const DATASET_ID = 5149;
const VIDEOS_URL = `/portal/datasets/${DATASET_ID}/videos`;
const REGISTRATION_URL = `/portal/datasets/${DATASET_ID}/registration`;

// ⚠ `as const` 를 붙이지 않는다 — 모의 어댑터의 콜백 반환 타입이 가변 배열이라 readonly 튜플이 들어가지 않는다.
function ok(data: unknown): [number, unknown] {
  return [200, { success: true, data, message: null, errorCode: null }];
}

function videosPage(registrationState: string, registrationFailureReason: string | null) {
  return {
    registrationState,
    registrationFailureReason,
    content: [],
    totalElements: 0,
    totalPages: 0,
    number: 0,
    size: 20,
  };
}

describe('데이터셋 영상 등록 재착수 — 끝에서 끝까지', () => {
  let mock: MockAdapter;
  beforeEach(() => {
    mock = new MockAdapter(apiClient);
  });
  afterEach(() => mock.restore());

  it('★다시_등록을_누르면_재착수_창구를_부르고_목록을_다시_불러_등록_중_판이_나타난다', async () => {
    // 재착수 전에는 실패, 재착수 뒤에는 진행 중을 답하는 서버.
    let restarted = false;
    mock.onGet(VIDEOS_URL).reply(() =>
      ok(
        restarted
          ? videosPage('IN_PROGRESS', null)
          : videosPage('FAILED', PortalDatasetRegistrationFailureReason.VIDEO_FILENAME_MISSING),
      ),
    );
    mock.onPost(REGISTRATION_URL).reply(() => {
      restarted = true;
      return ok({ registrationState: 'IN_PROGRESS', registrationFailureReason: null });
    });

    renderWithProviders(<DatasetVideoSection datasetId={DATASET_ID} />);

    const button = await screen.findByRole('button', { name: '다시 등록' });
    expect(mock.history.get.map((r) => r.url)).toEqual([VIDEOS_URL]);

    await userEvent.click(button);

    // ★재조회가 실제로 일어나 등록 중 판이 선다 — 무효화 배선을 빼면 여기서 죽는다.
    await screen.findByTestId('dataset-videos-registering');
    expect(screen.queryByTestId('dataset-videos-registration-failed')).not.toBeInTheDocument();

    // ★주소 축 — 재착수는 registration 창구를 POST 했고, 목록은 videos 창구를 두 번 GET 했다.
    expect(mock.history.post.map((r) => r.url)).toEqual([REGISTRATION_URL]);
    expect(mock.history.get.map((r) => r.url)).toEqual([VIDEOS_URL, VIDEOS_URL]);
    // 본문을 싣지 않는다 — 창구가 경로 변수 하나만 받는다.
    expect(mock.history.post[0].data).toBeUndefined();
  });

  it('★재착수가_503으로_거부되면_서버_안내_문장이_서고_실패_판은_그대로다', async () => {
    mock.onGet(VIDEOS_URL).reply(() =>
      ok(videosPage('FAILED', PortalDatasetRegistrationFailureReason.IO_ERROR)),
    );
    mock.onPost(REGISTRATION_URL).reply(503, {
      success: false,
      data: null,
      message: '등록 작업을 맡길 자리가 없습니다. 잠시 뒤 다시 시도해 주세요.',
      errorCode: 'SERVICE_UNAVAILABLE',
    });

    renderWithProviders(<DatasetVideoSection datasetId={DATASET_ID} />);
    await userEvent.click(await screen.findByRole('button', { name: '다시 등록' }));

    const banner = await screen.findByTestId('dataset-videos-restart-rejected');
    expect(banner).toHaveTextContent('등록 작업을 맡길 자리가 없습니다.');
    expect(screen.getByTestId('dataset-videos-registration-failed')).toBeInTheDocument();
    await waitFor(() => expect(screen.getByRole('button', { name: '다시 등록' })).toBeEnabled());
    // 거부는 목록을 다시 부르지 않는다 — 처음 1회뿐.
    expect(mock.history.get.map((r) => r.url)).toEqual([VIDEOS_URL]);
  });
});

describe('데이터셋 영상 목록 쿼리 키', () => {
  it('★페이지_키는_데이터셋_접두_키_뒤에_페이지를_붙인다_다른_데이터셋은_다른_키다', () => {
    const prefix = PORTAL_KEYS.datasetVideosOf(5149);
    expect(PORTAL_KEYS.datasetVideos(5149, 0).slice(0, prefix.length)).toEqual([...prefix]);
    expect(PORTAL_KEYS.datasetVideos(5149, 3).slice(0, prefix.length)).toEqual([...prefix]);
    expect(PORTAL_KEYS.datasetVideosOf(5150)).not.toEqual(prefix);
    expect(PORTAL_KEYS.datasetVideos(5150, 0)).not.toEqual(PORTAL_KEYS.datasetVideos(5149, 0));
  });
});
