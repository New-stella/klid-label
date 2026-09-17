import { useEffect, type ReactNode } from 'react';
import type { Meta, StoryObj } from '@storybook/react-vite';
import type MockAdapter from 'axios-mock-adapter';
import { userEvent, waitFor, within } from 'storybook/test';

import { PortalUploadStatus, PortalUploadType, type PortalUpload } from '@/features/portal/uploads/types';

import { fail, ok, pageOf, pending, 화면 } from './storyScreen';

/* 저작도구 · 내 업로드. 상태 이름은 포털 스토리북(6008) 「워크스페이스 / 저작도구 / 내 업로드」와 같다 */
const meta: Meta = {
  title: '저작도구/내 업로드',
};

export default meta;
type Story = StoryObj;

const 주소 = '/portal/uploads';

const KB = 1024;
const MB = KB * 1024;
const GB = MB * 1024;

/** 준비 완료 — 마킹을 마쳐 프레임 23장을 뽑은 영상 */
const READY: PortalUpload = {
  uldSn: 4,
  uldTypeCd: PortalUploadType.VIDEO,
  orgnlFileNm: '실종자추적_v0.7_20260910.mp4',
  fileSz: Math.round(31.4 * MB),
  mimeTypeNm: 'video/mp4',
  uldSttsCd: PortalUploadStatus.READY,
  frmeCnt: 23,
  frmeSn: 41,
  failRsnCn: null,
  regDt: '2026-09-11T10:49:00',
  expiresAt: '2026-09-18T10:49:00',
};

/** 고른 영상 — 아직 올리기 전 */
const PICKED_NAME = 'M-02_v0.8_20260910.mp4';
const PICKED_SIZE = 224 * KB;

/** 골라 둔 그 영상을 다 올린 뒤의 한 건 — 마킹 전이라 뽑힌 프레임이 없다 */
const UPLOADED: PortalUpload = {
  uldSn: 5,
  uldTypeCd: PortalUploadType.VIDEO,
  orgnlFileNm: PICKED_NAME,
  fileSz: PICKED_SIZE,
  mimeTypeNm: 'video/mp4',
  uldSttsCd: PortalUploadStatus.UPLOADED,
  frmeCnt: null,
  frmeSn: null,
  failRsnCn: null,
  regDt: '2026-09-11T11:17:00',
  expiresAt: '2026-09-18T11:17:00',
};

/** 마킹을 마쳐 프레임을 뽑는 중 — 이때만 만료일이 없다 */
const PROCESSING: PortalUpload = {
  uldSn: 3,
  uldTypeCd: PortalUploadType.VIDEO,
  orgnlFileNm: '침수도로_야간_v0.3_20260908.mp4',
  fileSz: Math.round(48.2 * MB),
  mimeTypeNm: 'video/mp4',
  uldSttsCd: PortalUploadStatus.PROCESSING,
  frmeCnt: null,
  frmeSn: null,
  failRsnCn: null,
  regDt: '2026-09-10T16:20:00',
  expiresAt: null,
};

/** 뽑다가 실패 — 서버가 사유를 주지 않아 화면의 기본 문구가 선다 */
const FAILED: PortalUpload = {
  uldSn: 2,
  uldTypeCd: PortalUploadType.VIDEO,
  orgnlFileNm: '산불연기_v0.1_20260905.mp4',
  fileSz: Math.round(1.24 * GB),
  mimeTypeNm: 'video/mp4',
  uldSttsCd: PortalUploadStatus.FAILED,
  frmeCnt: null,
  frmeSn: null,
  failRsnCn: null,
  regDt: '2026-09-09T09:41:00',
  expiresAt: '2026-09-16T09:41:00',
};

/** 기본 목록 — 다 올린 영상이 맨 위 「마킹 대기」 */
const UPLOADS = [UPLOADED, READY];

/** 화면 조각을 처음 불러오는 데 1초를 넘기기도 한다 — 누르기 전에 넉넉히 기다린다 */
const 화면_기다림 = 10_000;

/** 목록 응답 — 줄이 없는 쪽만 다른 응답을 건다 */
const 목록응답 = (mock: MockAdapter, uploads: PortalUpload[], total = uploads.length) =>
  mock.onGet('/portal/uploads').reply(200, ok(pageOf(uploads, total))[1]);

const 목록 = (uploads: PortalUpload[], more?: (mock: MockAdapter) => void, total?: number) =>
  화면(주소, (mock) => {
    목록응답(mock, uploads, total);
    more?.(mock);
  });

/* ── 올리기 ──────────────────────────────────────────────────────────────── */

const TUS = '/portal/uploads/tus';
const TUS_SESSION = /\/portal\/uploads\/tus\/.+/;
/** 끊기거나 멈춘 자리 — 224 KB 가운데 62% */
const SENT = Math.round(PICKED_SIZE * 0.62);

/** 세션을 만들고, 첫 조각은 62% 까지 받았다고 답한다. 그다음 조각의 결말만 스토리가 정한다 */
function 올리기(mock: MockAdapter, 다음조각: '멈춤' | '끊김' | '끝') {
  mock.onPost(TUS).reply(201, '', { location: `/api/v1${TUS}/story-upload` });
  if (다음조각 === '끝') {
    mock.onPatch(TUS_SESSION).reply(204, '', { 'upload-offset': String(PICKED_SIZE) });
    return;
  }
  mock.onPatch(TUS_SESSION).replyOnce(204, '', { 'upload-offset': String(SENT) });
  if (다음조각 === '멈춤') mock.onPatch(TUS_SESSION).reply(() => pending());
  else mock.onPatch(TUS_SESSION).networkError();
}

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
