import { useEffect, type ReactNode } from 'react';
import type { Meta, StoryObj } from '@storybook/react-vite';
import type MockAdapter from 'axios-mock-adapter';
import { userEvent, waitFor, within } from 'storybook/test';

import type { PortalUpload } from '@/features/portal/uploads/types';
import {
  FAILED,
  PICKED_NAME,
  PICKED_SIZE,
  PROCESSING,
  READY,
  UPLOADED,
  UPLOADS,
  목록응답,
  올리기,
} from '@/demo/screens/uploads';

import { fail, ok, pending, 화면 } from './storyScreen';

/* 저작도구 · 내 업로드. 상태 이름은 포털 스토리북(6008) 「워크스페이스 / 저작도구 / 내 업로드」와 같다 */
const meta: Meta = {
  title: '저작도구/내 업로드',
};

export default meta;
type Story = StoryObj;

const 주소 = '/portal/uploads';


/** 화면 조각을 처음 불러오는 데 1초를 넘기기도 한다 — 누르기 전에 넉넉히 기다린다 */
const 화면_기다림 = 10_000;

const 목록 = (uploads: PortalUpload[], more?: (mock: MockAdapter) => void, total?: number) =>
  화면(주소, (mock) => {
    목록응답(mock, uploads, total);
    more?.(mock);
  });

/* ── 올리기 ──────────────────────────────────────────────────────────────── */


/** 업로드 칸에 영상을 넣는다 (파일선택 창에서 고른 것과 같다) */
async function 영상을_고른다(canvasElement: HTMLElement) {
  const input = await waitFor(
    () => {
      const found = canvasElement.querySelector<HTMLInputElement>('input[type="file"]');
      if (!found) throw new Error('업로드 칸의 파일 입력을 찾지 못했습니다');
      return found;
    },
    { timeout: 화면_기다림 },
  );
  const file = new File([new Uint8Array(PICKED_SIZE)], PICKED_NAME, { type: 'video/mp4' });
  await userEvent.upload(input, file);
}

async function 올리기를_누른다(canvasElement: HTMLElement) {
  await 영상을_고른다(canvasElement);
  const canvas = within(canvasElement);
  await userEvent.click(await canvas.findByRole('button', { name: '영상 업로드' }));
}

export const 기본: Story = { render: () => 목록(UPLOADS) };

export const 영상_고른_뒤: Story = {
  name: '영상 고른 뒤',
  render: () => 목록([READY]),
  play: ({ canvasElement }) => 영상을_고른다(canvasElement),
};

export const 올리는_중: Story = {
  name: '올리는 중',
  render: () => 목록([READY], (mock) => 올리기(mock, '멈춤')),
  play: ({ canvasElement }) => 올리기를_누른다(canvasElement),
};

export const 전송이_끊김: Story = {
  name: '전송이 끊김',
  render: () => 목록([READY], (mock) => 올리기(mock, '끊김')),
  play: ({ canvasElement }) => 올리기를_누른다(canvasElement),
};

export const 올리기_끝남: Story = {
  name: '올리기 끝남',
  render: () => 목록(UPLOADS, (mock) => 올리기(mock, '끝')),
  play: ({ canvasElement }) => 올리기를_누른다(canvasElement),
};

/* ── 목록 ──────────────────────────────────────────────────────────────── */

export const 줄_상태_네_가지: Story = {
  name: '줄 상태 네 가지',
  render: () => 목록([UPLOADED, PROCESSING, FAILED, READY]),
};

export const 목록_불러오는_중: Story = {
  name: '목록 불러오는 중',
  render: () => 화면(주소, (mock) => mock.onGet('/portal/uploads').reply(() => pending())),
};

export const 목록_불러오기_실패: Story = {
  name: '목록 불러오기 실패',
  render: () => 화면(주소, (mock) => mock.onGet('/portal/uploads').reply(...fail(500))),
};

export const 빈_목록: Story = { name: '빈 목록', render: () => 목록([]) };

export const 여러_쪽: Story = { name: '여러 쪽', render: () => 목록(UPLOADS, undefined, 47) };

/* ── 줄 동작 — 준비 완료 줄에서 누른 뒤의 모습 ─────────────────────────────── */

const 준비된_줄 = (canvasElement: HTMLElement, 동작: string) =>
  within(canvasElement).findByRole(
    'button',
    { name: `${READY.orgnlFileNm} ${동작}` },
    { timeout: 화면_기다림 },
  );

