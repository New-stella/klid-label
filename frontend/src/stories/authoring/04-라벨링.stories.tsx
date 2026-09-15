import type { Meta, StoryObj } from '@storybook/react-vite';
import type MockAdapter from 'axios-mock-adapter';
import { userEvent, waitFor, within } from 'storybook/test';

import type { LabelAttrDef, LabelAttrValue } from '@/features/label/api/labelAttr';
import type { LabelMaster } from '@/features/label/api/labelMaster';
import { buildPortalUploadLabelPath } from '@/features/portal/labelingEntry';
import type { UploadFrameLabel, UploadLabelRequest } from '@/features/portal/uploads/api';
import type { PortalUploadDetail, PortalUploadFrame } from '@/features/portal/uploads/types';
import type { PortalEventAnnotation, PortalFrameMeta, PortalMetaItem } from '@/features/portal/work/types';

import { type ApiMocks, fail, ok, pending, 화면 } from './storyScreen';

/* 저작도구 · 내 업로드 · 라벨링. 상태 이름은 포털 스토리북(6008) 「워크스페이스 / 저작도구 / 내 업로드 · 라벨링」과 같다.
   견본 값(파일명 · 프레임 수 · 객체 · 라벨 · 속성)은 포털 목업(KLID_Portal `mocks/authoring.ts`)과 같고, 프레임 번호 · 메타 항목은 실제 서버가 올린 영상에 보내는 모양을 따른다 */
const meta: Meta = {
  title: '저작도구/내 업로드 · 라벨링',
};

export default meta;
type Story = StoryObj;

/* ── 견본 자산 ─────────────────────────────────────────────────────────────── */

/** 내 업로드 한 건 — 「내 작업」 스토리의 둘째 줄과 같은 자산 */
const 자산번호 = 4;
const 파일명 = '실종자추적_v0.7_20260910.mp4';
const 주소 = buildPortalUploadLabelPath(자산번호);

/** 첫 프레임 번호 — 「내 작업」 스토리의 이어서 작업 진입 프레임과 같다 */
const 첫_프레임 = 41;

/** 마킹으로 뽑힌 프레임 23장. 프레임 번호는 실제 서버처럼 뽑힌 차례(0, 1, 2 …)다 — 영상 속 프레임 위치가 아니다 */
const FRAMES: PortalUploadFrame[] = Array.from({ length: 23 }, (_, i) => ({
  uldFrmeSn: 첫_프레임 + i,
  uldSn: 자산번호,
  frmeNo: i,
  regDt: '2026-09-11T10:52:00',
}));

const DETAIL: PortalUploadDetail = {
  uldSn: 자산번호,
  uldTypeCd: 'VIDEO',
  orgnlFileNm: 파일명,
  fileSz: 32925286,
  mimeTypeNm: 'video/mp4',
  uldSttsCd: 'READY',
  frmeCnt: FRAMES.length,
  vdoLenSec: 229,
  fps: 30,
  regDt: '2026-09-11T10:49:00',
  mdfcnDt: '2026-09-11T10:52:00',
  frames: FRAMES,
  expiresAt: '2026-09-18T10:49:00',
};

/** 첫 프레임의 객체 — 사람 둘 · 차량 하나. 좌표는 견본 그림(1200×750) 픽셀이다(포털 목업의 백분율 좌표를 옮김) */
const OBJECTS: UploadFrameLabel[] = [
  {
    uldLblSn: 1,
    uldFrmeSn: 첫_프레임,
    lblTypeCd: 'BBOX',
    label: '사람',
    points: [
      [696, 225],
      [792, 465],
    ],
    regDt: '2026-09-11T11:02:00',
    mdfcnDt: null,
  },
  {
    uldLblSn: 2,
    uldFrmeSn: 첫_프레임,
    lblTypeCd: 'BBOX',
    label: '사람',
    points: [
      [240, 270],
      [324, 480],
    ],
    regDt: '2026-09-11T11:02:00',
    mdfcnDt: null,
  },
  {
    uldLblSn: 3,
    uldFrmeSn: 첫_프레임,
    lblTypeCd: 'POLYGON',
    label: '차량',
    points: [
      [408, 330],
      [624, 300],
      [672, 435],
      [576, 555],
      [384, 540],
    ],
    regDt: '2026-09-11T11:02:00',
    mdfcnDt: null,
  },
];

