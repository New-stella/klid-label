// 영상 상세 — SCREEN-009 확정 시안 표면 정합 가드. [@design SCREEN-009]
//
// 기준: docs/screen-design/klid-authoring-screens/screens/SCREEN-009/design/design-main.{html,css}
//
// ★ 무엇을 지키는 테스트인가
//   시안↔구현 전수 대조에서 잡힌 **이 화면 소관 표면 격차**가 되돌아오지 않게 고정한다.
//   구조(무엇이 어디 있나)는 role·텍스트로, 순수 시각값(크기·색·열 수)은 클래스로 단언한다 —
//   후자는 jsdom 이 실제 픽셀을 계산하지 않으므로 클래스가 유일한 관측 가능한 증거다.
//
// ⚠ 검수자 전용 배치 실패 패널은 이 파일의 대상이 아니다 — 역할을 지정하지 않아(권한 없음)
//   그 패널이 렌더되지 않는 조건에서 검증한다. 그 패널의 시안 정합은 별도 소관이다.

import MockAdapter from 'axios-mock-adapter';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Route, Routes } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { apiClient } from '@/lib/api/client';
import { VideoDetailPage } from '@/pages/VideoDetailPage';
import { renderWithProviders } from '@/test/renderWithProviders';
import { DOT_HALO_PX } from '@/components/common/BatchStageIndicator';
import { useAuthStore } from '@/stores/useAuthStore';

vi.mock('react-router-dom', async () => {
  const actual = await vi.importActual<typeof import('react-router-dom')>('react-router-dom');
  return { ...actual, useNavigate: () => vi.fn() };
});

const DETAIL = {
  id: 42,
  rawSn: 42,
  cctvName: '종로구 CCTV-01',
  vmsCctvId: 'CCTV-01',
  vmsClipId: 'VMS-42',
  eventTypeCd: 'FALL',
  status: 'BATCH_FAILED',
  frameCount: 5250,
  resolution: '1920x1080',
  durationSec: 35,
  capturedAt: '2026-01-15T09:30:00',
  createdAt: '2026-01-15T09:31:04',
  updatedAt: '2026-01-15T11:02:47',
  stages: [],
  deidentHistory: [],
  // ★ timestampMs·hasIssue 는 **서버 응답 계약에 실린다**(BE FramePreviewDto — hasIssue 는 원시
  //   boolean, timestampMs 는 nullable). 세 프레임이 각각 다른 축을 맡는다:
  //   #0  시각 0 + 이슈 없음  — `0` 이 「없음」으로 접히지 않는지(참/거짓 판정 금지)
  //   #24 시각 있음 + 이슈 있음 — 점·배지·시각 표기가 실제로 뜨는지
  //   #48 **시각 null** + 이슈 없음 — ★null 이 `undefined` 가드를 통과해 「null ms」가 그려지지
  //       않는지. 서버는 위치를 모르는 프레임에 **null 을 실어서** 보낸다(키 부재가 아니다).
  framePreviews: [
    { srcSn: 101, frameNo: 0, thumbnailUrl: '/t/0.jpg', timestampMs: 0, hasIssue: false },
    { srcSn: 102, frameNo: 24, thumbnailUrl: '/t/1.jpg', timestampMs: 8000, hasIssue: true },
    { srcSn: 103, frameNo: 48, thumbnailUrl: '/t/2.jpg', timestampMs: null, hasIssue: false },
  ],
};

const AUTO_LABELS = {
  videoId: 42,
  objects: [
    { id: 'L1', labelCode: 'PERSON', labelName: '사람', color: '#4ECDC4', confidence: 0.95, createdBy: 'auto' },
    { id: 'L2', labelCode: 'CAR', labelName: '차량', color: '#FF6B6B', confidence: 0.75, createdBy: 'auto' },
    { id: 'L3', labelCode: 'CAR', labelName: '차량', color: '#FF6B6B', confidence: 0.4, createdBy: 'auto' },
  ],
};

function renderPage() {
  renderWithProviders(
    <Routes>
      <Route path="/video/:id" element={<VideoDetailPage />} />
    </Routes>,
    { initialEntries: ['/video/42'] },
  );
}

/** 상세 로드 완료까지 기다린다. */
async function loaded() {
  await waitFor(() => expect(screen.getByText('종로구 CCTV-01')).toBeInTheDocument());
}