/**
 * 알림은 5초 뒤 저절로 사라진다. 알림 스토리에서만 그 타이머를 걸지 않아 모습을 세워 둔다 —
 * 포털 그림 스토리가 알림을 머물게 둔 것과 같은 까닭이다. 화면 코드는 건드리지 않는다.
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

export const 삭제_확인_창: Story = {
  name: '삭제 확인 창',
  render: () => 목록(UPLOADS),
  play: async ({ canvasElement }) => {
    await userEvent.click(await 준비된_줄(canvasElement, '삭제'));
  },
};

export const 삭제_실패: Story = {
  name: '삭제 실패',
  render: () => 목록(UPLOADS, (mock) => mock.onDelete(`/portal/uploads/${READY.uldSn}`).reply(...fail(500))),
  play: async ({ canvasElement }) => {
    await userEvent.click(await 준비된_줄(canvasElement, '삭제'));
    const page = within(canvasElement.ownerDocument.body);
    const dialog = await page.findByRole('dialog');
    await userEvent.click(within(dialog).getByRole('button', { name: '삭제' }));
  },
};

export const 원본_받는_중: Story = {
  name: '원본 받는 중',
  render: () => 목록(UPLOADS, (mock) => mock.onGet(`/portal/uploads/${READY.uldSn}/file`).reply(() => pending())),
  play: async ({ canvasElement }) => {
    await userEvent.click(await 준비된_줄(canvasElement, '원본 다운로드'));
  },
};

export const 내보내기_실패_알림: Story = {
  name: '내보내기 실패 알림',
  render: () => (
    <알림을_세워_둔다>
      {목록(UPLOADS, (mock) => mock.onGet(`/portal/uploads/${READY.uldSn}/export`).reply(...fail(500)))}
    </알림을_세워_둔다>
  ),
  play: async ({ canvasElement }) => {
    await userEvent.click(await 준비된_줄(canvasElement, '라벨 내보내기'));
  },
};

export const 원본_다운로드_실패_알림: Story = {
  name: '원본 다운로드 실패 알림',
  render: () => (
    <알림을_세워_둔다>
      {목록(UPLOADS, (mock) => mock.onGet(`/portal/uploads/${READY.uldSn}/file`).reply(...fail(500)))}
    </알림을_세워_둔다>
  ),
  play: async ({ canvasElement }) => {
    await userEvent.click(await 준비된_줄(canvasElement, '원본 다운로드'));
  },
};

/* ── AI 증강 요청 ─────────────────────────────────────────────────────── */

const AUGMENTS = `/portal/uploads/${READY.uldSn}/augments`;

/** 다섯 칸 — 밤 · 겨울 · 눈 · 도심 · 높음 */
const CONDITION: [string, string][] = [
  ['시간대', '밤'],
  ['계절', '겨울'],
  ['날씨', '눈'],
  ['지형', '도심'],
  ['심각도', '높음'],
];

async function 증강_창을_연다(canvasElement: HTMLElement) {
  await userEvent.click(await 준비된_줄(canvasElement, 'AI 증강 요청'));
  return within(canvasElement.ownerDocument.body).findByRole('dialog');
}

async function 조건을_채워_요청한다(canvasElement: HTMLElement) {
  const dialog = within(await 증강_창을_연다(canvasElement));
  const page = within(canvasElement.ownerDocument.body);
  for (const [field, value] of CONDITION) {
    await userEvent.click(dialog.getByRole('combobox', { name: field }));
    await userEvent.click(await page.findByRole('option', { name: value }));
  }
  await userEvent.click(dialog.getByRole('button', { name: '요청' }));
}

export const AI_증강_요청_창: Story = {
  name: 'AI 증강 요청 창',
  render: () => 목록(UPLOADS),
  play: async ({ canvasElement }) => {
    await 증강_창을_연다(canvasElement);
  },
};

export const AI_증강_요청_창_보내는_중: Story = {
  name: 'AI 증강 요청 창 · 보내는 중',
  render: () => 목록(UPLOADS, (mock) => mock.onPost(AUGMENTS).reply(() => pending())),
  play: ({ canvasElement }) => 조건을_채워_요청한다(canvasElement),
};

/** 반려 — 서버가 준 사유가 창 안 띠에 붙는다 (준비가 끝나지 않은 영상으로 되돌아온 경우) */
export const AI_증강_요청_창_반려됨: Story = {
  name: 'AI 증강 요청 창 · 반려됨',
  render: () =>
    목록(UPLOADS, (mock) =>
      mock
        .onPost(AUGMENTS)
        .reply(
          ...fail(409, '준비가 끝나지 않은 영상은 증강을 요청할 수 없습니다. 완료 후 다시 시도하세요.', 'CONFLICT'),
        ),
    ),
  play: ({ canvasElement }) => 조건을_채워_요청한다(canvasElement),
};

export const AI_증강_요청_접수_알림: Story = {
  name: 'AI 증강 요청 접수 알림',
  render: () => (
    <알림을_세워_둔다>
      {목록(UPLOADS, (mock) =>
        mock.onPost(AUGMENTS).reply(200, ok({ augSn: 901, uldSn: READY.uldSn, requestedAt: '2026-09-11T11:30:00' })[1]),
      )}
    </알림을_세워_둔다>
  ),
  play: ({ canvasElement }) => 조건을_채워_요청한다(canvasElement),
};