/** 라벨 마스터 — 포털 목업과 같은 다섯. 색은 포털 목업이 빌린 색 토큰을 그 자리에서 읽어 넣는다(값을 적어 두지 않는다) */
const LABELS = [
  { labelId: 1, name: '사람', token: '--klid-color-event-abduction' },
  { labelId: 2, name: '차량', token: '--klid-color-event-traffic' },
  { labelId: 3, name: '자전거', token: '--klid-color-event-flood' },
  { labelId: 4, name: '오토바이', token: '--klid-color-event-fire' },
  { labelId: 5, name: '동물', token: '--klid-color-event-landslide' },
] as const;

function labelMasters(): LabelMaster[] {
  const css = getComputedStyle(document.documentElement);
  return LABELS.map((l, i) => ({
    labelId: l.labelId,
    name: l.name,
    color: css.getPropertyValue(l.token).trim(),
    type: 'BBOX',
    sortNo: i + 1,
    useYn: 'Y',
    dtctTypeCd: null,
  }));
}

/** 라벨에 연결된 속성 — 넷의 입력 모양(드롭다운 · 라디오 · 체크박스 · 입력칸)을 한 벌씩 */
const attrDefs = (labelId: number): LabelAttrDef[] => [
  { attrId: 1, labelId, name: '가림 정도', inputType: 'SELECT', valuesJson: '["없음","일부","대부분"]', defaultVal: null, mutable: 'Y', sortNo: 1, useYn: 'Y' },
  { attrId: 2, labelId, name: '방향', inputType: 'RADIO', valuesJson: '["정면","측면","후면"]', defaultVal: null, mutable: 'Y', sortNo: 2, useYn: 'Y' },
  { attrId: 3, labelId, name: '소지품', inputType: 'CHECKBOX', valuesJson: '["우산","가방","모자"]', defaultVal: null, mutable: 'Y', sortNo: 3, useYn: 'Y' },
  { attrId: 4, labelId, name: '메모', inputType: 'TEXT', valuesJson: null, defaultVal: null, mutable: 'Y', sortNo: 4, useYn: 'Y' },
];

const ATTR_VALUES: LabelAttrValue[] = [
  { attrId: 1, name: '가림 정도', inputType: 'SELECT', value: '일부' },
  { attrId: 2, name: '방향', inputType: 'RADIO', value: '측면' },
  { attrId: 3, name: '소지품', inputType: 'CHECKBOX', value: '["우산"]' },
  { attrId: 4, name: '메모', inputType: 'TEXT', value: '' },
];

const metaItem = (metaKey: string, scope: PortalMetaItem['scope'], metaVl: string | null = null): PortalMetaItem => ({
  metaKey,
  metaVl,
  scope,
  overridden: false,
  source: metaVl === null ? 'NONE' : 'STORED',
});

/** 메타 — 적는 칸은 모두 비어 있다. 참고 정보 · 영상 기술 정보는 실제 서버가 올린 영상에 보내는 항목 그대로 */
const frameMeta = (srcSn: number): PortalFrameMeta => ({
  rawSn: 자산번호,
  srcSn,
  items: [
    metaItem('env.weather', 'video'),
    metaItem('env.timeOfDay', 'video'),
    metaItem('env.season', 'video'),
    metaItem('privacy.anonymity', 'video'),
    metaItem('privacy.pseudonymity', 'video'),
    metaItem('privacy.privacyIncluded', 'video'),
    metaItem('frame.description', 'frame'),
    metaItem('privacy.anonymity', 'frame'),
    metaItem('privacy.pseudonymity', 'frame'),
    metaItem('privacy.privacyIncluded', 'frame'),
  ],
  // 내가 올린 영상의 참고 정보는 실제 서버가 업로드 상태 한 줄만 보낸다 (위치 · CCTV 정보는 데이터마트 영상에만 있다)
  readOnlyMeta: [metaItem('portal.upload_status', 'video', 'READY')],
  technicalMeta: [
    metaItem('video.filesize', 'video', String(DETAIL.fileSz)),
    metaItem('video.fps', 'video', String(DETAIL.fps)),
    metaItem('video.mime', 'video', DETAIL.mimeTypeNm),
    metaItem('video.original_filename', 'video', 파일명),
  ],
});

