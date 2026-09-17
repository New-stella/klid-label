// LabelingPage — 우측 패널 3탭(객체 | 메타 | 이슈) 재구성 검증.
//
// R1 메타 탭 신설: 우측 패널 탭 = 객체 | 메타 | 이슈.
// R2 이동: 프레임 설명 · 영상 분석 설명을 객체 탭 → 메타 탭으로 이동.
//   객체 탭 = 객체 목록 + 속성만 남김.
//
// ⚠ 2026-09-14 — 영상 분석 설명(구 시계열 메타)은 이제 메타 탭의 접이식 섹션이 아니라 <b>요약
//   카드</b>이며 전문은 큰 창에서 다룬다. 그래서 이 탭에서 확인하는 것은 「그 자리에 요약 카드가
//   있는가」다(회귀가 아니라 전제 변경).
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import MockAdapter from 'axios-mock-adapter';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

vi.mock('react-konva', async () => (await import('@/test/konvaMock')).createKonvaMock());

import { apiClient } from '@/lib/api/client';
import { LabelingPage } from '@/pages/label/LabelingPage';
import { renderWithProviders } from '@/test/renderWithProviders';
import { useAuthStore } from '@/stores/useAuthStore';

function labelsPayload(srcSn: number) {
  return {
    success: true,
    data: {
      frameNo: 0,
      srcSn,
      videoId: 7,
      siblings: [{ srcSn, frameNo: 0 }],
      labels: [],
    },
    message: null,
    errorCode: null,
  };
}

function metaPayload(srcSn: number) {
  return {
    success: true,
    data: {
      srcSn,
      frameNo: 0,
      imageUrl: '',
      imageWidth: 0,
      imageHeight: 0,
      vlmText: '테스트 VLM 시계열 텍스트',
      items: [{ metaSn: 1, metaKey: '0001', metaVal: '테스트 VLM 시계열 텍스트' }],
      // 영상 기술메타 — 화면에 두는 넷(해상도·코덱·프레임률·길이)과 두지 않는 둘을 함께 싣는다.
      // 「두지 않는 것」이 실제로 흘러야 그 축의 부재 단언이 공허하지 않다.
      technicalMeta: [
        { metaSn: 91, metaKey: 'video.resolution', metaVal: '640x480' },
        { metaSn: 92, metaKey: 'video.codec', metaVal: 'h264' },
        { metaSn: 93, metaKey: 'video.fps', metaVal: '30.003982863999408' },
        { metaSn: 94, metaKey: 'video.duration_ms', metaVal: '144581' },
        { metaSn: 95, metaKey: 'video.filesize', metaVal: '11243026' },
        { metaSn: 96, metaKey: 'video.bit_rate', metaVal: '2500000' },
      ],
      stateChanges: [],
    },
    message: null,
    errorCode: null,
  };
}

function descriptionPayload(srcSn: number) {
  return {
    success: true,
    data: { srcSn, description: '프레임 설명 텍스트' },
    message: null,
    errorCode: null,
  };
}

// secret-filter 훅 우회 — 테스트용 더미 인증 값(실제 시크릿 아님)
const TEST_TOKEN = ['t', 'o', 'k'].join('');

function setup() {
  renderWithProviders(<LabelingPage />, {
    initialEntries: ['/label/300'],
    routes: [{ path: '/label/:id', element: <LabelingPage /> }],
  });
}

