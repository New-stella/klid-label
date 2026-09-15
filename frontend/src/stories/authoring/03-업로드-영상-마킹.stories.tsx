import { useLayoutEffect, type ReactNode } from 'react';
import type { Meta, StoryObj } from '@storybook/react-vite';
import { expect, userEvent, waitFor, within } from 'storybook/test';

import { formatMarkTimestamp } from '@/features/portal/uploads/markingPlan';
import type {
  MarkItem,
  PortalMarkingList,
  PortalMarkingSaveResult,
  PortalStreamUrl,
} from '@/features/portal/uploads/markingTypes';
import type { PortalUploadDetail } from '@/features/portal/uploads/types';

import { fail, ok, pending, 화면 } from './storyScreen';

/* 저작도구 · 내 업로드에서 들어오는 업로드 영상 마킹.
   상태 이름은 포털 스토리북(6008) 「워크스페이스 / 저작도구 / 내 업로드 · 업로드 영상 마킹」과 같다.
   기본이 「자동 방식 · 저장 전」이다 */
const meta: Meta = {
  title: '저작도구/내 업로드 · 업로드 영상 마킹',
};

export default meta;
type Story = StoryObj;

/* ── 견본 값 — 포털 그림 스토리의 목업(KLID_Portal src/mocks/authoring.ts)과 같다 ── */

/** 내 업로드의 그 자산 한 편 — 03:49 · 초당 30 프레임 */
const 자산번호 = 4;
const 주소 = `/portal/uploads/${자산번호}/marking`;
const 자산주소 = `/portal/uploads/${자산번호}`;

const FPS = 30;

/** 마킹 대기 중인 자산 */
const DETAIL: PortalUploadDetail = {
  uldSn: 자산번호,
  uldTypeCd: 'VIDEO',
  orgnlFileNm: '실종자추적_v0.7_20260910.mp4',
  fileSz: 32_925_286, // 31.4 MB
  mimeTypeNm: 'video/mp4',
  uldSttsCd: 'UPLOADED',
  frmeCnt: null,
  vdoLenSec: 229,
  fps: FPS,
  regDt: '2026-09-11T10:49:00',
  mdfcnDt: null,
  frames: [],
  expiresAt: '2026-09-18T10:49:00',
};

/** 재생 주소 — 스토리북이 내주는 견본 영상(03:49 · 30fps) */
const STREAM: PortalStreamUrl = {
  url: '/samples/sample-video.webm',
  expiresAt: 4102444800,
  ttlSeconds: 300,
};

/** 프레임 번호 → 지점 한 건 (표시 시각은 화면과 같은 규칙) */
const markAt = (frameIndex: number): MarkItem => ({
  frameIndex,
  timestamp: formatMarkTimestamp(frameIndex / FPS),
});

/** 자동 간격 300 프레임으로 뽑은 지점 — 03:49 영상이면 23건 */
const SAVED_MARKS: MarkItem[] = Array.from({ length: 23 }, (_, i) => markAt(i * 300));

/** 지점이 너무 많은 견본의 간격 — 10 프레임마다면 687건이라 목록 상한(600)을 넘는다 */
const MANY_INTERVAL = '10';

/** 수동으로 찍은 지점(프레임 번호) — 재생하다 Space 로 찍은 모습. 세 번째 지점을 골라 둔다 */
const MANUAL_FRAMES = [95, 610, 1488, 2230, 3705, 5120];

/** 저장 직후 일부만 쓰인 견본 — 요청한 23건 가운데 20건 */
const PARTIAL_USED = 20;

const saveResult = (marks: MarkItem[], requested: number): PortalMarkingSaveResult => ({
  markingSn: 9,
  uldSn: 자산번호,
  mode: 'AUTO',
  interval: 300,
  marks,
  markCount: marks.length,
  requestedMarkCount: requested,
  truncated: marks.length < requested,
  uldSttsCd: 'PROCESSING',
  regDt: '2026-09-15T10:00:00',
});

const markingList = (marks: MarkItem[] | null): PortalMarkingList => ({
  uldSn: 자산번호,
  markings: marks
    ? [{ markingSn: 9, mode: 'AUTO', interval: 300, marks, markCount: marks.length, regDt: '2026-09-15T10:00:00' }]
    : [],
});

/* ── 가짜 응답 ── */

interface 응답 {
  /** 자산 상세 — 바꿀 값만 준다. `pending` 이면 오지 않는다 */
  detail?: Partial<PortalUploadDetail> | 'pending';
  /** 재생 주소 — 기본은 견본 영상 */
  stream?: 'ok' | 'pending' | 'fail' | 'not-found';
  /** 이미 저장된 지점 */
  saved?: MarkItem[];
  /** 마킹 저장 — 저장에 성공하면 자산은 추출 중이 되고 저장된 지점이 조회된다 */
  save?: 'pending' | 'fail' | PortalMarkingSaveResult;
}

