// 회귀 가드 — 포털 라벨링 화면이 **부모 포털 시안의 부품**을 입는다. [@design SCREEN-029]
//
// 2026-09-16 사용자 지적 — *"상단에 저장버튼이나, 탭 컴포넌트가 패널 영역에 꽉찬다던지
// 이미지 조절 프로그래스바나 하단 프레임 재생 영역도 되다 만거 같아서"*.
// 넷 다 **기능은 이미 있었고 생김새만 갈려 있었다.** 그래서 이 가드가 보는 것은 언제나 둘이다 —
// «부품이 킷인가»와 «배선·문구는 관제와 같은가». 배선까지 갈리면 두 벌을 유지하는 것과 같아진다.
//
// ⚠ 이 가드는 «예쁜가»를 말하지 않는다. 어느 클래스를 입었고 무엇이 같은 값을 받는지만 본다.

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { screen, waitFor, within } from '@testing-library/react';
import MockAdapter from 'axios-mock-adapter';

vi.mock('react-konva', async () => (await import('@/test/konvaMock')).createKonvaMock());

import { apiClient } from '@/lib/api/client';
import { LabelingPage } from '@/pages/label/LabelingPage';
import { renderWithProviders } from '@/test/renderWithProviders';
import { useAuthStore } from '@/stores/useAuthStore';

const labelsPayload = {
  success: true,
  data: {
    frameNo: 1,
    srcSn: 555,
    videoId: 5,
    // 낱장 둘 — 한 장짜리로는 「고른 장만 표식을 진다」를 가를 수 없다.
    siblings: [
      { srcSn: 555, frameNo: 1 },
      { srcSn: 556, frameNo: 2 },
    ],
    labels: [],
  },
  message: null,
  errorCode: null,
};

