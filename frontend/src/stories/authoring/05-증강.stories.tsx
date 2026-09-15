import { type ReactNode, useEffect } from 'react';
import type { Meta, StoryObj } from '@storybook/react-vite';
import { userEvent, within } from 'storybook/test';

import type { PortalAugmentDetail, PortalAugmentSummary } from '@/features/portal/augments/types';
import type { PortalUploadDetail } from '@/features/portal/uploads/types';

import { type ApiMocks, fail, ok, pageOf, pending, 화면 } from './storyScreen';

/* 저작도구 · 증강. 상태 이름은 포털 스토리북(6008) 「워크스페이스 / 저작도구 / 증강」과 같다.
   기본은 요청 세 건(결과 대기 중 · 결과 도착 · 실패)이 선 모습이다 */
const meta: Meta = {
  title: '저작도구/증강',
};

export default meta;
type Story = StoryObj;

const 주소 = '/portal/augment';

/** 결과물 자산 — 결과가 도착한 요청의 결과물 위치 */
const RESULT_ULD_SN = 77;
const RESULT_FRAME_SN = 7701;

const cond = (time: string, season: string, weather: string, terrain: string, severity: string) => ({
  time,
  season,
  weather,
  terrain,
  severity,
});

/** 최근 요청이 위다 — 같은 영상에 조건을 달리해 세 번 낸 모습 (포털 견본 `AUTHORING_AUGMENTS` 와 같은 값) */
const ROWS: PortalAugmentSummary[] = [
  {
    augSn: 13,
    uldSn: 1,
    augSttsCd: 'REQUESTED',
    orgnlFileNm: '실종자추적_v0.7_20260910.mp4',
    requestedAt: '2026-09-11T11:32:00',
    resultReady: false,
    resultArrivedAt: null,
    generationCondition: cond('NIGHT', 'WINTER', 'SNOW', 'URBAN', 'HIGH'),
    failRsnCn: null,
  },
  {
    augSn: 12,
    uldSn: 1,
    augSttsCd: 'COMPLETED',
    orgnlFileNm: '실종자추적_v0.7_20260910.mp4',
    requestedAt: '2026-09-11T11:02:00',
    resultReady: true,
    resultArrivedAt: '2026-09-11T11:09:00',
    generationCondition: cond('DUSK', 'AUTUMN', 'RAIN', 'ROAD', 'MEDIUM'),
    failRsnCn: null,
  },
  {
    augSn: 11,
    uldSn: 1,
    augSttsCd: 'FAILED',
    orgnlFileNm: '실종자추적_v0.7_20260910.mp4',
    requestedAt: '2026-09-11T10:55:00',
    resultReady: false,
    resultArrivedAt: null,
    generationCondition: cond('DAY', 'SUMMER', 'FOG', 'RIVER', 'LOW'),
    failRsnCn: '외부 위탁 거절',
  },
];

/** 여러 쪽 — 한 쪽(20건)을 세 줄 견본으로 채운다. 전체 47건 · 3쪽 */
const MANY_ROWS: PortalAugmentSummary[] = Array.from({ length: 20 }, (_, i) => ({
  ...ROWS[i % ROWS.length],
  augSn: 100 - i,
}));

const detailOf = (row: PortalAugmentSummary): PortalAugmentDetail => ({
  ...row,
  resultUldSn: row.resultReady ? RESULT_ULD_SN : null,
});

/** 결과물 자산 — 미리보기가 첫 장면 한 장을 여기서 고른다 */
const RESULT_ASSET: PortalUploadDetail = {
  uldSn: RESULT_ULD_SN,
  uldTypeCd: 'VIDEO',
  orgnlFileNm: 'augment-12-실종자추적_v0.7_20260910.mp4',
  fileSz: 48_213_504,
  mimeTypeNm: 'video/mp4',
  uldSttsCd: 'READY',
  frmeCnt: 1,
  vdoLenSec: 229,
  fps: 30,
  regDt: '2026-09-11T11:09:00',
  mdfcnDt: null,
  frames: [{ uldFrmeSn: RESULT_FRAME_SN, uldSn: RESULT_ULD_SN, frmeNo: 1, regDt: '2026-09-11T11:09:00' }],
  expiresAt: '2026-10-11T11:09:00',
};

