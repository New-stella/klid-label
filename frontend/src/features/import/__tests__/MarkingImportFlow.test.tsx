import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import MockAdapter from 'axios-mock-adapter';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { MarkingImportSection } from '@/features/import/components/MarkingImportSection';
import { apiClient } from '@/lib/api/client';
import { renderWithProviders } from '@/test/renderWithProviders';
import { selectRadixOption } from '@/test/selectTestUtils';
import { useAuthStore } from '@/stores/useAuthStore';

const EVENT_TYPES = {
  success: true,
  data: [{ categoryKey: 'EV01000101', label: '침수(범람)', memberCodes: ['EV01000101'] }],
  message: null,
  errorCode: null,
};

const SCAN_OK = {
  success: true,
  data: {
    items: [
      {
        markingFileName: 'org_REPORT_A.json',
        clipId: 'A',
        videoFileName: 'A.mp4',
        videoFound: true,
        segmentCount: 1,
        markCount: 21,
        declaredFps: 29.997,
        probedFps: 29.97,
        videoFrameCount: 2396,
        importable: true,
        warnings: [],
      },
    ],
    scannedFileCount: 2,
    matchedCount: 1,
    importableCount: 1,
    unmatchedVideoCount: 0,
    unmatchedVideoNames: [],
    truncated: false,
    warnings: [],
  },
  message: null,
  errorCode: null,
};

function progressBody(data: Record<string, unknown>) {
  return {
    success: true,
    data: {
      jobSn: 31,
      status: 'RUNNING',
      folderPath: '/nas-storage/handover/marking-20260706',
      targetCount: 2,
      doneCount: 1,
      succeededCount: 1,
      failedCount: 0,
      startedAt: '2026-08-24T09:40:11',
      finishedAt: null,
      itemsTruncated: false,
      items: [],
      ...data,
    },
    message: null,
    errorCode: null,
  };
}

async function fillMeta(user: ReturnType<typeof userEvent.setup>) {
  await selectRadixOption(
    user,
    screen.getByRole('combobox', { name: /이벤트 유형/ }),
    '침수(범람)',
  );
  await selectRadixOption(
    user,
    screen.getByRole('combobox', { name: /개인정보 유형/ }),
    /개인정보 포함/,
  );
  await user.type(screen.getByLabelText(/지자체 코드/), '4113500000');
  await user.type(screen.getByLabelText(/CCTV ID/), 'CCTV_0001');
}

/**
 * SCREEN-039 이벤트 마킹 갈래 — 검사에서 적재 등록·진행 조회까지.
 *
 * @design API-216 API-217 API-218
 */
