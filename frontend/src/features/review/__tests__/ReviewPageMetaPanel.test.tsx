// SCR-REVIEW-002 — 검수 화면의 영상 분석 설명 · 이벤트 어노테이션(읽기 전용).
//
// 근본원인: 검수자가 실제 진입하는 ReviewPage(/review/:id)는 메타 패널을 전혀 렌더하지
//   않아 검수자가 그 값을 볼 수 없었다. 읽기 전용 표시를 추가한다.
//
// ⚠ 2026-09-14 — 전문이 <b>창</b>으로 옮겨갔다(전제 변경, 회귀 아님). 메타 탭에는 요약 카드만
//   있고 「크게 보기」로 읽기 전용 창을 연다. 이 파일이 고정하는 것은 넷이다:
//   ① 요약 카드 → 창 열기  ② 창이 읽기 전용(입력·저장·추가·지정 없음)
//   ③ 근거 프레임 번호를 누르면 그 프레임으로 이동하고 창이 접힌다
//   ④ 이 영상에 없는 번호는 이동하지 않고 안내만 한다

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import MockAdapter from 'axios-mock-adapter';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

// jsdom 환경에서 konva 가 native canvas 모듈을 요구하므로 mock 으로 대체.
vi.mock('react-konva', async () => (await import('@/test/konvaMock')).createKonvaMock());

import { apiClient } from '@/lib/api/client';
import { renderWithProviders } from '@/test/renderWithProviders';
import { useReviewSelectionStore } from '@/features/review/store/useReviewSelectionStore';
import { useUiStore } from '@/stores/useUiStore';
import { ReviewPage } from '@/pages/ReviewPage';

const baseReview = {
  id: 10,
  videoId: 1,
  cctvName: 'CCTV-1',
  workerId: 7,
  workerName: '홍길동',
  submittedAt: '2026-05-07T01:30:00Z',
  labelCount: 12,
  status: 'REVIEWING',
};

const FRAME_SRC_SN = 501;
/** 근거가 가리키는 두 번째 프레임 — 이동이 실제로 일어났는지 보는 대상. */
const SECOND_SRC_SN = 502;
/** 이 영상에 없는 번호 — 이동하지 않고 안내만 해야 한다. */
const MISSING_SRC_SN = 999;

const frameList = {
  videoId: 1,
  totalFrames: 2,
  frames: [
    { srcSn: FRAME_SRC_SN, frameNo: 0, imageUrl: 'http://example.test/501.jpg', labels: [] },
    { srcSn: SECOND_SRC_SN, frameNo: 1, imageUrl: 'http://example.test/502.jpg', labels: [] },
  ],
};

function mockCommon(mock: MockAdapter) {
  mock.onGet('/reviews/10').reply(200, {
    success: true,
    data: baseReview,
    message: null,
    errorCode: null,
  });
  mock.onGet('/reviews/10/issues').reply(200, {
    success: true,
    data: [],
    message: null,
    errorCode: null,
  });
  mock.onGet('/reviews/1/frames').reply(200, {
    success: true,
    data: frameList,
    message: null,
    errorCode: null,
  });
  // 영상 상세 — 이벤트 분류 이름의 조달처(전체 검증 이벤트 유형 목록).
  mock.onGet('/videos/1').reply(200, {
    success: true,
    data: {
      rawSn: 1,
      id: 1,
      allVrfcEvntTypes: [{ vrfcEvntTypeCd: 'car_accident', vrfcEvntTypeNm: '교통사고' }],
    },
    message: null,
    errorCode: null,
  });
}

