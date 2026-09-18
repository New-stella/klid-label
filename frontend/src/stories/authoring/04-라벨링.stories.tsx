import type { Meta, StoryObj } from '@storybook/react-vite';
import { userEvent, waitFor, within } from 'storybook/test';

import {
  DETAIL,
  그림_응답,
  그림_주소,
  라벨링응답,
  라벨_주소,
  번호,
  속성값_주소,
  자산번호,
  자산_주소,
  첫_프레임,
} from '@/demo/screens/labeling';
import { buildPortalUploadLabelPath } from '@/features/portal/labelingEntry';

import { type ApiMocks, fail, ok, pending, 화면 } from './storyScreen';

/* 저작도구 · 내 업로드 · 라벨링. 상태 이름은 포털 스토리북(6008) 「워크스페이스 / 저작도구 / 내 업로드 · 라벨링」과 같다.
   견본 값(파일명 · 프레임 수 · 객체 · 라벨 · 속성)은 포털 목업(KLID_Portal `mocks/authoring.ts`)과 같고, 프레임 번호 · 메타 항목은 실제 서버가 올린 영상에 보내는 모양을 따른다 */
const meta: Meta = {
  title: '저작도구/내 업로드 · 라벨링',
};

export default meta;
type Story = StoryObj;

/* 견본 값은 시연판과 같이 쓴다(`demo/screens/labeling`) — 여기서는 주소와 상태만 정한다 */
const 주소 = buildPortalUploadLabelPath(자산번호);

/**
 * 편집기가 부르는 응답 전부를 건 뒤 `바꿈` 을 건다 — 같은 주소 모양으로 건 응답이 기본 응답을 대신한다.
 * 저장한 라벨은 스토리 안에서 기억해 다시 불러올 때 돌려준다.
 */
function 편집기(바꿈?: ApiMocks) {
  return 화면(주소, (mock) => {
    라벨링응답(mock);
    바꿈?.(mock);
  });
}

/* ── 누르기 도우미 ─────────────────────────────────────────────────────────── */

/** 편집기가 선 뒤 — 객체 목록이 뜨고 캔버스 · 프레임 그림까지 다 받은 뒤 */
async function 편집기_뜸(canvasElement: HTMLElement) {
  const canvas = within(canvasElement);
  await canvas.findByRole('button', { name: '사람 #1' }, { timeout: 15000 });
  await waitFor(
    () => {
      if (canvas.queryByText('캔버스를 불러오는 중') || canvas.queryByText('프레임 이미지를 불러오는 중')) {
        throw new Error('캔버스를 아직 받는 중');
      }
    },
    { timeout: 15000 },
  );
  return canvas;
}

/** 누른 뒤 남는 포커스 테두리를 걷는다 — 마우스로 누른 모습에 맞춘다 */
function 포커스_풀기() {
  (document.activeElement as HTMLElement | null)?.blur();
}

/** 속성 칸을 굴려 `el` 이 칸 위쪽에 보이게 한다(바깥 문서는 굴리지 않는다) */
function 칸에서_보이게(el: HTMLElement) {
  for (let p = el.parentElement; p; p = p.parentElement) {
    if (p.scrollHeight > p.clientHeight && getComputedStyle(p).overflowY !== 'visible') {
      p.scrollTop += el.getBoundingClientRect().top - p.getBoundingClientRect().top;
      return;
    }
  }
}

/** 객체 두 개의 X 좌표를 고친다 — 사람 #2 를 먼저, 사람 #1 을 나중에(사람 #1 이 골라진 채 남는다).
    좌표 칸에 적느라 내려간 속성 칸은 객체 목록이 보이게 되돌린다 */
async function 두_객체_고침(canvasElement: HTMLElement) {
  const canvas = await 편집기_뜸(canvasElement);
  for (const [name, x] of [
    ['사람 #2', '250'],
    ['사람 #1', '690'],
  ] as const) {
    await userEvent.click(canvas.getByRole('button', { name }));
    const input = await canvas.findByRole('textbox', { name: 'X 좌표' });
    await userEvent.clear(input);
    await userEvent.type(input, x);
  }
  포커스_풀기();
  칸에서_보이게(canvas.getByText('객체 목록'));
  return canvas;
}