/** 목록 — 세 줄 */
const 목록응답: ApiMocks = (mock) => {
  mock.onGet('/portal/augments').reply(200, ok(pageOf(ROWS))[1]);
};

/** 결과 확인 창이 부르는 것 — 요청 단건 · 결과물 자산 · 첫 장면 그림 */
const 결과응답: ApiMocks = (mock) => {
  mock.onGet(/^\/portal\/augments\/\d+$/).reply((config) => {
    const augSn = Number(config.url?.split('/').pop());
    const row = ROWS.find((r) => r.augSn === augSn);
    return row ? ok(detailOf(row)) : fail(403, null, 'FORBIDDEN');
  });
  mock.onGet(`/portal/uploads/${RESULT_ULD_SN}`).reply(200, ok(RESULT_ASSET)[1]);
  mock.onGet(`/portal/uploads/frames/${RESULT_FRAME_SN}/image`).reply(async () => {
    const blob = await fetch('/samples/dummy-5.jpg').then((r) => r.blob());
    return [200, blob];
  });
};

const 합치기 =
  (...mocks: ApiMocks[]): ApiMocks =>
  (mock) =>
    mocks.forEach((m) => m(mock));

/** 목록 줄의 「결과 확인」을 누른다 — 요청 일시로 줄을 가린다 */
const 결과확인누르기 = async (canvasElement: HTMLElement, requestedAt: string) => {
  const canvas = within(canvasElement);
  const button = await canvas.findByRole(
    'button',
    { name: new RegExp(`${requestedAt} 요청 결과 확인$`) },
    { timeout: 5000 },
  );
  await userEvent.click(button);
};

/** 창은 문서 끝에 뜬다 — 창 안의 걸음을 누른다 */
const 창안에서누르기 = async (canvasElement: HTMLElement, name: string) => {
  const body = within(canvasElement.ownerDocument.body);
  const button = await body.findByRole('button', { name }, { timeout: 5000 });
  await userEvent.click(button);
};

export const 기본: Story = { render: () => 화면(주소, 합치기(목록응답, 결과응답)) };

/* 목록 */
export const 요청_없음: Story = {
  name: '요청 없음',
  render: () => 화면(주소, (mock) => mock.onGet('/portal/augments').reply(200, ok(pageOf([]))[1])),
};

export const 목록_불러오는_중: Story = {
  name: '목록 불러오는 중',
  render: () => 화면(주소, (mock) => mock.onGet('/portal/augments').reply(() => pending())),
};

export const 목록_불러오기_실패: Story = {
  name: '목록 불러오기 실패',
  render: () => 화면(주소, (mock) => mock.onGet('/portal/augments').reply(...fail(500))),
};

export const 여러_쪽: Story = {
  name: '여러 쪽',
  render: () =>
    화면(주소, (mock) => mock.onGet('/portal/augments').reply(200, ok(pageOf(MANY_ROWS, 47))[1])),
};

/* 결과 확인 — 목록 줄의 「결과 확인」을 누른 뒤의 모습. 불러오는 중 · 실패 · 도착 쪽은 결과가 도착한 줄의 창이다 */
export const 결과_확인_불러오는_중: Story = {
  name: '결과 확인 · 불러오는 중',
  render: () =>
    화면(주소, (mock) => {
      목록응답(mock);
      mock.onGet(/^\/portal\/augments\/\d+$/).reply(() => pending());
    }),
  play: ({ canvasElement }) => 결과확인누르기(canvasElement, '11:02'),
};

export const 결과_확인_불러오기_실패: Story = {
  name: '결과 확인 · 불러오기 실패',
  render: () =>
    화면(주소, (mock) => {
      목록응답(mock);
      mock.onGet(/^\/portal\/augments\/\d+$/).reply(...fail(500));
    }),
  play: ({ canvasElement }) => 결과확인누르기(canvasElement, '11:02'),
};