const EVENT_ANNOTATION: PortalEventAnnotation = { rawSn: 자산번호, annotation: null, overridden: false };

/* ── 가짜 응답 ─────────────────────────────────────────────────────────────── */

/* 주소 모양 — 상태마다 같은 모양으로 다시 걸면 기본 응답을 갈아 끼운다(가짜 응답 도구의 규칙) */
const 자산_주소 = `/portal/uploads/${자산번호}`;
const 라벨_주소 = /^\/portal\/uploads\/frames\/(\d+)\/labels$/;
const 그림_주소 = /^\/portal\/uploads\/frames\/(\d+)\/image$/;
const 속성값_주소 = /^\/labels\/(\d+)\/attrs$/;

const 번호 = (url: string | undefined, pattern: RegExp) => Number(pattern.exec(url ?? '')?.[1]);

/** 견본 그림 — 한 번 받아 두고 프레임마다 돌려 쓴다(dummy-1 ~ dummy-6) */
const 그림_캐시 = new Map<number, Promise<Blob>>();
async function 그림_응답(url: string | undefined): Promise<[number, Blob]> {
  const n = ((((번호(url, 그림_주소) - 첫_프레임) % 6) + 6) % 6) + 1;
  let blob = 그림_캐시.get(n);
  if (!blob) {
    blob = fetch(`/samples/dummy-${n}.jpg`).then((r) => r.blob());
    그림_캐시.set(n, blob);
  }
  return [200, await blob];
}

/**
 * 편집기가 부르는 응답 전부를 건 뒤 `바꿈` 을 건다 — 같은 주소 모양으로 건 응답이 기본 응답을 대신한다.
 * 저장한 라벨은 스토리 안에서 기억해 다시 불러올 때 돌려준다.
 */
function 편집기(바꿈?: ApiMocks) {
  return 화면(주소, (mock: MockAdapter) => {
    const saved = new Map<number, UploadFrameLabel[]>([[첫_프레임, OBJECTS]]);
    mock.onGet(자산_주소).reply(...ok(DETAIL));
    mock.onGet(라벨_주소).reply((config) => ok(saved.get(번호(config.url, 라벨_주소)) ?? []));
    mock.onPut(라벨_주소).reply((config) => {
      const sn = 번호(config.url, 라벨_주소);
      const body = JSON.parse(String(config.data)) as UploadLabelRequest[];
      const rows = body.map((r, i) => ({
        uldLblSn: 100 + i,
        uldFrmeSn: sn,
        ...r,
        regDt: '2026-09-15T10:00:00',
        mdfcnDt: null,
      }));
      saved.set(sn, rows);
      return ok(rows);
    });
    mock.onGet(그림_주소).reply((config) => 그림_응답(config.url));
    mock.onGet('/manage/labels').reply(() => ok(labelMasters()));
    mock.onGet(/^\/manage\/labels\/(\d+)\/attrs$/).reply((config) =>
      ok(attrDefs(번호(config.url, /labels\/(\d+)\/attrs/))),
    );
    mock.onGet(속성값_주소).reply(...ok(ATTR_VALUES));
    mock.onGet(/^\/portal\/frames\/(\d+)\/meta$/).reply((config) =>
      ok(frameMeta(번호(config.url, /frames\/(\d+)\/meta/))),
    );
    mock.onGet(`/portal/videos/${자산번호}/event-annotation`).reply(...ok(EVENT_ANNOTATION));

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
