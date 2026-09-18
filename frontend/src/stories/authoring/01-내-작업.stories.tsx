import type { Meta, StoryObj } from '@storybook/react-vite';
import { userEvent, within } from 'storybook/test';

import type { PortalUserWork } from '@/features/portal/api';
import { WORKS, 내작업목록 } from '@/demo/screens/works';

import { fail, pending, 화면 } from './storyScreen';

/* 저작도구 · 내 작업. 상태 이름은 포털 스토리북(6008) 「워크스페이스 / 저작도구 / 내 작업」과 같다.
   견본 값은 시연판과 같이 쓴다(`demo/screens/works`) */
const meta: Meta = {
  title: '저작도구/내 작업',
};

export default meta;
type Story = StoryObj;

const 주소 = '/portal';

const 목록 = (works: PortalUserWork[], total = works.length) =>
  화면(주소, (mock) => {
    내작업목록(mock, works, total);
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
      내작업목록(mock, WORKS);
      mock.onGet(/\/portal\/datamart\/videos\/\d+\/download/).reply(...fail(500));
    }),
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    const [first] = await canvas.findAllByRole('button', { name: '내려받기' });
    await userEvent.click(first);
  },
};