/* ── 상태 ──────────────────────────────────────────────────────────────────── */

export const 기본: Story = { render: () => 편집기() };

export const 단축키_도움말: Story = {
  name: '단축키 도움말',
  render: () => 편집기(),
  play: async ({ canvasElement }) => {
    const canvas = await 편집기_뜸(canvasElement);
    await userEvent.click(canvas.getByRole('button', { name: /단축키 안내/ }));
  },
};

export const 라벨_불러오는_중: Story = {
  name: '라벨 불러오는 중',
  render: () => 편집기((mock) => mock.onGet(라벨_주소).reply(() => pending())),
};

/* 사유는 다섯(잘못된 주소 · 못 불러옴 · 처리 실패 · 준비 중 · 프레임 없음) — 틀은 같고 글만 갈려 준비 중을 견본으로 둔다 */
export const 자산_안내: Story = {
  name: '자산 안내',
  render: () =>
    편집기((mock) =>
      mock.onGet(자산_주소).reply(...ok({ ...DETAIL, uldSttsCd: 'PROCESSING', frames: [] })),
    ),
};

export const 접근할_수_없는_영상: Story = {
  name: '접근할 수 없는 영상',
  render: () => 편집기((mock) => mock.onGet(라벨_주소).reply(...fail(403, '권한이 없습니다.', 'FORBIDDEN'))),
};

export const 라벨_조회_실패: Story = {
  name: '라벨 조회 실패',
  render: () => 편집기((mock) => mock.onGet(라벨_주소).reply(...fail(500))),
};

export const 객체_없음: Story = {
  name: '객체 없음',
  render: () => 편집기((mock) => mock.onGet(라벨_주소).reply(...ok([]))),
};

export const 객체_고름: Story = {
  name: '객체 고름',
  render: () => 편집기(),
  play: async ({ canvasElement }) => {
    const canvas = await 편집기_뜸(canvasElement);
    await userEvent.click(canvas.getByRole('button', { name: '사람 #1' }));
    포커스_풀기();
  },
};

export const 저장_안_한_변경_있음: Story = {
  name: '저장 안 한 변경 있음',
  render: () => 편집기(),
  play: async ({ canvasElement }) => {
    await 두_객체_고침(canvasElement);
  },
};

/* 저장된 객체의 라벨을 고르면 속성 값 칸이 서고, 그 저장값을 못 불러온 모습 */
export const 속성값_못_불러옴: Story = {
  name: '속성값 못 불러옴',
  render: () => 편집기((mock) => mock.onGet(속성값_주소).reply(...fail(500))),
  play: async ({ canvasElement }) => {
    const canvas = await 편집기_뜸(canvasElement);
    await userEvent.click(canvas.getByRole('button', { name: '사람 #1' }));
    await userEvent.click(await canvas.findByRole('combobox', { name: '라벨 선택' }));
    await userEvent.click(await canvas.findByRole('option', { name: '사람 (#1)' }));
    포커스_풀기();
    칸에서_보이게(await canvas.findByText('속성 값'));
  },
};

export const 저장_중: Story = {
  name: '저장 중',
  render: () => 편집기((mock) => mock.onPut(라벨_주소).reply(() => pending())),
  play: async ({ canvasElement }) => {
    const canvas = await 두_객체_고침(canvasElement);
    await userEvent.click(canvas.getByTestId('label-toolbar-save'));
  },
};

export const 저장됨_알림: Story = {
  name: '저장됨 알림',
  render: () => 편집기(),
  play: async ({ canvasElement }) => {
    const canvas = await 편집기_뜸(canvasElement);
    await userEvent.click(canvas.getByTestId('label-toolbar-save'));
    포커스_풀기();
  },
};