describe('포털 라벨링 — 킷 부품 이관', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
    useAuthStore.setState({
      token: 'tok',
      claims: { sub: 'u-99', role: 'PORTAL_USER', channel: 'PORTAL', exp: 9999999999 },
    });
    mock.onGet('/portal/frames/555/labels').reply(200, labelsPayload);
    mock.onGet(/\/portal\/frames\/\d+\/image/).reply(200, new Blob([new Uint8Array([1])]));
    mock.onGet('/portal/frames/555/meta').reply(200, {
      success: true,
      data: { rawSn: 5, srcSn: 555, items: [], readOnlyMeta: [], technicalMeta: [] },
      message: null,
      errorCode: null,
    });
    mock.onGet('/portal/videos/5/event-annotation').reply(200, {
      success: true,
      data: { rawSn: 5, annotation: null, overridden: false },
      message: null,
      errorCode: null,
    });
  });

  afterEach(() => {
    mock.restore();
    useAuthStore.getState().clear();
  });

  async function renderPortalLabel() {
    const view = renderWithProviders(<LabelingPage />, {
      initialEntries: ['/label/555'],
      routes: [{ path: '/label/:id', element: <LabelingPage /> }],
    });
    // ⚠ `labeling-page` 는 **불러오는 중에도** 선다 — 그것만 기다리면 빈 화면을 재고 만다.
    //   우측 칸이 서야 라벨 조회가 끝난 것이다.
    await screen.findByTestId('right-tab-objects');
    await waitFor(() => expect(screen.getByTestId('labeling-page')).toBeInTheDocument());
    return view;
  }

  describe('① 우측 패널 탭이 칸을 꽉 채운다', () => {
    it('★킷의 「꽉 채움」 표식을 진다 — 이 한 낱말이 폭을 나눈다', async () => {
      const { container } = await renderPortalLabel();
      const tab = container.querySelector('.krds-tab-area .tab.line');
      expect(tab, '킷 탭 짜임이 아니다').not.toBeNull();
      // 킷 규칙이 `.tab.full>ul{display:flex}` · `>li{flex:1 1 0}` 로 폭을 나눈다.
      expect(tab).toHaveClass('full');
    });

    it('탭 짜임과 뜻은 종전 그대로다 — 같은 문서의 칸을 가르는 진짜 탭이다', async () => {
      await renderPortalLabel();
      const objects = screen.getByTestId('right-tab-objects');
      expect(objects).toHaveAttribute('role', 'tab');
      expect(objects).toHaveClass('btn-tab');
      expect(objects.closest('li')).toHaveClass('tab-item', 'active');
    });
  });

  describe('② 저장 버튼이 킷 것이다', () => {
    /** 킷 버튼인가 — 킷이 제 클래스를 단다. */
    const isKit = (el: HTMLElement) =>
      Array.from(el.classList).some((c) => c.startsWith('krds-btn'));

    it('★포털은 킷 기본 버튼이다', async () => {
      await renderPortalLabel();
      expect(isKit(screen.getByTestId('label-toolbar-save'))).toBe(true);
    });

    it('★이름·안내는 종전과 같은 값을 받는다 — 갈린 것은 부품뿐이다', async () => {
      await renderPortalLabel();
      const save = screen.getByTestId('label-toolbar-save');
      expect(save).toHaveAccessibleName('저장');
      expect(save.getAttribute('title') ?? '').toContain('저장');
    });
  });

  describe('③ 이미지 조절이 킷 판·킷 막대다', () => {
    it('★킷 판을 입는다 — 이름 줄 · 초기화 · 딸린 안내', async () => {
      await renderPortalLabel();
      // ⚠ 화면에 킷 판이 여럿이다(좌측 도구 칸도 같은 판을 쓴다) — 이름으로 그 판을 집는다.
      const head = screen.getByText('이미지 조절');
      const panel = head.closest('.klid-tool-panel');
      expect(panel, '이미지 조절이 킷 판이 아니다').not.toBeNull();
      expect(head).toHaveClass('klid-tool-panel-title');
      expect(within(panel as HTMLElement).getByRole('button', { name: '초기화' })).toBeInTheDocument();
      // 딸린 안내도 판이 그린다 — 우리가 문단을 따로 두지 않는다.
      expect((panel as HTMLElement).querySelector('.klid-tool-panel-note')?.textContent).toContain(
        '저장되지 않습니다',
      );
    });

    it('★막대 넷이 킷 막대다 — 날 `input` 을 그대로 두지 않는다', async () => {
      const { container } = await renderPortalLabel();
      const ranges = container.querySelectorAll('.klid-range input[type="range"]');
      // 밝기 · 대비 · 라벨 투명도 · 작업 투명도 + 하단 재생 줄의 위치 막대
      expect(ranges.length).toBeGreaterThanOrEqual(5);
    });

    it('★범위는 채널을 가리지 않는다 — 시안 눈금에 맞춰 바꾸면 보정 세기가 달라진다', async () => {
      const { container } = await renderPortalLabel();
      const brightness = Array.from(
        container.querySelectorAll<HTMLInputElement>('.klid-range input[type="range"]'),
      ).find((el) => el.closest('label')?.textContent?.startsWith('밝기'));
      expect(brightness, '밝기 막대를 찾지 못했다').toBeDefined();
      // 캔버스 필터 계약 — konva 가 −1~1 로 받는다.
      expect(brightness?.min).toBe('-1');
      expect(brightness?.max).toBe('1');
    });
  });

  describe('④ 하단 프레임 줄·재생 줄이 킷 것이다', () => {
    it('★낱장이 킷 꼴이다 — 번호가 그림 밖 아래에 선다', async () => {
      const { container } = await renderPortalLabel();
      const strip = container.querySelector('.klid-frame-strip');
      expect(strip, '킷 프레임 줄이 아니다').not.toBeNull();
      const items = container.querySelectorAll('.klid-frame-strip-item');
      expect(items).toHaveLength(2);
      // 번호는 그림 형제다 — 그림 «위»에 겹쳐 얹던 구 꼴로 되돌아가지 않는다.
      expect(items[0].querySelector('.index')?.textContent).toBe('1');
    });

    it('★고른 장만 표식을 진다 — 킷이 그 표식으로 테를 그린다', async () => {
      const { container } = await renderPortalLabel();
      const current = container.querySelectorAll('.klid-frame-strip-item[aria-current="true"]');
      expect(current).toHaveLength(1);
      expect(current[0].getAttribute('data-frame-index')).toBe('0');
    });

    it('★시험 후크와 이름은 관제와 같은 값을 받는다', async () => {
      const { container } = await renderPortalLabel();
      const first = container.querySelector('.klid-frame-strip-item') as HTMLElement;
      expect(first.getAttribute('data-frame-status')).toBe('CURRENT');
      expect(first).toHaveAccessibleName('프레임 1');
    });

    it('★재생 줄이 킷 것이다 — 막대 이름은 종전 그대로다', async () => {
      const { container } = await renderPortalLabel();
      expect(container.querySelector('.klid-playback'), '킷 재생 줄이 아니다').not.toBeNull();
      expect(screen.getByLabelText('프레임 슬라이더')).toBeInTheDocument();
    });

    it('★줄 끝 글은 관제와 같은 문자열이다', async () => {
      const { container } = await renderPortalLabel();
      expect(container.querySelector('.klid-playback-trail')?.textContent).toBe('1 / 2 · 00:00');
    });
  });
});