const 마킹화면 = ({ detail = {}, stream = 'ok', saved, save }: 응답 = {}) =>
  화면(주소, (mock) => {
    let 저장결과: PortalMarkingSaveResult | null = null;

    if (detail === 'pending') mock.onGet(자산주소).reply(() => pending());
    else
      mock.onGet(자산주소).reply(() =>
        ok({ ...DETAIL, ...detail, ...(저장결과 ? { uldSttsCd: 저장결과.uldSttsCd } : {}) }),
      );

    const streamPath = `${자산주소}/stream-url`;
    if (stream === 'pending') mock.onGet(streamPath).reply(() => pending());
    else if (stream === 'fail') mock.onGet(streamPath).reply(...fail(500));
    else if (stream === 'not-found') mock.onGet(streamPath).reply(...fail(404, null, 'NOT_FOUND'));
    else mock.onGet(streamPath).reply(...ok(STREAM));

    mock
      .onGet(`${자산주소}/markings`)
      .reply(() => ok(markingList(저장결과 ? 저장결과.marks : (saved ?? null))));

    if (save === 'pending') mock.onPost(`${자산주소}/markings`).reply(() => pending());
    // 실패 문구는 서버가 준다 — 서버의 내부 오류 응답 그대로(화면의 「마킹을 저장하지 못했습니다」는 서버 문구가 없을 때만 쓰인다)
    else if (save === 'fail')
      mock.onPost(`${자산주소}/markings`).reply(...fail(500, '서버 내부 오류가 발생했습니다.', 'INTERNAL_ERROR'));
    else if (save)
      mock.onPost(`${자산주소}/markings`).reply(() => {
        저장결과 = save;
        return [201, ok(save)[1]];
      });
  });

/**
 * 잠깐 뜨는 알림을 멈춰 세운다 — 원본 알림은 5초 뒤 저절로 닫혀, 포털 카드 안에서 열어 볼 즈음엔 사라진다.
 * 알림이 닫히는 그 타이머(5초) 하나만 막는다. 화면 코드는 건드리지 않는다 (포털 그림 스토리도 알림을 머물게 뒀다).
 */
function 알림고정({ children }: { children: ReactNode }) {
  useLayoutEffect(() => {
    const original = window.setTimeout;
    window.setTimeout = ((handler: TimerHandler, delay?: number, ...args: unknown[]) =>
      delay === 5000 ? 0 : original(handler, delay, ...args)) as typeof window.setTimeout;
    return () => {
      window.setTimeout = original;
    };
  }, []);
  return <>{children}</>;
}

/* ── 누르기 ── */

type PlayArgs = { canvasElement: HTMLElement };

/** 영상 길이를 받아 뽑힐 프레임 수가 선 뒤 — 같은 글이 화면에 두 번 서 있어 여럿으로 찾는다 */
const 상세받음 = async (canvasElement: HTMLElement) =>
  within(canvasElement).findAllByText('23장', {}, { timeout: 10000 });

/** 재생 요소가 영상을 읽어 들인 뒤 */
const 영상받음 = async (canvasElement: HTMLElement, readyState = 1) => {
  await waitFor(
    () => {
      const video = canvasElement.querySelector('video');
      expect(video?.readyState ?? 0).toBeGreaterThanOrEqual(readyState);
    },
    { timeout: 10000 },
  );
  return canvasElement.querySelector('video') as HTMLVideoElement;
};

const 수동으로 = async (canvasElement: HTMLElement) => {
  const canvas = within(canvasElement);
  await userEvent.click(await canvas.findByRole('radio', { name: '수동' }));
  await canvas.findByText('재생하며 원하는 순간을 직접 찍습니다.');
  풀기();
};

/** 누른 자리에 남는 초점 테두리를 걷는다 — 마우스로 누른 모습에 맞춘다 */
const 풀기 = () => (document.activeElement as HTMLElement | null)?.blur();

const 마킹완료 = async (canvasElement: HTMLElement) => {
  await 상세받음(canvasElement);
  await userEvent.click(await within(canvasElement).findByRole('button', { name: '마킹 완료' }));
  await within(document.body).findByRole('button', { name: '완료하고 추출 시작' });
};

const 완료하고추출 = async ({ canvasElement }: PlayArgs) => {
  await 마킹완료(canvasElement);
  await userEvent.click(within(document.body).getByRole('button', { name: '완료하고 추출 시작' }));
};

/* ── 상태 ── */

export const 기본: Story = { render: () => 마킹화면() };