describe('SCREEN-009 시안 정합 — 페이지·헤더 카드', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
    mock.onGet('/videos/42').reply(200, { success: true, data: DETAIL, message: null, errorCode: null });
    mock.onGet('/videos/42/labels/auto').reply(200, { success: true, data: AUTO_LABELS, message: null, errorCode: null });
  });

  afterEach(() => {
    mock.restore();
    useAuthStore.getState().clear();
  });

  // A1 — 시안 `.page-head`(빵부스러기 + h1 + 설명). 공용 PageHeader 재사용.
  it('A1_페이지_헤더가_빵부스러기와_제목과_설명을_갖는다', async () => {
    renderPage();
    await loaded();

    expect(screen.getByRole('heading', { level: 1, name: '영상 상세 화면' })).toBeInTheDocument();
    const crumb = screen.getByRole('navigation', { name: '현재 위치' });
    // 부모는 이 화면으로 오는 실제 진입점(영상 처리 현황)이다 — 시안 표기 「작업 목록」은
    // 자리표시 링크라 그대로 옮기면 무관한 목록으로 보낸다.
    expect(within(crumb).getByRole('link', { name: '영상 처리 현황' })).toHaveAttribute(
      'href',
      '/video/status',
    );
    expect(screen.getByText(/영상 메타와 오토라벨 결과를 조회합니다/)).toBeInTheDocument();
  });

  // A2 — `.screen-root { gap: --sp-lg }` 24px.
  it('A2_페이지_블록_간격은_24px다', async () => {
    renderPage();
    await loaded();

    const root = screen
      .getByRole('heading', { level: 1, name: '영상 상세 화면' })
      .closest('header')?.parentElement;
    expect(root?.className).toContain('gap-6');
    expect(root?.className).not.toContain('space-y-4');
  });

  // B1·B2 — 뒤로가기는 카드 **안**이고 공용 Button(ghost)이다.
  it('B1_B2_뒤로가기는_헤더_카드_안의_공용_버튼이다', async () => {
    renderPage();
    await loaded();

    const back = screen.getByRole('button', { name: '뒤로가기' });
    // 카드 소속 — 제목(h2)과 같은 카드 안에 있다.
    const card = back.closest('[data-slot="card"]');
    expect(card).not.toBeNull();
    expect(within(card as HTMLElement).getByRole('heading', { level: 2 })).toHaveTextContent(
      '종로구 CCTV-01',
    );
    // 공용 Button 계약 — 44px 터치 타깃 + 6px 모서리 + 포커스 링.
    expect(back.className).toContain('min-h-11');
    expect(back.className).toContain('rounded-md');
    expect(back.className).toContain('focus-visible:ring-[3px]');
  });

  // B4·B5·B6 — 200×120 / radius 6 / 다크 매트 / 좌하단 캡션.
  it('B4_B5_B6_썸네일은_200x120_다크매트에_프레임_캡션을_얹는다', async () => {
    renderPage();
    await loaded();

    const caption = screen.getByText('#0 · 00:00');
    const frame = caption.parentElement as HTMLElement;
    expect(frame.className).toContain('w-[200px]');
    expect(frame.className).toContain('h-[120px]');
    expect(frame.className).toContain('rounded-md');
    expect(frame.className).toContain('bg-gray-900');
    // 캡션은 좌하단 오버레이다.
    expect(caption.className).toContain('absolute');
    expect(caption.className).toContain('bottom-2');
    expect(caption.className).toContain('left-2');
  });

  // B8 — 제목 굵기는 ladder title-md(600)가 소유한다(font-bold 덧칠 금지).
  it('B8_제목은_ladder_굵기를_덧칠하지_않는다', async () => {
    renderPage();
    await loaded();

    const title = screen.getByRole('heading', { level: 2, name: '종로구 CCTV-01' });
    expect(title.className).toContain('text-title-md');
    expect(title.className).not.toContain('font-bold');
  });

  // B9·B10·B11 — 키/값 2색 분리 · 콜론 없음 · 값 등폭 · 「녹화 시각」.
  it('B9_B10_B11_헤더_메타는_콜론_없는_키값_두_노드이며_라벨은_녹화_시각이다', async () => {
    renderPage();
    await loaded();

    const hero = screen.getByTestId('video-hero-meta');
    expect(hero.className).toContain('gap-6');

    for (const key of ['길이', '녹화 시각', '해상도']) {
      const node = within(hero).getByText(key);
      // 콜론을 붙이지 않는다 — 키 노드의 텍스트가 키 그 자체다.
      expect(node.textContent).toBe(key);
      expect(node.nextElementSibling?.className).toContain('text-gray-900');
    }
    // 구 표기(콜론 합침)와 구 라벨(녹화일)은 남지 않는다.
    expect(within(hero).queryByText(/해상도:/)).not.toBeInTheDocument();
    expect(within(hero).queryByText('녹화일')).not.toBeInTheDocument();
    // 수치·식별자 값은 등폭이다.
    expect(within(hero).getByText('1920x1080').className).toContain('font-mono');
  });
});

