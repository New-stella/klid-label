import type { Meta, StoryObj } from '@storybook/react-vite';
import { userEvent, within } from 'storybook/test';

import type { PortalUserWork } from '@/features/portal/api';

import { fail, ok, pageOf, pending, 화면 } from './storyScreen';

/* 저작도구 · 내 작업. 상태 이름은 포털 스토리북(6008) 「워크스페이스 / 저작도구 / 내 작업」과 같다 */
const meta: Meta = {
  title: '저작도구/내 작업',
};

export default meta;
type Story = StoryObj;

const 주소 = '/portal';

/** 세 줄 — 데이터마트 영상(저장함) · 내 업로드(아직 저장 전) · 이어서 작업할 수 없는 업로드 */
const WORKS: PortalUserWork[] = [
  {
    rawSn: 1821,
    assetSource: 'DATAMART',
    videoName: '교차로_야간_침수_CCTV-107_20260902.mp4',
    labelCount: 3,
    lastSavedAt: '2026-09-12T16:40:00',
    entrySrcSn: 5001,
    expiresOn: '2026-09-19',
  },
  {
    rawSn: 4,
    assetSource: 'PORTAL_UPLOAD',
    videoName: '실종자추적_v0.7_20260910.mp4',
    labelCount: 0,
    lastSavedAt: null,
    entrySrcSn: 41,
    expiresOn: '2026-09-18',
  },
  {
    rawSn: 3,
    assetSource: 'PORTAL_UPLOAD',
    videoName: '침수도로_야간_v0.3_20260908.mp4',
    labelCount: 0,
    lastSavedAt: null,
    entrySrcSn: null,
    expiresOn: null,
  },
];

const 목록 = (works: PortalUserWork[], total = works.length) =>
  화면(주소, (mock) => {
    mock.onGet('/portal/user-works').reply(200, ok(pageOf(works, total))[1]);
    mock.onGet(/\/portal\/datamart\/videos\/\d+\/download/).reply(() => pending());
  });

export const 기본: Story = { render: () => 목록(WORKS) };

export const 빈_목록: Story = { name: '빈 목록', render: () => 목록([]) };

export const 목록_불러오는_중: Story = {
  name: '목록 불러오는 중',
  render: () => 화면(주소, (mock) => mock.onGet('/portal/user-works').reply(() => pending())),
};

export const 여러_쪽: Story = { name: '여러 쪽', render: () => 목록(WORKS, 47) };

/* 받기 — 첫 줄 「내려받기」를 누른 뒤의 모습 */
export const 내려받는_중: Story = {
  name: '내려받는 중',
  render: () => 목록(WORKS),
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    const [first] = await canvas.findAllByRole('button', { name: '내려받기' });
    await userEvent.click(first);
  },
};

export const 내려받기_실패: Story = {
  name: '내려받기 실패',
  render: () =>
    화면(주소, (mock) => {
      mock.onGet('/portal/user-works').reply(200, ok(pageOf(WORKS))[1]);
      mock.onGet(/\/portal\/datamart\/videos\/\d+\/download/).reply(...fail(500));
    }),
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    const [first] = await canvas.findAllByRole('button', { name: '내려받기' });
    await userEvent.click(first);
  },
};