const FULL_ANNOTATION = {
  rawSn: 1,
  evntAnnoSn: 55,
  reviewStatus: 'APPROVED',
  regId: 'worker1',
  mdfcnId: null,
  payload: {
    event_class: 'car_accident',
    question: '무슨 일이 일어나고 있나요?',
    answer: '건물에서 연기가 피어오릅니다.',
    // ★후보 키를 <b>c1 · c3</b> 로 둔다(가운데를 지운 상태) — 이름의 번호가 저장 키에서 오는지,
    //   목록 순번에서 오는지를 이 픽스처만이 가른다. 키가 c1 하나뿐이면 「목록 첫째 = 1」과
    //   「키가 1」이 같은 값이라, 이름을 상수 '캡션 1' 로 굳히는 변이도 순번으로 다시 매기는
    //   변이도 <b>둘 다 통과한다</b>(QA 실측).
    caption: {
      c1: { caption_text: '연기가 올라온다', cot: ['연기 관찰', '화재 추정'] },
      c3: { caption_text: '불꽃이 보인다', cot: ['불꽃 관찰'] },
    },
    evidence: {
      c1: {
        evidence_text: '2번 객체에서 연기',
        frame_id: [SECOND_SRC_SN, MISSING_SRC_SN],
        obj_id: ['obj-2'],
        obj_label: ['smoke'],
        obj_bbox: [[10, 20, 30, 40]],
      },
      c3: {
        evidence_text: '3번 객체에서 불꽃',
        frame_id: [],
        obj_id: ['obj-3'],
        obj_label: ['fire'],
        obj_bbox: [[50, 60, 70, 80]],
      },
    },
  },
};

/** 검수 화면을 띄우고 우측 '메타' 탭을 연다(기본 탭은 '객체'다). */
async function renderReviewPage() {
  const user = userEvent.setup();
  renderWithProviders(<ReviewPage />, {
    initialEntries: ['/review/10'],
    routes: [{ path: '/review/:id', element: <ReviewPage /> }],
  });
  await user.click(await screen.findByTestId('review-tab-meta'));
  return user;
}

/** 메타 탭 → 요약 카드 → 읽기 전용 창. */
async function openWindow() {
  const user = await renderReviewPage();
  await user.click(await screen.findByTestId('annotation-summary-open'));
  await screen.findByTestId('annotation-window');
  return user;
}