describe('SCREEN-009 시안 정합 — 탭 레일 · 기본 정보', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
    mock.onGet('/videos/42').reply(200, { success: true, data: DETAIL, message: null, errorCode: null });
    mock.onGet('/videos/42/labels/auto').reply(200, { success: true, data: AUTO_LABELS, message: null, errorCode: null });
  });

  afterEach(() => {
    mock.restore();
    useAuthStore.getState().clear();
  });

  // C1·C2·C3 — 레일 패딩 8px / 헤딩 색 n-5 / 헤딩 패딩 8·8·4.
  it('C1_C2_C3_탭_레일의_패딩과_헤딩_색이_시안값이다', async () => {
    renderPage();
    await loaded();

    const rail = screen.getByRole('navigation', { name: '상세 정보' });
    expect(rail.className).toContain('p-2');
    expect(rail.className).not.toContain('p-3');

    const heading = within(rail).getByText('상세 정보');
    expect(heading.className).toContain('text-gray-500');
    expect(heading.className).not.toContain('text-gray-400');
    expect(heading.className).toContain('px-2');
    expect(heading.className).toContain('pt-2');
    expect(heading.className).toContain('pb-1');
  });

  // D1 — mono 칸은 등폭 글꼴이다(자간만 맞추는 tabular-nums 로는 부족).
  it('D1_메타_그리드의_식별자_수치_칸은_등폭_글꼴이다', async () => {
    renderPage();
    await loaded();

    const valueOf = (label: string) =>
      screen.getAllByRole('term').find((el) => el.textContent === label)
        ?.parentElement?.querySelector('dd');

    for (const label of ['CCTV ID', '해상도', '길이']) {
      expect(valueOf(label)?.className).toContain('font-mono');
    }
    // 등폭이 아닌 칸(날짜)은 그대로 본문체다.
    expect(valueOf('녹화 시각')?.className).not.toContain('font-mono');
  });

  // E1·E2 — 시안 `.stage-row`(margin-top --sp-sm=8px / padding-top --sp-md=16px / 상단 구분선)와
  //   `.stage-row-label`. 라벨은 시안이 색을 지정하지 않아 body 상속색(--n-9 = gray-900)이다.
  //   ⚠ 구 구현은 `mt` 가 없어 위 그리드에 붙었고 라벨이 gray-600 이라 한 단 흐렸다.
  it('E1_E2_처리_단계_밴드는_위쪽_여백을_갖고_라벨은_본문색이다', async () => {
    renderPage();
    await loaded();

    const band = screen.getByText('처리 단계').parentElement;
    expect(band?.className).toContain('mt-2'); // --sp-sm = 8px
    expect(band?.className).toContain('pt-4'); // --sp-md = 16px
    expect(band?.className).toContain('border-t');

    const label = screen.getByText('처리 단계');
    expect(label.className).toContain('mb-2'); // --sp-sm = 8px
    // 시안이 색을 지정하지 않는다 = body 상속(--n-9). 한 단 흐린 gray-600 으로 되돌리지 말 것.
    expect(label.className).toContain('text-gray-900');
    expect(label.className).not.toContain('text-gray-600');
  });

  // ★ 브라우저 실렌더에서만 보이던 결함의 되돌림 가드. [@design SCREEN-009]
  //   이 래퍼는 가로 스크롤을 위해 `overflow-x` 를 걸지만, CSS 규칙상 한 축이 `visible` 이
  //   아니면 다른 축도 `auto` 로 계산되어 **세로로도 잘라 낸다**. 표시기의 진행 중 점은 위쪽
  //   모서리가 이 상자의 내용 상자 top 과 같고 헤일로는 `box-shadow` 라 스크롤 영역에 기여하지
  //   않아, 위로 뻗은 두께가 그대로 잘렸다(실측 3px — 점이 «위가 평평한 반달»이었다).
  //   ⚠ jsdom 은 그 잘림을 계산하지 않는다 — 여기서 고정하는 것은 **여백이 헤일로 두께에서
  //     파생된다는 사실**이고, 잘리지 않는다는 것은 브라우저 실측이 따로 증언한다.
  it('★처리_단계_래퍼가_헤일로_두께만큼_위쪽_자리를_비운다', async () => {
    // 기본 픽스처는 `stages: []`(표시기 미렌더 → 배지 폴백)이라 이 가드에서만 단계를 싣는다.
    mock.reset();
    mock.onGet('/videos/42').reply(200, {
      success: true,
      data: {
        ...DETAIL,
        stages: [
          { name: 'DEIDENTIFY', status: 'DONE', progress: null },
          { name: 'MARKING', status: 'PROGRESS', progress: null },
        ],
      },
      message: null,
      errorCode: null,
    });
    mock.onGet('/videos/42/labels/auto').reply(200, {
      success: true,
      data: AUTO_LABELS,
      message: null,
      errorCode: null,
    });
    renderPage();
    await loaded();

    const wrapper = screen.getByTestId('batch-stage-indicator').parentElement;
    expect(wrapper).not.toBeNull();
    expect(wrapper!.className).toContain('overflow-x-auto');
    // 숫자를 여기 적지 않는다 — 표시기가 내보내는 두께와 **같은 값**임을 단언한다.
    expect(wrapper!.style.paddingTop).toBe(`${DOT_HALO_PX}px`);
  });
});