export const 결과_확인_결과_기다리는_중: Story = {
  name: '결과 확인 · 결과 기다리는 중',
  render: () => 화면(주소, 합치기(목록응답, 결과응답)),
  play: ({ canvasElement }) => 결과확인누르기(canvasElement, '11:32'),
};

export const 결과_확인_실패: Story = {
  name: '결과 확인 · 실패',
  render: () => 화면(주소, 합치기(목록응답, 결과응답)),
  play: ({ canvasElement }) => 결과확인누르기(canvasElement, '10:55'),
};

export const 결과_확인_결과_도착: Story = {
  name: '결과 확인 · 결과 도착',
  render: () => 화면(주소, 합치기(목록응답, 결과응답)),
  play: ({ canvasElement }) => 결과확인누르기(canvasElement, '11:02'),
};

/* 받기 — 결과가 도착한 창에서 걸음을 누른 뒤의 모습 */
export const 결과_확인_라벨_내보내는_중: Story = {
  name: '결과 확인 · 라벨 내보내는 중',
  render: () =>
    화면(주소, (mock) => {
      합치기(목록응답, 결과응답)(mock);
      mock.onGet(`/portal/uploads/${RESULT_ULD_SN}/export`).reply(() => pending());
    }),
  play: async ({ canvasElement }) => {
    await 결과확인누르기(canvasElement, '11:02');
    await 창안에서누르기(canvasElement, '증강 결과물 라벨 내보내기');
  },
};

export const 결과_확인_원본_파일_받는_중: Story = {
  name: '결과 확인 · 원본 파일 받는 중',
  render: () =>
    화면(주소, (mock) => {
      합치기(목록응답, 결과응답)(mock);
      mock.onGet(`/portal/uploads/${RESULT_ULD_SN}/file`).reply(() => pending());
    }),
  play: async ({ canvasElement }) => {
    await 결과확인누르기(canvasElement, '11:02');
    await 창안에서누르기(canvasElement, '증강 결과물 원본 파일 내려받기');
  },
};

/**
 * 알림은 5초 뒤 저절로 사라진다. 알림 스토리에서만 그 타이머를 걸지 않아 모습을 세워 둔다 —
 * 포털 그림 스토리가 알림을 머물게 둔 것과 같은 까닭이다(내 업로드 스토리와 같은 방식). 화면 코드는 건드리지 않는다.
 */
const TOAST_MS = 5000;

function 알림을_세워_둔다({ children }: { children: ReactNode }) {
  useEffect(() => {
    const original = window.setTimeout;
    window.setTimeout = ((handler: TimerHandler, timeout?: number, ...args: unknown[]) =>
      timeout === TOAST_MS ? 0 : original(handler, timeout, ...args)) as typeof window.setTimeout;
    return () => {
      window.setTimeout = original;
    };
  }, []);
  return <>{children}</>;
}

/* 받기 실패 알림 */
export const 결과_확인_내보내기_실패_알림: Story = {
  name: '결과 확인 · 내보내기 실패 알림',
  render: () => (
    <알림을_세워_둔다>
      {화면(주소, (mock) => {
        합치기(목록응답, 결과응답)(mock);
        mock.onGet(`/portal/uploads/${RESULT_ULD_SN}/export`).reply(...fail(500));
      })}
    </알림을_세워_둔다>
  ),
  play: async ({ canvasElement }) => {
    await 결과확인누르기(canvasElement, '11:02');
    await 창안에서누르기(canvasElement, '증강 결과물 라벨 내보내기');
  },
};

export const 결과_확인_원본_다운로드_실패_알림: Story = {
  name: '결과 확인 · 원본 다운로드 실패 알림',
  render: () => (
    <알림을_세워_둔다>
      {화면(주소, (mock) => {
        합치기(목록응답, 결과응답)(mock);
        mock.onGet(`/portal/uploads/${RESULT_ULD_SN}/file`).reply(...fail(500));
      })}
    </알림을_세워_둔다>
  ),
  play: async ({ canvasElement }) => {
    await 결과확인누르기(canvasElement, '11:02');
    await 창안에서누르기(canvasElement, '증강 결과물 원본 파일 내려받기');
  },
};
