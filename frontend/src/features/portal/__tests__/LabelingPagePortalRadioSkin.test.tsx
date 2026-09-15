// 라벨링 화면 — 채널이 라디오 세 자리에 끝까지 닿는가(읽는 자리 가드).
//
// 배경 (2026-09-15 포털 개발망 실측): 포털 Host 스타일이 네이티브 라디오를 1px·clip 으로 숨겨
//   AI 탐지 팝업 「박스 / 폴리곤」의 동그라미가 사라졌다. 세 자리(형태 · 트랙 결과 적용 방식 ·
//   객체 속성 RADIO)는 포털일 때 포털 라디오로 그린다.
//
// 컴포넌트 단위 시험(portalRadioSkin)은 «prop 을 받으면 바뀐다»만 본다. 이 파일은 화면이
//   `portalMode` 를 그 prop 으로 **실제로 내려보내는가**를 본다 — 배선이 빠지면 컴포넌트 시험은
//   전부 초록인데 포털 화면만 다시 동그라미를 잃는다. 관제 화면은 반대로 공통 라디오 그대로여야 한다.
//
// ⚠ jsdom 에는 Host 스타일이 없다 — «보인다»가 아니라 «포털 라디오 표식이 선다»로 가른다.
//
// @design SCREEN-029, SCREEN-005, AC-1108