describe('SCREEN-009 시안 정합 — 프레임 미리보기 탭', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
    mock.onGet('/videos/42').reply(200, { success: true, data: DETAIL, message: null, errorCode: null });
    mock.onGet('/videos/42/labels/auto').reply(200, { success: true, data: AUTO_LABELS, message: null, errorCode: null });
  });

  afterEach(() => {
    mock.restore();
    useAuthStore.getState().clear();
  });

  async function openFrames() {
    const user = userEvent.setup();
    renderPage();
    await loaded();
    await user.click(screen.getByRole('tab', { name: /프레임 미리보기/ }));
    return user;
  }

  // H1 — 툴바(표시 개수 + 이슈 점 범례).
  it('H1_툴바가_표시_개수와_이슈_점_범례를_말한다', async () => {
    await openFrames();

    // 분모는 영상 전체 프레임 수, 분자는 실제로 그린 미리보기 수다.
    expect(screen.getByText('총 5,250개 프레임 중 3개 표시')).toBeInTheDocument();
    expect(screen.getByText('빨간 점 = 이슈 있는 프레임')).toBeInTheDocument();
  });

  it('H1_전체_프레임_수를_못_받으면_실제로_그린_수를_분모로_쓴다', async () => {
    mock.onGet('/videos/42').reply(200, {
      success: true,
      // 구 응답은 frameCount 가 0 으로 폴백된다 — 분모가 분자보다 작아지면 안 된다.
      data: { ...DETAIL, frameCount: 0 },
      message: null,
      errorCode: null,
    });
    await openFrames();

    expect(screen.getByText('총 3개 프레임 중 3개 표시')).toBeInTheDocument();
  });

  // H2·H4 — 타일 테두리 + radius 6, 미디어 매트 n-8.
  it('H2_H4_타일은_테두리를_갖고_미디어_매트는_다크다', async () => {
    await openFrames();

    const tile = screen.getByRole('button', { name: '프레임 0 상세 보기' });
    expect(tile.className).toContain('border-gray-200');
    expect(tile.className).toContain('rounded-md');

    const media = within(tile).getByLabelText('Frame 0');
    expect(media.className).toContain('bg-gray-800');
    expect(media.className).not.toContain('bg-gray-100');
  });

  // H3 — 프레임 번호는 이미지 **아래** 흰 스트립이다(오버레이 아님).
  it('H3_프레임_번호는_이미지를_가리지_않고_아래에_온다', async () => {
    await openFrames();

    const tile = screen.getByRole('button', { name: '프레임 0 상세 보기' });
    const caption = within(tile).getByText('#0');
    expect(caption.className).not.toContain('absolute');
    expect(caption.className).not.toContain('bg-black/50');
    // 이미지 다음 형제로 온다.
    expect(within(tile).getByLabelText('Frame 0').nextElementSibling).toBe(caption);
  });

  /**
   * H5 — ★이슈 점은 **이슈가 있는 프레임에만** 뜬다.
   *
   * ⚠ 이 자리에는 «두 필드가 서버 응답 계약에 없어 도달 불가»를 고정하던 가드가 있었다. 그 가드는
   * ①BE 계약 → ②FE 매핑 → ③표면 가드 순서로 고치라고 적어 두고, ①②가 끝나면 스스로 실패해
   * ③을 알리도록 만들어진 장치였다. **계약이 채워져 전제가 소멸했으므로** 그 역할을 다했고,
   * 여기서부터는 「그려진다」를 고정하는 표면 가드가 대신한다.
   *
   * 색만으로 뜻을 전하지 않는다는 계약도 함께 본다 — 점에 보조기술용 이름(`이슈 있음`)이 있고
   * 툴바 범례가 그 색의 뜻을 글로 적는다(범례는 H1 이 고정한다).
   */
  it('H5_이슈가_있는_프레임에만_점이_뜬다', async () => {
    await openFrames();

    const issueTile = screen.getByRole('button', { name: '프레임 24 상세 보기' });
    const dot = within(issueTile).getByRole('img', { name: '이슈 있음' });
    // 시안 `.frame-dot` — 10px 원 + 흰 테두리(어두운 프레임 위에서 점이 묻히지 않게 한다).
    expect(dot.className).toContain('h-2.5');
    expect(dot.className).toContain('w-2.5');
    expect(dot.className).toContain('rounded-full');
    expect(dot.className).toContain('border-white');
    expect(dot.className).toContain('bg-danger');

    // 이슈가 없는 프레임에는 뜨지 않는다 — 「전부 뜬다」로 통과하는 가드가 되지 않게 한다.
    for (const frameNo of [0, 48]) {
      const tile = screen.getByRole('button', { name: `프레임 ${frameNo} 상세 보기` });
      expect(within(tile).queryByRole('img', { name: '이슈 있음' })).not.toBeInTheDocument();
    }
    expect(screen.getAllByRole('img', { name: '이슈 있음' })).toHaveLength(1);
  });

  // I3·I4 — 확대 보기가 시각과 이슈 배지를 값에 따라 보여준다.
  //
  // ★표기는 확정 디자인의 `hh:mm:ss.SSS`(`00:00:00.960`)다 — 구 표기인 원시 밀리초(`8000 ms`)로
  //   되돌리면 이 단언이 문다. 긴 영상에서 원시 밀리초는 사람이 어느 지점인지 읽어낼 수 없다.
  it('I3_I4_확대_보기는_시각과_이슈_배지를_보여준다', async () => {
    const user = await openFrames();
    await user.click(screen.getByRole('button', { name: '프레임 24 상세 보기' }));

    const dialog = await screen.findByRole('dialog');
    // 다이얼로그의 접근 이름으로 대상을 확정한다 — 제목과 본문 줄이 같은 문자열이라
    // `getByText` 는 두 노드를 함께 집는다(둘 다 정상 렌더다).
    expect(dialog).toHaveAccessibleName('프레임 #24');
    expect(within(dialog).getByText('00:00:08.000')).toBeInTheDocument();
    // 구 표기(원시 밀리초)는 남지 않는다.
    expect(within(dialog).queryByText('8000 ms')).not.toBeInTheDocument();
    // 이슈 여부는 평문이 아니라 배지다(그리드의 빨간 점과 같은 위험 톤).
    expect(within(dialog).getByText('이슈 있음')).toBeInTheDocument();
  });

  /**
   * I3 표면 — ★확대 보기의 시각은 **등폭 글꼴**이다.
   *
   * `hh:mm:ss.SSS` 는 **자릿수가 고정된 값**이라 본문체로 그리면 글자마다 폭이 달라 프레임을 넘길
   * 때 숫자가 좌우로 흔들린다. 같은 화면의 「길이」·「해상도」 값이 **이미 같은 이유로**
   * `font-mono text-mono` 를 쓰므로 그 관례를 그대로 따른다(새 클래스·새 토큰을 만들지 않는다).
   *
   * ⚠ 이 케이스는 **글꼴 축만** 본다 — 표기값(`hh:mm:ss.SSS`)·시각 `0`·미상(`-`) 축은 위아래의
   *   기존 케이스들이 맡는다. 한 케이스가 두 축을 함께 물면 어느 쪽이 깨졌는지 실패가 말해주지 못한다.
   */
  it('I3_확대_보기의_시각은_등폭_글꼴이다', async () => {
    const user = await openFrames();
    await user.click(screen.getByRole('button', { name: '프레임 24 상세 보기' }));

    const dialog = await screen.findByRole('dialog');
    const clock = within(dialog).getByText('00:00:08.000');
    expect(clock.className).toContain('font-mono');
    expect(clock.className).toContain('text-mono');
  });

  /**
   * I3 경계 — ★시각 `0` 은 **유효한 값**이다(영상 첫 프레임).
   *
   * 가드를 참/거짓 판정(`timestampMs &&`)으로 좁히면 이 프레임의 시각이 조용히 사라진다.
   *
   * ⚠ `0` 의 표기는 **`00:00:00.000`** 이지 미상(`-`)이 아니다 — 둘이 같아지면 「모른다」와
   *   「영상 맨 앞이다」가 확대 보기에서 구분되지 않는다(캡션 축의 `00:00` 과 같은 함정).
   */
  it('I3_시각이_0인_프레임도_시각을_보여준다', async () => {
    const user = await openFrames();
    await user.click(screen.getByRole('button', { name: '프레임 0 상세 보기' }));

    const dialog = await screen.findByRole('dialog');
    expect(within(dialog).getByText('00:00:00.000')).toBeInTheDocument();
    expect(within(dialog).queryByText('-')).not.toBeInTheDocument();
    expect(within(dialog).queryByText('이슈 있음')).not.toBeInTheDocument();
  });

  /**
   * I3 경계 — ★시·분·초·밀리 **네 칸이 각각 제 자리에** 들어가는지.
   *
   * ★★공용 픽스처(`0` · `8000`)로는 이 축이 **원리적으로 검증되지 않는다** — 두 값 모두 시·분이
   * `0` 이라 시와 분을 맞바꾸거나 시 칸을 통째로 빠뜨려도 기대값이 그대로 나온다. 그래서 이
   * 케이스만 네 칸이 **모두 다른 값**이 되는 응답으로 갈아 끼운다(`01:02:03.045`).
   *
   * ⚠ 밀리는 **세 자리**로 채운다 — `45` 로 적으면 `0.045초`가 `0.45초`로 읽힌다. 그래서 밀리가
   * 두 자리인 값을 고른다(세 자리 값이면 자리채움이 검증되지 않는다).
   */
  it('I3_확대_보기는_시_분_초_밀리를_각각_제_자리에_적는다', async () => {
    mock.onGet('/videos/42').reply(200, {
      success: true,
      data: {
        ...DETAIL,
        framePreviews: [
          { srcSn: 102, frameNo: 24, thumbnailUrl: '/t/1.jpg', timestampMs: 3_723_045, hasIssue: true },
        ],
      },
      message: null,
      errorCode: null,
    });

    const user = await openFrames();
    await user.click(screen.getByRole('button', { name: '프레임 24 상세 보기' }));

    const dialog = await screen.findByRole('dialog');
    expect(within(dialog).getByText('01:02:03.045')).toBeInTheDocument();
  });

  /**
   * I3 경계 — ★★시각이 **`null`** 인 프레임에서는 그 자리에 **`-`** 를 적는다.
   *
   * 서버는 영상 내 위치를 모르는 프레임에 `timestampMs: null` 을 **실어서** 보낸다(키 부재가
   * 아니다 — 이 프로젝트는 직렬화에서 null 을 생략하지 않는다). 구 가드(`!== undefined`)는 그
   * null 을 통과시켜 화면에 「null ms」를 그렸다. **픽스처가 `undefined` 였다면 그 결함을 되돌려도
   * 통과한다** — 그래서 여기 실리는 값은 반드시 `null` 이어야 한다(그 축은 그대로 유지한다).
   *
   * ★★구 동작(줄을 통째로 그리지 않는다)에서 뒤집혔다 — **미상을 실제 값처럼 보여주던 것을
   * 고쳤다**. 줄을 숨기면 같은 프레임의 썸네일 캡션과 이 자리가 같은 값을 다르게 보여준다.
   * 미상은 `-` 하나이며 **`- ms` 처럼 단위를 붙이지 않는다**(없는 값에 단위를 붙이면 값처럼 읽힌다).
   *
   * ⚠ `00:00` 이 나오면 안 된다 — 그건 **영상 맨 앞 프레임의 실제 값**이라 미상과 구분되지 않는다.
   */
  it('I3_시각이_null_인_프레임에서는_미상_표기를_그린다', async () => {
    const user = await openFrames();
    await user.click(screen.getByRole('button', { name: '프레임 48 상세 보기' }));

    const dialog = await screen.findByRole('dialog');
    expect(dialog).toHaveAccessibleName('프레임 #48');
    // 줄은 그린다 — 그리고 그 자리에 적히는 것은 `-` 하나다.
    expect(within(dialog).getByText('-')).toBeInTheDocument();
    // ★구 결함(`null ms`)·구 미상 표기(`00:00`)·단위 붙은 미상(`- ms`) 어느 것도 나오면 안 된다.
    expect(within(dialog).queryByText(/ms/)).not.toBeInTheDocument();
    expect(within(dialog).queryByText(/null/)).not.toBeInTheDocument();
    expect(within(dialog).queryByText(/undefined/)).not.toBeInTheDocument();
    expect(within(dialog).queryByText(/00:00/)).not.toBeInTheDocument();
    // 없는 값을 문구로 채우지 않는다.
    expect(within(dialog).queryByText('없음')).not.toBeInTheDocument();
    expect(within(dialog).queryByText('이슈 있음')).not.toBeInTheDocument();
  });

  /**
   * I3 경계 — ★썸네일 캡션도 같은 기준이다(확대 보기와 두 자리가 갈리지 않게 한다).
   *
   * 헤더 썸네일은 `framePreviews[0]` 이므로 시각 `0` 인 #0 이 실린다 — **`00:00` 이 그대로**
   * 나와야 한다. `0` 이 미상(`-`)으로 떨어지면 영상 맨 앞 프레임의 시각이 조용히 사라진다.
   */
  it('I3_썸네일_캡션은_시각_0을_00_00으로_그린다', async () => {
    renderPage();
    await loaded();

    expect(screen.getByText('#0 · 00:00')).toBeInTheDocument();
    expect(screen.queryByText('#0 · -')).not.toBeInTheDocument();
  });

  /**
   * I3 경계 — ★★썸네일 캡션의 **미상**은 `-` 다. 이 케이스가 없으면 `formatClock` 의 미상
   * 반환을 `'00:00'` 으로 되돌려도 아무도 못 잡는다(실증: 되돌리면 이 케이스만 RED).
   *
   * ★공용 픽스처의 `framePreviews[0]` 은 시각 `0` 이라 **미상 경로를 한 번도 지나지 않는다** —
   * `0` 과 미상이 둘 다 `00:00` 으로 보이던 것이 바로 이 결함이었으므로, 그 픽스처로는 원리적으로
   * 구분할 수 없다. 그래서 이 케이스만 **첫 프레임이 미상인 응답**으로 갈아 끼운다.
   *
   * ⚠ 실리는 값은 반드시 `null` 이다 — `undefined` 로 두면 서버가 실제로 보내는 모양과 달라져
   * 「키가 없으면」 만 거르는 구현으로 되돌려도 통과한다(확대 보기 축과 같은 이유).
   */
  it('I3_썸네일_캡션은_시각이_null_이면_미상_표기를_그린다', async () => {
    mock.reset();
    mock.onGet('/videos/42').reply(200, {
      success: true,
      data: {
        ...DETAIL,
        framePreviews: [
          { srcSn: 101, frameNo: 0, thumbnailUrl: '/t/0.jpg', timestampMs: null, hasIssue: false },
        ],
      },
      message: null,
      errorCode: null,
    });

    renderPage();
    await loaded();

    expect(screen.getByText('#0 · -')).toBeInTheDocument();
    // ★`00:00` 은 **영상 맨 앞 프레임의 실제 값**이라 미상 자리에 나오면 안 된다.
    expect(screen.queryByText('#0 · 00:00')).not.toBeInTheDocument();
  });

  // H6 — hover/focus 시 '상세 보기' 어포던스. 타일이 버튼이라 중첩 button 을 두지 않는다.
  it('H6_타일에_상세_보기_어포던스가_있고_중첩_버튼이_아니다', async () => {
    await openFrames();

    const tile = screen.getByRole('button', { name: '프레임 0 상세 보기' });
    const affordance = within(tile).getByText('상세 보기');
    expect(affordance).toBeInTheDocument();
    expect(tile.querySelector('button')).toBeNull();
    // 접근 이름은 여전히 타일의 aria-label 이다(어포던스가 이름을 오염시키지 않는다).
    expect(tile.className).toContain('group');
    const overlay = affordance.parentElement as HTMLElement;
    expect(overlay.className).toContain('group-hover:opacity-100');
    expect(overlay.className).toContain('group-focus-visible:opacity-100');
    expect(overlay.className).toContain('pointer-events-none');
  });

  // H7 — 1280 이상 6열 / 그 아래 4열.
  it('H7_열_수는_기본_4열이고_1280_이상에서_6열이다', async () => {
    await openFrames();

    const grid = screen.getByRole('button', { name: '프레임 0 상세 보기' }).parentElement as HTMLElement;
    expect(grid.className).toContain('grid-cols-4');
    expect(grid.className).toContain('xl:grid-cols-6');
    expect(grid.className).not.toContain('grid-cols-3');
    expect(grid.className).not.toContain('md:grid-cols-6');
  });

  // I2·I4 — 라이트박스 미디어 매트(16:9 다크) + 이슈 배지.
  it('I2_I4_라이트박스는_16대9_다크매트와_이슈_배지를_쓴다', async () => {
    const user = await openFrames();
    await user.click(screen.getByRole('button', { name: '프레임 24 상세 보기' }));

    const dialog = await screen.findByRole('dialog');
    const media = within(dialog).getByLabelText('Frame 24').parentElement as HTMLElement;
    expect(media.className).toContain('aspect-video');
    expect(media.className).toContain('bg-gray-900');
    expect(media.className).toContain('rounded-md');
    expect(media.className).not.toContain('max-h-80');
    // 이미지가 매트 안에서 비율을 유지한다(잘라내지 않는다).
    expect(within(dialog).getByLabelText('Frame 24').className).toContain('object-contain');
  });
});