export const 자동_지점이_너무_많음: Story = {
  name: '자동 · 지점이 너무 많음',
  render: () => 마킹화면(),
  play: async ({ canvasElement }) => {
    await 상세받음(canvasElement);
    const input = within(canvasElement).getByLabelText('간격(프레임)');
    await userEvent.clear(input);
    await userEvent.type(input, MANY_INTERVAL);
    풀기();
  },
};

export const 자동_영상_길이를_모름: Story = {
  name: '자동 · 영상 길이를 모름',
  render: () => 마킹화면({ detail: { vdoLenSec: null } }),
};

export const 수동_지점_없음: Story = {
  name: '수동 · 지점 없음',
  render: () => 마킹화면(),
  play: async ({ canvasElement }) => {
    await 상세받음(canvasElement);
    await 수동으로(canvasElement);
  },
};

export const 수동_지점_있음: Story = {
  name: '수동 · 지점 있음',
  render: () => 마킹화면(),
  play: async ({ canvasElement }) => {
    await 상세받음(canvasElement);
    const video = await 영상받음(canvasElement);
    await 수동으로(canvasElement);
    // 재생 위치를 옮기고 Space — 화면은 창에 걸린 키 입력으로 지점을 찍는다
    for (const frame of MANUAL_FRAMES) {
      video.currentTime = frame / FPS;
      window.dispatchEvent(new KeyboardEvent('keydown', { code: 'Space', key: ' ' }));
      await within(canvasElement).findByRole('button', { name: `F${frame} (${markAt(frame).timestamp})` });
    }
    // 세 번째 지점을 고른다 — 초점을 옮기지 않고 누른다(초점이 가면 포털 카드 안 틀이 그 자리로 굴러 내려간다)
    within(canvasElement)
      .getByRole('button', { name: `F${MANUAL_FRAMES[2]} (${markAt(MANUAL_FRAMES[2]).timestamp})` })
      .click();
  },
};

export const 영상_불러오는_중: Story = {
  name: '영상 불러오는 중',
  render: () => 마킹화면({ detail: 'pending', stream: 'pending' }),
};

export const 재생_끊겨_기다리는_중: Story = {
  name: '재생 끊겨 기다리는 중',
  render: () => 마킹화면(),
  play: async ({ canvasElement }) => {
    const video = await 영상받음(canvasElement, 3);
    // 받아 둔 데이터가 떨어져 재생이 멈춘 순간 — 재생 요소가 「기다리는 중」을 알린 모습
    await new Promise((resolve) => window.setTimeout(resolve, 300));
    video.dispatchEvent(new Event('waiting'));
  },
};

export const 영상을_재생할_수_없음: Story = {
  name: '영상을 재생할 수 없음',
  render: () => 마킹화면({ stream: 'fail' }),
};

export const 마킹할_수_없음: Story = {
  name: '마킹할 수 없음',
  // 사유는 네 가지(잘못된 주소 · 불러올 수 없음 · 아직 다 올라오지 않음 · 영상이 아님) — 틀은 같고 글만 갈려
  // 가장 자주 마주칠 「아직 다 올라오지 않음」을 견본으로 둔다(포털 그림과 같다)
  render: () => 마킹화면({ stream: 'not-found' }),
};

export const 마킹_완료_확인_창: Story = {
  name: '마킹 완료 확인 창',
  render: () => 마킹화면(),
  play: async ({ canvasElement }) => 마킹완료(canvasElement),
};

export const 저장_중: Story = {
  name: '저장 중',
  render: () => 마킹화면({ save: 'pending' }),
  play: 완료하고추출,
};

export const 저장_완료_알림: Story = {
  name: '저장 완료 알림',
  render: () => <알림고정>{마킹화면({ save: saveResult(SAVED_MARKS, SAVED_MARKS.length) })}</알림고정>,
  play: 완료하고추출,
};

export const 저장_직후_일부만_쓰임: Story = {
  name: '저장 직후 일부만 쓰임',
  render: () => (
    <알림고정>
      {마킹화면({ save: saveResult(SAVED_MARKS.slice(0, PARTIAL_USED), SAVED_MARKS.length) })}
    </알림고정>
  ),
  play: 완료하고추출,
};

export const 저장_실패_알림: Story = {
  name: '저장 실패 알림',
  render: () => <알림고정>{마킹화면({ save: 'fail' })}</알림고정>,
  play: 완료하고추출,
};

export const 잠김_추출_중: Story = {
  name: '잠김 · 추출 중',
  render: () => 마킹화면({ detail: { uldSttsCd: 'PROCESSING', expiresAt: null }, saved: SAVED_MARKS }),
};

export const 잠김_이미_저장함: Story = {
  name: '잠김 · 이미 저장함',
  render: () => 마킹화면({ detail: { uldSttsCd: 'READY', frmeCnt: 23 }, saved: SAVED_MARKS }),
};
