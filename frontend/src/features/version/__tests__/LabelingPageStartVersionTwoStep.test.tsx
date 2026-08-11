// R6 / API-195·API-196 — 라벨링 화면의 「불러오기 → 확정 저장」 2단계 (2026-08-11 확정, 구속).
//
// ★ 이 파일이 지키는 핵심: **불러오기만으로는 서버가 바뀌지 않는다.** 그래야 "불러왔는데 아니네" 하고
//   저장하지 않고 떠날 창이 생긴다. 구 동작(고르는 순간 즉시 서버 작업본 교체)은 폐기됐다.
//
// ⚠ 모달 내부 동작은 `StartVersionModal.test.tsx`, 세트 변환은 `loadedVersionDraft.test.ts` 가 본다.
//   여기서는 **화면 전체에서 실제로 어떤 요청이 나가는지**만 본다(요청 유무가 계약이므로).

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import MockAdapter from 'axios-mock-adapter';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

vi.mock('react-konva', async () => (await import('@/test/konvaMock')).createKonvaMock());

import { apiClient } from '@/lib/api/client';
import { LabelingPage } from '@/pages/label/LabelingPage';
import { renderWithProviders } from '@/test/renderWithProviders';
import { useAuthStore } from '@/stores/useAuthStore';

const RAW_SN = 7;
const SRC_SN = 300;

const ok = (data: unknown) => ({ success: true, data, message: null, errorCode: null });

function labelsPayload() {
  return ok({
    frameNo: 0,
    srcSn: SRC_SN,
    videoId: RAW_SN,
    labelVersion: 4,
    dscdYn: 'N',
    siblings: [{ srcSn: SRC_SN, frameNo: 0, hasLabel: true, dscdYn: 'N' }],
    labels: [
      {
        id: 8001,
        lblTypeCd: 'BBOX',
        label: '차량',
        labelId: 33,
        points: [
          [1, 1],
          [9, 9],
        ],
        autoLblYn: 'N',
      },
    ],
  });
}

/** API-195 — v1 시점에는 라벨이 '사람'(labelId 12) 하나였다. */
const loadedV1 = ok({
  rawSn: RAW_SN,
  version: 1,
  frames: [
    {
      srcSn: SRC_SN,
      frmNo: 0,
      dscdYn: 'N',
      lblVer: 4,
      resolved: true,
      items: [
        {
          id: 9001,
          lblTypeCd: 'BBOX',
          label: '사람',
          labelId: 12,
          points: [
            [10, 10],
            [50, 50],
          ],
        },
      ],
    },
  ],
});