describe('SCREEN-009 시안 정합 — 오토라벨 결과 탭', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
    mock.onGet('/videos/42').reply(200, { success: true, data: DETAIL, message: null, errorCode: null });
    mock.onGet('/videos/42/labels/auto').reply(200, { success: true, data: AUTO_LABELS, message: null, errorCode: null });
  });

  afterEach(() => {
    mock.restore();
    useAuthStore.getState().clear();
  });

  async function openAuto() {
    const user = userEvent.setup();
    renderPage();
    await loaded();
    await user.click(screen.getByRole('tab', { name: /오토라벨 결과/ }));
    return user;
  }

  // J1·J2·J3·J4 — 4열 / 흰 카드 + 테두리 / 값 display-sm + 단위 분리 / 라벨 label(600).
  it('J1_J2_J3_J4_통계는_4열_흰_카드이고_값과_단위가_분리된다', async () => {
    await openAuto();

    const label = await screen.findByText('총 라벨 수');
    const card = label.closest('div') as HTMLElement;
    const strip = card.parentElement as HTMLElement;

    expect(strip.className).toContain('grid-cols-2');
    expect(strip.className).toContain('xl:grid-cols-4');
    expect(strip.className).not.toContain('md:grid-cols-3');

    expect(card.className).toContain('border-gray-200');
    expect(card.className).toContain('bg-white');
    expect(card.className).not.toContain('bg-gray-50');

    expect(label.className).toContain('text-label');
    const value = label.nextElementSibling as HTMLElement;
    expect(value.className).toContain('text-display-sm');
    // 단위는 값에 붙되 별도 노드·별도 크기다.
    expect(within(value).getByText('건').className).toContain('text-body-md');
  });

  // J6·J7 — 두 차트는 좌우 두 칸이고 각각 카드다.
  it('J6_J7_두_차트는_좌우_두_칸의_카드다', async () => {
    await openAuto();

    const confTitle = await screen.findByText('신뢰도 분포');
    const distTitle = screen.getByText('라벨별 분포 (상위 10종)');
    const confCard = confTitle.parentElement as HTMLElement;
    const distCard = distTitle.parentElement as HTMLElement;

    expect(confCard.parentElement).toBe(distCard.parentElement);
    expect((confCard.parentElement as HTMLElement).className).toContain('xl:grid-cols-2');
    for (const c of [confCard, distCard]) {
      expect(c.className).toContain('border-gray-200');
      expect(c.className).toContain('rounded-lg');
    }
    expect(confTitle.className).toContain('text-gray-900');
    expect(distTitle.className).toContain('mb-4');
  });

  // J8·J9·J10·J11 — 차트 높이 180 / 막대 폭 40 / 중간색 w-4 / 라벨 문구 / 값 타이포.
  it('J8_J9_J10_J11_막대_차트의_크기와_색과_라벨_문구가_시안값이다', async () => {
    await openAuto();

    const high = await screen.findByText('0.9 이상');
    const chart = high.closest('div')?.parentElement as HTMLElement;
    expect(chart.className).toContain('h-[180px]');
    expect(chart.className).toContain('border-b');

    // 구 표기(0.9+ / <0.7)는 남지 않는다.
    expect(screen.queryByText('0.9+')).not.toBeInTheDocument();
    expect(screen.queryByText('<0.7')).not.toBeInTheDocument();
    expect(screen.getByText('0.7 미만')).toBeInTheDocument();

    const col = high.parentElement as HTMLElement;
    const bar = col.querySelector('[style*="height"]') as HTMLElement;
    expect(bar.className).toContain('w-10');
    expect(bar.className).toContain('rounded-t-[3px]');

    // 중간 버킷은 warning-400(#C78500) — DEFAULT(500)는 저(danger)와 명도가 붙는다.
    const mid = screen.getByText('0.7~0.9').parentElement as HTMLElement;
    expect((mid.querySelector('[style*="height"]') as HTMLElement).className).toContain(
      'bg-warning-400',
    );

    // 값 표기는 body-sm(15/400) + n-9.
    const value = col.firstElementChild as HTMLElement;
    expect(value.className).toContain('text-body-sm');
    expect(value.className).not.toContain('font-semibold');
  });

  // J12·J14·J15 — 열 폭 고정 / 트랙 배경 n-0 / 제목이 절단을 밝힌다. J13(라벨 고유색)은 불변.
  it('J12_J13_J14_J15_라벨별_분포의_열폭과_트랙색과_제목이_시안값이다', async () => {
    await openAuto();

    const title = await screen.findByText('라벨별 분포 (상위 10종)');
    expect(title).toBeInTheDocument();

    const name = screen.getByText('차량');
    const row = name.parentElement as HTMLElement;
    expect(row.className).toContain('grid-cols-[96px_1fr_48px]');
    expect(name.className).toContain('text-body-sm');

    const track = name.nextElementSibling as HTMLElement;
    expect(track.className).toContain('bg-gray-50');
    expect(track.className).not.toContain('bg-gray-100');

    // J13 — 막대는 라벨 마스터 고유색을 그대로 쓴다(단일색으로 덮지 않는다).
    const fill = track.firstElementChild as HTMLElement;
    expect(fill.style.backgroundColor).toBe('rgb(255, 107, 107)');
  });

  // J16 — 공용 Skeleton + 시안 폭 구성(60% / 100% / 100%).
  it('J16_로딩은_공용_스켈레톤을_시안_폭_구성으로_쓴다', async () => {
    mock.onGet('/videos/42/labels/auto').reply(() => new Promise(() => {}));
    await openAuto();

    const bars = await waitFor(() => {
      const found = screen.getAllByRole('presentation');
      expect(found.length).toBeGreaterThanOrEqual(3);
      return found;
    });
    expect(bars[0].style.width).toBe('60%');
    expect(bars[0].style.height).toBe('16px');
    expect(bars[1].style.height).toBe('64px');
    expect(bars[1].className).toContain('animate-pulse');
  });

  // J17 — 공용 ErrorState(아이콘 + 제목 + 캡션, role=alert).
  it('J17_에러는_공용_ErrorState_로_아이콘과_함께_알린다', async () => {
    mock.onGet('/videos/42/labels/auto').reply(500);
    await openAuto();

    const alert = await screen.findByRole('alert');
    expect(within(alert).getByText('오토라벨 결과를 불러올 수 없습니다')).toBeInTheDocument();
    expect(alert.querySelector('svg')).not.toBeNull();
  });

  // J18 — 공용 EmptyState(아이콘 + 제목 + 캡션, role=status).
  it('J18_빈_상태는_공용_EmptyState_로_아이콘과_함께_알린다', async () => {
    mock.onGet('/videos/42/labels/auto').reply(200, {
      success: true,
      data: { videoId: 42, objects: [] },
      message: null,
      errorCode: null,
    });
    await openAuto();

    const status = await screen.findByRole('status');
    expect(within(status).getByText('오토라벨 결과가 없습니다')).toBeInTheDocument();
    expect(status.querySelector('svg')).not.toBeNull();
  });
});