import { act, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import MockAdapter from 'axios-mock-adapter';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

vi.mock('react-konva', async () => (await import('@/test/konvaMock')).createKonvaMock());

import { apiClient } from '@/lib/api/client';
import { LabelingPage } from '@/pages/label/LabelingPage';
import { renderWithProviders } from '@/test/renderWithProviders';
import { useAuthStore } from '@/stores/useAuthStore';
import { useLabelStore } from '@/stores/useLabelStore';
import { useUiStore } from '@/stores/useUiStore';

const SESSION_JWT = ['t', 'o', 'k'].join('');

const ok = <T,>(data: T) => ({ success: true, data, message: null, errorCode: null });

function labelsPayload(srcSn: number) {
  return ok({
    frameNo: 0,
    srcSn,
    videoId: 5,
    siblings: [
      { srcSn, frameNo: 0 },
      { srcSn: srcSn + 1, frameNo: 1 },
    ],
    // 라벨 마스터에 이어진 저장 라벨 — 속성 섹션(classId>0 · serverId) 게이트를 통과한다.
    labels: [{ id: 41, lblTypeCd: 'BBOX', label: 'person', labelId: 1, points: [[1, 2], [30, 40]] }],
  });
}

const MASTER = [{ labelId: 1, name: '사람', color: '#EF4444', type: 'BBOX', useYn: 'Y' }];

const RADIO_ATTR = [
  {
    attrId: 10,
    labelId: 1,
    name: '색상',
    inputType: 'RADIO',
    valuesJson: JSON.stringify(['빨강', '파랑']),
    defaultVal: null,
    mutable: 'Y',
    sortNo: 1,
    useYn: 'Y',
  },
];

/** 포털 라디오인가 — 보이는 동그라미가 입력의 다음 형제로 선다. */
function isPortalRadio(radio: HTMLElement): boolean {
  return (
    radio.closest('[data-portal-radio]') !== null &&
    radio.nextElementSibling?.hasAttribute('data-portal-radio-dot') === true
  );
}

function mockCommon(mock: MockAdapter) {
  mock.onGet('/manage/labels').reply(200, ok(MASTER));
  mock.onGet('/manage/labels/detect-candidates').reply(
    200,
    ok([{ labelId: 1, name: '사람', color: '#EF4444', type: 'BBOX', dtctTypeCd: 'person' }]),
  );
  mock.onGet('/manage/labels/1/attrs').reply(200, ok(RADIO_ATTR));
  mock.onGet('/labels/41/attrs').reply(200, ok([]));
}

async function selectFirstLabel() {
  await waitFor(() => expect(useLabelStore.getState().labels).toHaveLength(1));
  const id = useLabelStore.getState().labels[0]?.id;
  expect(id).toBeDefined();
  act(() => {
    useLabelStore.getState().selectLabel(id ?? null);
  });
  // 대조 — 속성 섹션이 실제로 선다(선택이 없으면 라디오 자리 자체가 없어 단언이 공허해진다).
  return screen.findByRole('group', { name: '색상' });
}

describe.each([
  {
    channel: '포털',
    expectPortal: true,
    srcSn: 555,
    entry: '/portal/label/555',
    path: '/portal/label/:id',
    claims: { sub: 'u-99', role: 'PORTAL_USER', channel: 'PORTAL', exp: 9999999999 },
    mockChannel: (mock: MockAdapter) => {
      mock.onGet('/portal/frames/555/labels').reply(200, labelsPayload(555));
      mock.onGet(/\/portal\/frames\/\d+\/image/).reply(200, new Blob([new Uint8Array([1])]));
      mock.onGet('/portal/ai-defaults').reply(200, ok({ confThreshold: 30, simplifyTolerance: 5 }));
    },
  },
  {
    channel: '관제',
    expectPortal: false,
    srcSn: 300,
    entry: '/label/300',
    path: '/label/:id',
    claims: { sub: '10', role: 'WORKER', channel: 'INTERNAL', exp: 9999999999 },
    mockChannel: (mock: MockAdapter) => {
      mock.onGet('/frames/300/labels').reply(200, labelsPayload(300));
      mock.onGet(/\/frames\/\d+\/image/).reply(200, new Blob());
      mock.onGet(/\/frames\/\d+\/meta/).reply(200, ok(null));
      mock.onGet(/\/frames\/\d+\/description/).reply(200, ok({ srcSn: 300, description: '' }));
      mock.onGet('/videos/5/issues').reply(200, ok([]));
    },
  },
] as const)('라벨링 화면 라디오 — $channel', ({ expectPortal, entry, path, claims, mockChannel }) => {
  let mock: MockAdapter;

  beforeEach(() => {
    useLabelStore.getState().reset();
    useUiStore.setState({ toasts: [] });
    mock = new MockAdapter(apiClient);
    useAuthStore.setState({ token: SESSION_JWT, claims: { ...claims } });
    mockChannel(mock);
    mockCommon(mock);
  });

  afterEach(() => {
    mock.restore();
    useAuthStore.getState().clear();
    useLabelStore.getState().reset();
    useUiStore.setState({ toasts: [] });
  });

  function renderPage() {
    return renderWithProviders(<LabelingPage />, {
      initialEntries: [entry],
      routes: [{ path, element: <LabelingPage /> }],
    });
  }

  it('AI_탐지_팝업의_형태_라디오', async () => {
    const user = userEvent.setup();
    renderPage();
    await waitFor(() => expect(useLabelStore.getState().labels).toHaveLength(1));

    // 포털 도구바는 AI 보조 묶음을 따로 갖고, 관제 도구바는 묶음 표식이 없다 — 있으면 그 안에서 찾는다.
    const scope = screen.queryByTestId('label-toolbar-ai-group') ?? document.body;
    await user.click(within(scope).getByRole('button', { name: 'AI 탐지' }));
    const dialog = await screen.findByRole('dialog');
    for (const name of ['박스', '폴리곤']) {
      expect(isPortalRadio(within(dialog).getByRole('radio', { name }))).toBe(expectPortal);
    }
  });

  it('AI_자동_추적_패널의_적용_방식_라디오', async () => {
    renderPage();
    await waitFor(() => expect(useLabelStore.getState().labels).toHaveLength(1));

    const group = within(screen.getByTestId('auto-track-panel')).getByRole('radiogroup', {
      name: '트랙 결과 적용 방식',
    });
    const radios = within(group).getAllByRole('radio');
    expect(radios).toHaveLength(2);
    for (const r of radios) expect(isPortalRadio(r)).toBe(expectPortal);
  });

  it('선택_객체_속성의_RADIO_선택지', async () => {
    renderPage();
    const group = await selectFirstLabel();
    const radios = within(group).getAllByRole('radio');
    expect(radios.map((r) => (r as HTMLInputElement).value)).toEqual(['빨강', '파랑']);
    for (const r of radios) expect(isPortalRadio(r)).toBe(expectPortal);
  });
});