describe('SCREEN-039 이벤트 마킹 — 검사·적재·진행', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
    mock.onGet('/event-types').reply(200, EVENT_TYPES);
    useAuthStore.setState({
      token: 'tok',
      claims: { sub: '1', role: 'ADMIN', channel: 'INTERNAL', exp: 9999999999 },
    });
  });

  afterEach(() => {
    mock.restore();
    useAuthStore.getState().clear();
  });

  it('폴더_위치가_비면_검사할_수_없다', async () => {
    renderWithProviders(<MarkingImportSection />);
    expect(await screen.findByTestId('marking-scan-button')).toBeDisabled();
  });

  /**
   * ★적재 응답은 202 이고 본문에 담긴 것은 작업 식별번호와 대상 수뿐이다. 영상 목록도 결과도
   * 없으므로, 성공 처리를 200/201 에 묶거나 응답에서 결과를 읽으면 이 흐름이 깨진다. 진행은
   * 작업 식별번호로 따로 조회한다.
   */
  it('★적재는_202로_접수되고_진행은_작업_식별번호로_따로_조회한다', async () => {
    const user = userEvent.setup();
    mock.onPost('/imports/markings/scan').reply(200, SCAN_OK);
    mock.onPost('/imports/markings').reply(202, {
      success: true,
      data: { jobSn: 31, targetCount: 1 },
      message: null,
      errorCode: null,
    });
    mock.onGet('/imports/markings/31').reply(
      200,
      progressBody({
        targetCount: 1,
        doneCount: 1,
        succeededCount: 1,
        status: 'COMPLETED',
        items: [
          {
            markingFileName: 'org_REPORT_A.json',
            videoFileName: 'A.mp4',
            status: 'SUCCESS',
            rawSn: 1042,
            failureReason: null,
          },
        ],
      }),
    );

    renderWithProviders(<MarkingImportSection />);
    await user.type(screen.getByLabelText(/폴더 위치/), '/nas-storage/handover/marking-20260706');
    await user.click(screen.getByTestId('marking-scan-button'));
    await screen.findByTestId('marking-scan-result');

    await fillMeta(user);
    await user.click(screen.getByTestId('marking-submit-button'));

    // 진행 패널은 적재 응답이 아니라 진행 조회가 채운다
    expect(await screen.findByTestId('marking-progress-panel')).toHaveTextContent('작업 31');
    await waitFor(() =>
      expect(screen.getByTestId('marking-progress-row-org_REPORT_A.json')).toHaveTextContent(
        '1042',
      ),
    );
    expect(screen.getByTestId('marking-progress-count')).toHaveTextContent('1 / 1');
  });

  it('★적재_요청에_고른_항목과_공통_정보가_그대로_실린다_영상_식별자는_싣지_않는다', async () => {
    const user = userEvent.setup();
    mock.onPost('/imports/markings/scan').reply(200, SCAN_OK);
    mock.onPost('/imports/markings').reply(202, {
      success: true,
      data: { jobSn: 31, targetCount: 1 },
      message: null,
      errorCode: null,
    });
    mock.onGet('/imports/markings/31').reply(200, progressBody({}));

    renderWithProviders(<MarkingImportSection />);
    await user.type(screen.getByLabelText(/폴더 위치/), '/nas-storage/handover/marking-20260706');
    await user.click(screen.getByTestId('marking-scan-button'));
    await screen.findByTestId('marking-scan-result');

    await fillMeta(user);
    await user.click(screen.getByTestId('marking-submit-button'));

    await waitFor(() => expect(mock.history.post).toHaveLength(2));
    const body = JSON.parse(mock.history.post[1].data);
    expect(body).toEqual({
      folderPath: '/nas-storage/handover/marking-20260706',
      meta: {
        eventTypeCd: 'EV01000101',
        localGovCd: '4113500000',
        cctvId: 'CCTV_0001',
        prvcTypeCd: 'PRVC',
      },
      targets: ['org_REPORT_A.json'],
    });
    // ★영상 식별자는 영상 파일 이름에서 얻으므로 요청에 담지 않는다
    expect(Object.keys(body.meta)).not.toContain('clipId');
    // 촬영 일시는 비웠으므로 키 자체가 실리지 않는다(빈 문자열은 「이 값이다」로 읽힌다)
    expect(Object.keys(body.meta)).not.toContain('capturedAt');
  });

  it('★건별_결과가_일부만_담겼으면_그_사실을_알린다', async () => {
    const user = userEvent.setup();
    mock.onPost('/imports/markings/scan').reply(200, SCAN_OK);
    mock.onPost('/imports/markings').reply(202, {
      success: true,
      data: { jobSn: 31, targetCount: 1 },
      message: null,
      errorCode: null,
    });
    mock.onGet('/imports/markings/31').reply(200, progressBody({ itemsTruncated: true }));

    renderWithProviders(<MarkingImportSection />);
    await user.type(screen.getByLabelText(/폴더 위치/), '/nas-storage/handover/marking-20260706');
    await user.click(screen.getByTestId('marking-scan-button'));
    await screen.findByTestId('marking-scan-result');
    await fillMeta(user);
    await user.click(screen.getByTestId('marking-submit-button'));

    const alert = await screen.findByTestId('marking-items-truncated');
    expect(alert).toHaveTextContent('전체가 아닙니다');
    expect(alert).toHaveAttribute('role', 'alert');
  });

  it('건별_결과를_다_담았으면_그_안내를_띄우지_않는다', async () => {
    const user = userEvent.setup();
    mock.onPost('/imports/markings/scan').reply(200, SCAN_OK);
    mock.onPost('/imports/markings').reply(202, {
      success: true,
      data: { jobSn: 31, targetCount: 1 },
      message: null,
      errorCode: null,
    });
    mock.onGet('/imports/markings/31').reply(200, progressBody({ itemsTruncated: false }));

    renderWithProviders(<MarkingImportSection />);
    await user.type(screen.getByLabelText(/폴더 위치/), '/nas-storage/handover/marking-20260706');
    await user.click(screen.getByTestId('marking-scan-button'));
    await screen.findByTestId('marking-scan-result');
    await fillMeta(user);
    await user.click(screen.getByTestId('marking-submit-button'));

    await screen.findByTestId('marking-progress-panel');
    await waitFor(() =>
      expect(screen.queryByTestId('marking-items-truncated')).not.toBeInTheDocument(),
    );
  });

  /**
   * ★상태로 거르는 것은 담기는 목록뿐이며 위쪽 집계는 언제나 전체 기준이다. 거르기가 집계까지
   * 좁히면 사람이 보는 진행률이 필터에 따라 달라져 무엇이 참인지 알 수 없다.
   */
  it('★상태로_걸러도_위쪽_집계는_전체_기준이다', async () => {
    const user = userEvent.setup();
    mock.onPost('/imports/markings/scan').reply(200, SCAN_OK);
    mock.onPost('/imports/markings').reply(202, {
      success: true,
      data: { jobSn: 31, targetCount: 1 },
      message: null,
      errorCode: null,
    });
    mock
      .onGet('/imports/markings/31', { params: {} })
      .reply(
        200,
        progressBody({
          targetCount: 5,
          doneCount: 5,
          succeededCount: 4,
          failedCount: 1,
          status: 'FAILED',
          items: [
            {
              markingFileName: 'org_REPORT_A.json',
              videoFileName: 'A.mp4',
              status: 'SUCCESS',
              rawSn: 1042,
              failureReason: null,
            },
            {
              markingFileName: 'org_REPORT_B.json',
              videoFileName: 'B.mp4',
              status: 'FAILED',
              rawSn: null,
              failureReason: '영상 재생 속도가 어긋납니다.',
            },
          ],
        }),
      );
    mock.onGet('/imports/markings/31', { params: { status: 'FAILED' } }).reply(
      200,
      progressBody({
        targetCount: 5,
        doneCount: 5,
        succeededCount: 4,
        failedCount: 1,
        status: 'FAILED',
        items: [
          {
            markingFileName: 'org_REPORT_B.json',
            videoFileName: 'B.mp4',
            status: 'FAILED',
            rawSn: null,
            failureReason: '영상 재생 속도가 어긋납니다.',
          },
        ],
      }),
    );

    renderWithProviders(<MarkingImportSection />);
    await user.type(screen.getByLabelText(/폴더 위치/), '/nas-storage/handover/marking-20260706');
    await user.click(screen.getByTestId('marking-scan-button'));
    await screen.findByTestId('marking-scan-result');
    await fillMeta(user);
    await user.click(screen.getByTestId('marking-submit-button'));

    await screen.findByTestId('marking-progress-row-org_REPORT_A.json');
    expect(screen.getByTestId('marking-succeeded-count')).toHaveTextContent('4');

    await selectRadixOption(user, screen.getByRole('combobox', { name: /상태로 거르기/ }), '실패');

    await waitFor(() =>
      expect(screen.queryByTestId('marking-progress-row-org_REPORT_A.json')).not.toBeInTheDocument(),
    );
    expect(screen.getByTestId('marking-progress-row-org_REPORT_B.json')).toBeInTheDocument();
    // 집계는 그대로다
    expect(screen.getByTestId('marking-succeeded-count')).toHaveTextContent('4');
    expect(screen.getByTestId('marking-failed-count')).toHaveTextContent('1');
  });

  it('적재를_등록하지_못하면_서버_문구를_그대로_보여준다', async () => {
    const user = userEvent.setup();
    mock.onPost('/imports/markings/scan').reply(200, SCAN_OK);
    mock.onPost('/imports/markings').reply(403, {
      success: false,
      data: null,
      message: '권한이 없습니다.',
      errorCode: 'FORBIDDEN',
    });

    renderWithProviders(<MarkingImportSection />);
    await user.type(screen.getByLabelText(/폴더 위치/), '/nas-storage/handover/marking-20260706');
    await user.click(screen.getByTestId('marking-scan-button'));
    await screen.findByTestId('marking-scan-result');
    await fillMeta(user);
    await user.click(screen.getByTestId('marking-submit-button'));

    expect(await screen.findByTestId('marking-submit-error')).toHaveTextContent('권한이 없습니다.');
    // 접수되지 않았으므로 진행 패널을 열지 않는다
    expect(screen.queryByTestId('marking-progress-panel')).not.toBeInTheDocument();
  });
});