describe('ReviewPage 영상 분석 설명 · 이벤트 어노테이션(읽기 전용)', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
    mockCommon(mock);
    useUiStore.setState({ toasts: [] });
    useReviewSelectionStore.getState().setCurrentFrameIdx(0);
  });

  afterEach(() => {
    mock.restore();
    useReviewSelectionStore.getState().setCurrentFrameIdx(0);
  });

  it('요약카드는_이벤트_분류를_이름과_코드로_보인다', async () => {
    mock.onGet('/videos/1/event-annotation').reply(200, {
      success: true,
      data: FULL_ANNOTATION,
      message: null,
      errorCode: null,
    });
    mock.onGet(/\/frames\/\d+\/meta/).reply(200, {
      success: true,
      data: { items: [{ metaSn: 1, metaKey: 'vlm.description', metaVal: '도로에서 충돌' }] },
      message: null,
      errorCode: null,
    });

    await renderReviewPage();

    const card = await screen.findByTestId('annotation-summary-card');
    await waitFor(() => expect(card).toHaveTextContent('교통사고'));
    expect(card).toHaveTextContent('car_accident');
    expect(card).toHaveTextContent('도로에서 충돌');
    // 검수 카드에는 검토 상태 행이 없다.
    expect(screen.queryByTestId('annotation-summary-review-status')).toBeNull();
  });

  it('창을_열면_값이_읽기_전용으로_보인다', async () => {
    mock.onGet('/videos/1/event-annotation').reply(200, {
      success: true,
      data: FULL_ANNOTATION,
      message: null,
      errorCode: null,
    });
    mock.onGet(/\/frames\/\d+\/meta/).reply(200, {
      success: true,
      data: { items: [{ metaSn: 1, metaKey: 'vlm.description', metaVal: '도로에서 충돌' }] },
      message: null,
      errorCode: null,
    });

    await openWindow();
    const win = screen.getByTestId('annotation-window');

    // 값이 보인다 — 분류·질의·답변·캡션·사고 단계·근거.
    await waitFor(() => expect(win).toHaveTextContent('도로에서 충돌'));
    expect(win).toHaveTextContent('무슨 일이 일어나고 있나요?');
    expect(win).toHaveTextContent('건물에서 연기가 피어오릅니다.');
    expect(win).toHaveTextContent('연기가 올라온다');
    expect(win).toHaveTextContent('연기 관찰');
    expect(win).toHaveTextContent('2번 객체에서 연기');
    expect(win).toHaveTextContent('obj-2');
    expect(win).toHaveTextContent('smoke');

    // ★후보 이름은 「캡션 1」·「근거 1」이고 저장 키 c1 을 흐리게 병기한다.
    //   ⚠ 부재 단언(입력 컨트롤이 없다)만으로는 이름이 「캡션」으로 뭉개지거나 저장 키 병기가
    //     사라져도 잡히지 않는다 — 존재 단언을 짝으로 둔다(읽기 전용 쪽에 가드가 없었다).
    expect(win).toHaveTextContent('캡션 1');
    expect(win).toHaveTextContent('근거 1');
    const captionName = within(win).getByText('캡션 1').parentElement;
    expect(captionName).toHaveTextContent('c1');
    const evidenceName = within(win).getByText('근거 1').parentElement;
    expect(evidenceName).toHaveTextContent('c1');

    // ★★번호는 <b>저장 키의 숫자</b>다 — 목록 순번으로 다시 매기지 않는다. 픽스처의 둘째 후보가
    //   키 c3 이므로 목록에서는 둘째이지만 이름은 「캡션 3」·「근거 3」이어야 한다.
    expect(within(win).getByText('캡션 3').parentElement).toHaveTextContent('c3');
    expect(within(win).getByText('근거 3').parentElement).toHaveTextContent('c3');
    expect(within(win).queryByText('캡션 2')).toBeNull();
    expect(within(win).queryByText('근거 2')).toBeNull();

    // ★읽기 전용 — 입력·저장·후보 추가/삭제·「화면에서 지정」 컨트롤을 그리지 않는다.
    expect(within(win).queryByRole('textbox')).toBeNull();
    expect(within(win).queryByRole('combobox')).toBeNull();
    expect(screen.queryByTestId('annotation-window-save-bar')).toBeNull();
    expect(screen.queryByTestId('annotation-window-save')).toBeNull();
    expect(screen.queryByTestId('ea-add-caption')).toBeNull();
    expect(screen.queryByTestId('ea-add-evidence')).toBeNull();
    expect(screen.queryByTestId('ea-evidence-pick-c1')).toBeNull();
    expect(screen.queryByTestId('ea-del-evidence-c1')).toBeNull();
    // 검토(승인·반려) 표면도 이 창에 두지 않는다 — 확정은 영상 검수 승인 시 자동이다.
    expect(screen.queryByTestId('ea-approve')).toBeNull();
    expect(screen.queryByTestId('ea-reject')).toBeNull();
    expect(screen.queryByTestId('ea-review-status')).toBeNull();
  });

  it('★영상_분석_설명에_검토_상태_배지가_없다', async () => {
    mock.onGet('/videos/1/event-annotation').reply(404, {
      success: false,
      data: null,
      message: '없음',
      errorCode: 'NOT_FOUND',
    });
    mock.onGet(/\/frames\/\d+\/meta/).reply(200, {
      success: true,
      data: {
        items: [
          {
            metaSn: 9001,
            metaKey: 'vlm.description',
            metaVal: '사람이 배회하고 있음',
            dataMetaReviewSn: 700,
            reviewStatus: 'APPROVED',
          },
        ],
      },
      message: null,
      errorCode: null,
    });

    await openWindow();
    const win = screen.getByTestId('annotation-window');

    await waitFor(() => expect(win).toHaveTextContent('사람이 배회하고 있음'));
    expect(within(win).queryByText('승인됨')).toBeNull();
    expect(within(win).queryByText('검토 대기')).toBeNull();
    expect(within(win).queryByText('검토 상태')).toBeNull();
  });

  it('★근거_프레임_번호를_누르면_그_프레임으로_이동하고_창이_접힌다', async () => {
    mock.onGet('/videos/1/event-annotation').reply(200, {
      success: true,
      data: FULL_ANNOTATION,
      message: null,
      errorCode: null,
    });
    mock.onGet(/\/frames\/\d+\/meta/).reply(200, {
      success: true,
      data: { items: [] },
      message: null,
      errorCode: null,
    });

    const user = await openWindow();
    expect(useReviewSelectionStore.getState().currentFrameIdx).toBe(0);

    // when — 근거에 적힌 두 번째 프레임 번호를 누른다.
    await user.click(
      await screen.findByTestId(`ea-evidence-frame-link-c1-${SECOND_SRC_SN}`),
    );

    // then — 뒤 화면이 그 프레임으로 이동하고 창은 접힌다(띠가 그 사실을 말한다).
    expect(useReviewSelectionStore.getState().currentFrameIdx).toBe(1);
    const strip = await screen.findByTestId('annotation-strip-evidence-jump');
    expect(strip).toHaveTextContent(`근거 1 · 프레임 ${SECOND_SRC_SN}`);
    expect(strip).toHaveTextContent('근거로 적힌 프레임으로 이동했습니다. 확인이 끝나면 창을 펼치세요.');
    expect(screen.getByTestId('annotation-window')).not.toBeVisible();

    // when — 「펼치기」
    await user.click(within(strip).getByRole('button', { name: '펼치기' }));

    // then — 창이 돌아오고 띠는 사라진다(값은 그대로다 — 창은 언마운트되지 않았다).
    expect(screen.getByTestId('annotation-window')).toBeVisible();
    expect(screen.queryByTestId('annotation-strip-evidence-jump')).toBeNull();
    expect(screen.getByTestId('annotation-window')).toHaveTextContent('2번 객체에서 연기');
  });

  it('★근거_필드_이름은_짧은_한_벌이고_프레임은_번호마다_별개_칩이다', async () => {
    mock.onGet('/videos/1/event-annotation').reply(200, {
      success: true,
      data: FULL_ANNOTATION,
      message: null,
      errorCode: null,
    });
    mock.onGet(/\/frames\/\d+\/meta/).reply(200, {
      success: true,
      data: { items: [] },
      message: null,
      errorCode: null,
    });

    await openWindow();
    const win = screen.getByTestId('annotation-window');

    // ★필드 이름은 「프레임」·「객체 박스」다. 구 이름(「프레임 번호」·「객체 박스 좌표」)은
    //   읽기 전용에만 남아 있던 구현 잔재이며, 편집 화면과 한 벌이어야 한다.
    // (근거 후보가 둘이라 각 이름도 둘씩 선다 — 개수가 아니라 이름이 이 케이스의 축이다.)
    await waitFor(() => expect(within(win).getAllByText('프레임')).toHaveLength(2));
    expect(within(win).getAllByText('객체 박스')).toHaveLength(2);
    expect(within(win).queryByText('프레임 번호')).toBeNull();
    expect(within(win).queryByText('객체 박스 좌표')).toBeNull();

    // ★「프레임」은 칩들 <b>앞에 한 번만</b> 둔다 — 칩 안에 되풀이하지 않는다.
    const chip = within(win).getByTestId(`ea-evidence-frame-link-c1-${SECOND_SRC_SN}`);
    expect(chip).not.toHaveTextContent('프레임');

    // ★번호마다 별개 칩이다 — 한 덩어리로 묶으면 눌렀을 때 어느 프레임으로 가는지 알 수 없다.
    //   두 번호가 각각 자기 칩을 갖는지 본다(둘을 합친 구현이면 한쪽이 없다).
    expect(chip).toHaveTextContent(String(SECOND_SRC_SN));
    expect(chip).not.toHaveTextContent(String(MISSING_SRC_SN));
    const missing = within(win).getByTestId(`ea-evidence-frame-link-c1-${MISSING_SRC_SN}`);
    expect(missing).not.toHaveTextContent(String(SECOND_SRC_SN));

    // ★이동 표식(↗)은 누를 수 있는 칩에만 둔다 — 없는 번호와 눈으로 갈리는 축이다.
    //   ⚠ 표식만으로 정보를 전달하지 않으므로, 이동할 수 있다는 사실은 접근성 이름이 말한다.
    expect(chip).toHaveTextContent('↗');
    expect(missing).not.toHaveTextContent('↗');
    expect(chip).toHaveAttribute('aria-label', `근거 1 프레임 ${SECOND_SRC_SN} 으로 이동`);
    expect(missing).toHaveAttribute(
      'aria-label',
      `근거 1 프레임 ${MISSING_SRC_SN} — 이 영상에 없음`,
    );
  });

  it('★요약_카드는_왜_여기에_요약만_두는지_알리는_보조_설명을_함께_보인다', async () => {
    mock.onGet('/videos/1/event-annotation').reply(200, {
      success: true,
      data: FULL_ANNOTATION,
      message: null,
      errorCode: null,
    });
    mock.onGet(/\/frames\/\d+\/meta/).reply(200, {
      success: true,
      data: { items: [] },
      message: null,
      errorCode: null,
    });

    await renderReviewPage();

    // ★기본은 <b>감춤</b>이다(2026-09-15 사용자 확정) — 좁은 메타 탭에서 설명 두 문장이 값을
    //   아래로 밀어냈다. 「보인다」만 단언하면 기본값이 조용히 뒤집혀도 통과하므로 양방향을 둘 다 센다.
    await screen.findByTestId('annotation-summary-card');
    expect(screen.queryByTestId('annotation-summary-hint')).toBeNull();

    // when — 패널 머리의 도움말 토글 하나가 메타 탭 설명문을 한꺼번에 편다.
    const toggle = screen.getByTestId('meta-help-toggle');
    expect(toggle).toHaveAttribute('aria-pressed', 'false');
    await userEvent.click(toggle);

    const hint = await screen.findByTestId('annotation-summary-hint');
    // ★검수 변형 — 창이 읽기 전용이고 버튼 문구가 「크게 보기」라 두 번째 문장이 라벨링과 갈린다.
    expect(hint.textContent).toBe(
      '좁은 탭에서 읽기 어려워 여기에는 간추린 값만 둡니다. 전문은 「크게 보기」로 여는 읽기 전용 창에서 확인합니다.',
    );
    expect(hint).not.toHaveTextContent('보고 고칩니다');
    expect(screen.getByTestId('meta-help-toggle')).toHaveAttribute('aria-pressed', 'true');
  });

  it('★이_영상에_없는_프레임_번호는_이동하지_않고_안내만_한다', async () => {
    mock.onGet('/videos/1/event-annotation').reply(200, {
      success: true,
      data: FULL_ANNOTATION,
      message: null,
      errorCode: null,
    });
    mock.onGet(/\/frames\/\d+\/meta/).reply(200, {
      success: true,
      data: { items: [] },
      message: null,
      errorCode: null,
    });

    const user = await openWindow();

    // ★없는 번호는 <b>글자로도</b> 구분된다 — 흐린 색만으로 가르면 색을 구분하지 못하는
    //   사용자에게는 눌러 보기 전까지 같은 칩이다.
    const missingChip = await screen.findByTestId(
      `ea-evidence-frame-link-c1-${MISSING_SRC_SN}`,
    );
    expect(missingChip).toHaveTextContent('이 영상에 없음');
    expect(
      screen.getByTestId(`ea-evidence-frame-link-c1-${SECOND_SRC_SN}`),
    ).not.toHaveTextContent('이 영상에 없음');

    await user.click(missingChip);

    // then — 프레임은 그대로고 창도 접히지 않는다. 안내만 뜬다.
    expect(useReviewSelectionStore.getState().currentFrameIdx).toBe(0);
    expect(screen.getByTestId('annotation-window')).toBeVisible();
    expect(screen.queryByTestId('annotation-strip-evidence-jump')).toBeNull();
    await waitFor(() =>
      expect(
        useUiStore
          .getState()
          .toasts.some((t) =>
            t.message.includes(`${MISSING_SRC_SN} — 이 영상에 없는 프레임 번호라 이동하지 않았습니다.`),
          ),
      ).toBe(true),
    );
  });

  it('메타_0건_크래시없이_빈상태_렌더', async () => {
    mock.onGet('/videos/1/event-annotation').reply(404, {
      success: false,
      data: null,
      message: '없음',
      errorCode: 'NOT_FOUND',
    });
    mock.onGet(/\/frames\/\d+\/meta/).reply(200, {
      success: true,
      data: { items: [] },
      message: null,
      errorCode: null,
    });

    await renderReviewPage();

    // 크래시 없이 패널 자체는 렌더되고 빈 상태 안내가 뜬다.
    const panel = await screen.findByTestId('review-meta-panel');
    expect(within(panel).getByTestId('review-meta-empty')).toBeInTheDocument();
    // 값이 없어도 창을 여는 입구는 남는다.
    expect(screen.getByTestId('annotation-summary-open')).toBeInTheDocument();
  });
});