describe('LabelingPage — 시작 버전 불러오기/확정 저장 2단계', () => {
  let mock: MockAdapter;
  /** 서버를 바꾸는 요청만 모은다 — "불러오기가 서버를 바꾸지 않는다"의 판정 근거. */
  let writes: string[];

  beforeEach(() => {
    writes = [];
    mock = new MockAdapter(apiClient);
    useAuthStore.setState({
      token: 'dummy',
      claims: { sub: '10', role: 'WORKER', channel: 'INTERNAL', exp: 9999999999 },
    });
    mock.onGet(`/frames/${SRC_SN}/labels`).reply(200, labelsPayload());
    mock.onGet(`/frames/${SRC_SN}/image`).reply(200, new Blob());
    mock.onGet(`/frames/${SRC_SN}/versions`).reply(200, ok([]));
    mock.onGet(/\/frames\/\d+\/label-history/).reply(200,
      ok({ content: [], number: 0, size: 20, totalElements: 0, totalPages: 0 }));
    // 산출 버전 2건 — 진입 시 「시작 버전 선택」이 자동으로 뜬다(확정 사양: 둘 이상일 때만).
    mock.onGet(`/videos/${RAW_SN}/versions`).reply(200,
      ok([
        { versionNo: 2, snapshotCnt: 1, latestRegDt: '2026-08-09T10:00:00' },
        { versionNo: 1, snapshotCnt: 1, latestRegDt: '2026-08-01T10:00:00' },
      ]));
    mock.onGet(`/videos/${RAW_SN}/versions/1/labels`).reply(200, loadedV1);
    mock.onGet(`/videos/${RAW_SN}/versions/2/labels`).reply(200, loadedV1);
    // 쓰기 표면 — 어느 것이든 발화하면 기록한다.
    mock.onPut(`/videos/${RAW_SN}/labels`).reply((config) => {
      writes.push(`PUT /videos/${RAW_SN}/labels ${config.data as string}`);
      return [200, ok({
        rawSn: RAW_SN,
        frames: [{ srcSn: SRC_SN, dscdYn: 'N', lblVer: 5 }],
        savedFrameCount: 1,
        discardedFrameCount: 0,
      })];
    });
    mock.onPut(`/frames/${SRC_SN}/labels`).reply((config) => {
      writes.push(`PUT /frames/${SRC_SN}/labels ${config.data as string}`);
      return [200, labelsPayload().data];
    });
    // 구 즉시적용 경로 — 발화하면 즉시 드러나야 한다(폐기 회귀 가드).
    mock.onPut(`/videos/${RAW_SN}/start-version`).reply(() => {
      writes.push('PUT /videos/start-version');
      return [200, ok({})];
    });
  });

  afterEach(() => {
    mock.restore();
    useAuthStore.getState().clear();
  });

  async function loadV1(user: ReturnType<typeof userEvent.setup>) {
    renderWithProviders(<LabelingPage />, {
      initialEntries: [`/label/${SRC_SN}`],
      routes: [{ path: '/label/:id', element: <LabelingPage /> }],
    });
    await waitFor(() => expect(screen.getByTestId('labeling-page')).toBeInTheDocument());
    await screen.findByTestId('start-version-modal');
    await user.click(await screen.findByTestId('start-version-radio-1'));
    await user.click(screen.getByTestId('start-version-apply'));
    await screen.findByTestId('start-version-result');
  }

  it('불러오기만_하고_닫으면_서버_저장_요청이_발생하지_않는다', async () => {
    const user = userEvent.setup();

    await loadV1(user);
    // 모달을 닫는다 — 저장을 누르지 않았다.
    await user.click(screen.getByTestId('start-version-keep-working'));

    // then: 어떤 쓰기도 나가지 않았다. 서버 작업본은 그대로이므로 사용자는 되돌릴 수 있다.
    expect(writes).toEqual([]);
  });

  it('확정_저장을_눌러야_PUT_이_한_번_발생한다', async () => {
    const user = userEvent.setup();

    await loadV1(user);
    await user.click(screen.getByTestId('start-version-keep-working'));
    // 저장 버튼(헤더) — 회차를 불러온 뒤에는 영상 단위 확정 저장으로 갈린다.
    await user.click(await screen.findByTestId('label-toolbar-save'));

    await waitFor(() => expect(writes).toHaveLength(1));
    const [sent] = writes;
    expect(sent).toContain(`PUT /videos/${RAW_SN}/labels`);
    const body = JSON.parse(sent.slice(sent.indexOf('{')));
    // 불러온 회차 기록 + 판번호 되돌려보내기 + labelId 보존
    expect(body.loadedVersion).toBe('1');
    expect(body.frames).toHaveLength(1);
    expect(body.frames[0]).toMatchObject({ srcSn: SRC_SN, lblVer: 4, dscdYn: 'N' });
    expect(body.frames[0].items[0]).toMatchObject({ id: 9001, labelId: 12 });
    // 프레임 단위 저장으로 새지 않는다(두 축이 섞이면 회차가 섞인 영상이 확정된다).
    expect(sent).not.toContain(`/frames/${SRC_SN}/labels`);
  });

  it('구_즉시적용_경로는_화면에서_호출되지_않는다', async () => {
    const user = userEvent.setup();

    await loadV1(user);
    await user.click(screen.getByTestId('start-version-keep-working'));
    await user.click(await screen.findByTestId('label-toolbar-save'));

    await waitFor(() => expect(writes).toHaveLength(1));
    expect(writes.some((w) => w.includes('start-version'))).toBe(false);
  });

  it('닫기_가드의_저장도_영상_단위_확정으로_간다 — 프레임_하나만_저장하지_않는다', async () => {
    // ★ 저장 호출부가 셋(헤더 저장·프레임 이동 가드·닫기 가드)이라 한 곳만 프레임 단위로 새면
    //   불러온 세트에서 그 프레임만 확정되어 한 영상에 서로 다른 회차가 섞인다.
    const user = userEvent.setup();

    await loadV1(user);
    await user.click(screen.getByTestId('start-version-keep-working'));
    await user.click(await screen.findByLabelText('뒤로가기'));
    await user.click(await screen.findByTestId('label-close-save'));

    await waitFor(() => expect(writes).toHaveLength(1));
    expect(writes[0]).toContain(`PUT /videos/${RAW_SN}/labels`);
  });

  it('불러온_뒤_저장하지_않으면_미저장_상태로_표시된다', async () => {
    const user = userEvent.setup();

    await loadV1(user);
    await user.click(screen.getByTestId('start-version-keep-working'));

    // 미저장 표식이 없으면 이탈 경고·프레임 가드가 풀린 채 불러온 내용이 조용히 사라진다.
    expect(await screen.findByText('편집 중')).toBeInTheDocument();
  });
});
