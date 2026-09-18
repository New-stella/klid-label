import { useLayoutEffect, type ReactNode } from 'react';
import type { Meta, StoryObj } from '@storybook/react-vite';
import { expect, userEvent, waitFor, within } from 'storybook/test';

import {
  FPS,
  MANUAL_FRAMES,
  MANY_INTERVAL,
  PARTIAL_USED,
  SAVED_MARKS,
  markAt,
  saveResult,
  마킹응답,
  type 마킹응답옵션,
  자산번호,
} from '@/demo/screens/marking';

import { 화면 } from './storyScreen';

/* 저작도구 · 내 업로드에서 들어오는 업로드 영상 마킹.
   상태 이름은 포털 스토리북(6008) 「워크스페이스 / 저작도구 / 내 업로드 · 업로드 영상 마킹」과 같다.
   기본이 「자동 방식 · 저장 전」이다 */
const meta: Meta = {
  title: '저작도구/내 업로드 · 업로드 영상 마킹',
};

export default meta;
type Story = StoryObj;

/* 견본 값은 시연판과 같이 쓴다(`demo/screens/marking`) — 여기서는 주소와 상태만 정한다 */
const 주소 = `/portal/uploads/${자산번호}/marking`;

const 마킹화면 = (옵션: 마킹응답옵션 = {}) => 화면(주소, (mock) => 마킹응답(mock, 옵션));

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
