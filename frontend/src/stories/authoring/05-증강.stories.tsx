import { type ReactNode, useEffect } from 'react';
import type { Meta, StoryObj } from '@storybook/react-vite';
import { userEvent, within } from 'storybook/test';

import { MANY_ROWS, RESULT_ULD_SN, 결과응답, 목록응답 } from '@/demo/screens/augments';

import { type ApiMocks, fail, ok, pageOf, pending, 화면 } from './storyScreen';

/* 저작도구 · 증강. 상태 이름은 포털 스토리북(6008) 「워크스페이스 / 저작도구 / 증강」과 같다.
   기본은 요청 세 건(결과 대기 중 · 결과 도착 · 실패)이 선 모습이다 */
const meta: Meta = {
  title: '저작도구/증강',
};

export default meta;
type Story = StoryObj;

const 주소 = '/portal/augment';

/* 견본 값은 시연판과 같이 쓴다(`demo/screens/augments`) */

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