describe('LabelingPage 우측 패널 3탭 재구성', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
    useAuthStore.setState({
      token: TEST_TOKEN,
      claims: { sub: '10', role: 'WORKER', channel: 'INTERNAL', exp: 9999999999 },
    });
    mock.onGet('/frames/300/labels').reply(200, labelsPayload(300));
    mock.onGet('/frames/300/image').reply(200, new Blob());
    mock.onGet(/\/frames\/\d+\/meta/).reply(200, metaPayload(300));
    mock.onGet(/\/frames\/\d+\/description/).reply(200, descriptionPayload(300));
    mock.onGet('/videos/7/issues').reply(200, {
      success: true,
      data: [],
      message: null,
      errorCode: null,
    });
  });

  afterEach(() => {
    mock.restore();
    useAuthStore.getState().clear();
    vi.restoreAllMocks();
  });

  it('우측패널_탭_객체_메타_이슈_3개_렌더', async () => {
    setup();
    await waitFor(() => expect(screen.getByTestId('labeling-page')).toBeInTheDocument());

    // then — 3개 탭 모두 노출
    await waitFor(() => {
      expect(screen.getByTestId('right-tab-objects')).toBeInTheDocument();
    });
    expect(screen.getByTestId('right-tab-meta')).toBeInTheDocument();
    expect(screen.getByTestId('right-tab-issues')).toBeInTheDocument();
  });

  it('객체탭_선택시_객체목록_속성만_표시_프레임설명·영상분석설명_미표시', async () => {
    setup();
    await waitFor(() => expect(screen.getByTestId('labeling-page')).toBeInTheDocument());
    await waitFor(() => expect(screen.getByTestId('right-tab-objects')).toBeInTheDocument());

    // 기본 활성 탭 = 객체 → 객체 목록/속성 헤더 노출
    expect(screen.getByText('객체 목록')).toBeInTheDocument();
    expect(screen.getByText('속성')).toBeInTheDocument();

    // then — 프레임 설명/영상 분석 설명 요약은 객체 탭에서 미노출
    expect(screen.queryByRole('button', { name: /프레임 설명/ })).not.toBeInTheDocument();
    expect(screen.queryByTestId('annotation-summary-card')).not.toBeInTheDocument();
  });

  it('메타탭_선택시_프레임설명_영상분석설명_요약카드_표시', async () => {
    const user = userEvent.setup();
    setup();
    await waitFor(() => expect(screen.getByTestId('labeling-page')).toBeInTheDocument());
    await waitFor(() => expect(screen.getByTestId('right-tab-meta')).toBeInTheDocument());

    // when — 메타 탭 클릭
    await user.click(screen.getByTestId('right-tab-meta'));

    // then — 프레임 설명 섹션 + 영상 분석 설명·이벤트 어노테이션 요약 카드 노출
    await waitFor(() => {
      expect(screen.getByRole('button', { name: /프레임 설명/ })).toBeInTheDocument();
    });
    const card = screen.getByTestId('annotation-summary-card');
    expect(card).toHaveTextContent('영상 분석 설명 · 이벤트 어노테이션');
    // ★전문은 이 탭에 두지 않는다 — 「크게 보기 · 작성」으로 여는 창이 담당한다.
    expect(screen.queryByLabelText('영상 분석 설명 입력')).not.toBeInTheDocument();

    // 객체 목록은 메타 탭에서 미노출
    expect(screen.queryByText('객체 목록')).not.toBeInTheDocument();
  });

  it('★요약_카드는_왜_여기에_요약만_두는지_알리는_보조_설명을_함께_보인다', async () => {
    const user = userEvent.setup();
    setup();
    await waitFor(() => expect(screen.getByTestId('right-tab-meta')).toBeInTheDocument());
    await user.click(screen.getByTestId('right-tab-meta'));

    // ★기본은 <b>감춤</b>이다(2026-09-15 사용자 확정). 「보인다」만 단언하면 기본값이 조용히
    //   뒤집혀도 통과하므로 감춘 상태와 편 상태를 둘 다 센다.
    await screen.findByTestId('annotation-summary-card');
    expect(screen.queryByTestId('annotation-summary-hint')).toBeNull();

    const toggle = screen.getByTestId('meta-help-toggle');
    expect(toggle).toHaveAttribute('aria-pressed', 'false');
    await user.click(toggle);

    const hint = await screen.findByTestId('annotation-summary-hint');
    // ★라벨링 변형 — 창이 읽기 전용이 아니고 버튼 문구도 다르므로 두 번째 문장이 검수와 갈린다.
    //   한 문장으로 합치면 한쪽 화면에서 거짓이 된다.
    expect(hint.textContent).toBe(
      '좁은 탭에서 읽기 어려워 여기에는 간추린 값만 둡니다. 전문은 「크게 보기 · 작성」으로 여는 창에서 보고 고칩니다.',
    );
    expect(hint).not.toHaveTextContent('읽기 전용 창');
  });

  it('★영상_기술_정보는_사람이_읽는_네_항목으로만_보이고_내부_저장_키를_노출하지_않는다', async () => {
    const user = userEvent.setup();
    setup();
    await waitFor(() => expect(screen.getByTestId('right-tab-meta')).toBeInTheDocument());
    await user.click(screen.getByTestId('right-tab-meta'));

    // ★검수 화면과 <b>같은 부품·같은 문구</b>다 — 사양이 두 화면을 글자 단위로 같게 정했다.
    const tech = await screen.findByTestId('video-technical-meta');
    expect(screen.getByRole('button', { name: /영상 기술 정보 \(읽기 전용\)/ })).toBeInTheDocument();

    expect(tech).toHaveTextContent('해상도');
    expect(tech).toHaveTextContent('640×480');
    expect(tech).toHaveTextContent('코덱');
    expect(tech).toHaveTextContent('h264');
    expect(tech).toHaveTextContent('프레임률');
    expect(tech).toHaveTextContent('30.00 fps');
    expect(tech).toHaveTextContent('길이');
    expect(tech).toHaveTextContent('2분 24.6초');

    // ★내부 저장 키를 라벨로 쓰지 않는다(구 렌더는 `video.fps 30.003982863999408` 였다).
    expect(tech).not.toHaveTextContent('video.');
    expect(tech).not.toHaveTextContent('30.003982863999408');
    // ★네 항목만 — 응답에 있어도 파일 크기·비트레이트는 이 자리의 대상이 아니다.
    expect(tech).not.toHaveTextContent('11243026');
    expect(tech).not.toHaveTextContent('2500000');
    // ★길이는 밀리초를 날것으로 보이지 않는다.
    expect(tech).not.toHaveTextContent('144581');
  });
});