export const 저장_실패_알림: Story = {
  name: '저장 실패 알림',
  render: () => 편집기((mock) => mock.onPut(라벨_주소).reply(...fail(500, '서버 내부 오류가 발생했습니다.'))),
  play: async ({ canvasElement }) => {
    const canvas = await 편집기_뜸(canvasElement);
    await userEvent.click(canvas.getByTestId('label-toolbar-save'));
    포커스_풀기();
  },
};

export const 프레임_이미지_불러오는_중: Story = {
  name: '프레임 이미지 불러오는 중',
  render: () =>
    편집기((mock) =>
      mock.onGet(그림_주소).reply((config) =>
        번호(config.url, 그림_주소) === 첫_프레임 ? pending() : 그림_응답(config.url),
      ),
    ),
};

export const 프레임_이미지_불러오기_실패: Story = {
  name: '프레임 이미지 불러오기 실패',
  render: () =>
    편집기((mock) =>
      mock.onGet(그림_주소).reply((config) =>
        번호(config.url, 그림_주소) === 첫_프레임 ? fail(404, null, 'NOT_FOUND') : 그림_응답(config.url),
      ),
    ),
};

export const 회전_보기: Story = {
  name: '회전 보기',
  render: () => 편집기(),
  play: async ({ canvasElement }) => {
    const canvas = await 편집기_뜸(canvasElement);
    await userEvent.click(canvas.getByRole('button', { name: /우 90° 회전/ }));
    포커스_풀기();
  },
};

export const 영역_확대: Story = {
  name: '영역 확대',
  render: () => 편집기(),
  play: async ({ canvasElement }) => {
    const canvas = await 편집기_뜸(canvasElement);
    await userEvent.click(canvas.getByRole('button', { name: /영역 확대/ }));
    포커스_풀기();
  },
};

export const 닫기_전_저장_확인_창: Story = {
  name: '닫기 전 저장 확인 창',
  render: () => 편집기(),
  play: async ({ canvasElement }) => {
    const canvas = await 두_객체_고침(canvasElement);
    await userEvent.click(canvas.getByRole('button', { name: '뒤로가기' }));
  },
};

export const 프레임_이동_전_저장_확인_창: Story = {
  name: '프레임 이동 전 저장 확인 창',
  render: () => 편집기(),
  play: async ({ canvasElement }) => {
    const canvas = await 두_객체_고침(canvasElement);
    await userEvent.click(canvas.getByRole('button', { name: '다음 프레임' }));
  },
};

export const 라벨_선택_창: Story = {
  name: '라벨 선택 창',
  render: () => 편집기(),
  play: async ({ canvasElement }) => {
    const canvas = await 편집기_뜸(canvasElement);
    await userEvent.click(canvas.getByRole('radio', { name: /바운딩 박스/ }));
  },
};

export const 저장_충돌_안내_창: Story = {
  name: '저장 충돌 안내 창',
  render: () =>
    편집기((mock) =>
      mock.onPut(라벨_주소).reply(...fail(409, '다른 사용자가 먼저 저장했습니다.', 'CONFLICT')),
    ),
  play: async ({ canvasElement }) => {
    const canvas = await 두_객체_고침(canvasElement);
    await userEvent.click(canvas.getByTestId('label-toolbar-save'));
  },
};

export const 메타_탭: Story = {
  name: '메타 탭',
  render: () => 편집기(),
  play: async ({ canvasElement }) => {
    const canvas = await 편집기_뜸(canvasElement);
    await userEvent.click(canvas.getByRole('tab', { name: '메타' }));
    포커스_풀기();
  },
};

export const 메타_탭_후보_카드: Story = {
  name: '메타 탭 · 후보 카드',
  render: () => 편집기(),
  play: async ({ canvasElement }) => {
    const canvas = await 편집기_뜸(canvasElement);
    await userEvent.click(canvas.getByRole('tab', { name: '메타' }));
    await userEvent.click(await canvas.findByTestId('portal-ea-add-caption'));
    await userEvent.click(canvas.getByTestId('portal-ea-add-evidence'));
    포커스_풀기();
    칸에서_보이게(canvas.getByText('캡션 후보'));
  },
};
