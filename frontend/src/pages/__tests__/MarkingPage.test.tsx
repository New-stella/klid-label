import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import MockAdapter from 'axios-mock-adapter';
import { act, fireEvent, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { apiClient } from '@/lib/api/client';
import { MarkingPage } from '@/pages/MarkingPage';
import { renderWithProviders } from '@/test/renderWithProviders';
import { useAuthStore } from '@/stores/useAuthStore';
import { useUiStore } from '@/stores/useUiStore';
import { useMarkingStore } from '@/features/marking/store';

const navigateMock = vi.fn();

vi.mock('react-router-dom', async () => {
  const actual = await vi.importActual<typeof import('react-router-dom')>(
    'react-router-dom',
  );
  return {
    ...actual,
    useNavigate: () => navigateMock,
    useParams: () => ({ rawSn: '42' }),
  };
});

// VideoPlayer 는 jsdom 에서 <video> 미지원 — stub 처리.
// src prop 을 data-src 로 노출해 서명 URL 주입을 단언한다.
vi.mock('@/features/marking/components/VideoPlayer', () => ({
  VideoPlayer: vi.fn(({ src }: { src: string }) => (
    <div data-testid="video-player-stub" data-src={src} />
  )),
}));

function setRole(role: 'REVIEWER' | 'WORKER', sub = 'u-7') {
  useAuthStore.setState({
    token: 'sample-token',
    claims: { sub, role, channel: 'INTERNAL', exp: 9999999999 },
  });
}

describe('MarkingPage', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
    navigateMock.mockReset();
    useMarkingStore.getState().reset();
    // 기존 토스트 비우기
    useUiStore.setState({ toasts: [] });
    // <video> 서명 URL 발급 mock — 모든 테스트 공통.
    mock.onGet(/\/videos\/42\/stream-url/).reply(200, {
      url: '/api/v1/videos/42/stream?exp=9999999999&sig=testsig',
      expiresAt: 9999999999,
      ttlSeconds: 60,
    });
  });

  afterEach(() => {
    mock.restore();
    useAuthStore.getState().clear();
  });

  it('마킹_제출_성공시_토스트_표시_및_task_목록으로_이동', async () => {
    // given: WORKER 로그인 + 마킹 저장 API mock
    setRole('WORKER');
    mock.onGet(/\/videos\/42\/markings/).reply(200, []);
    let postBody: Record<string, unknown> = {};
    mock.onPost(/\/videos\/42\/markings/).reply((config) => {
      postBody = JSON.parse(config.data ?? '{}');
      return [
        200,
        {
          markingSn: 1,
          rawSn: 42,
          eventName: '화재',
          markingMode: 'AUTO',
          intervalFrames: 5,
          videoPath: '/path/to/video',
          marks: [],
          status: 'PENDING',
          createdAt: '2026-05-27T10:00:00Z',
        },
      ];
    });

    renderWithProviders(<MarkingPage />, {
      initialEntries: ['/marking/42'],
    });

    await waitFor(() => {
      expect(screen.getByText(/마킹 — 영상 #42/)).toBeInTheDocument();
    });

    // when: "자동" 모드에서 제출 (이벤트명 입력 없음 — evntTypeCd 자동 소싱)
    const user = userEvent.setup();
    await user.click(screen.getByText('자동'));

    // "마킹 완료" 버튼 클릭
    const submitBtn = screen.getByRole('button', { name: /마킹 완료/ });
    await user.click(submitBtn);

    // then: 토스트 메시지가 UI store 에 추가되어야 한다
    await waitFor(() => {
      const toasts = useUiStore.getState().toasts;
      expect(toasts.some((t) => t.variant === 'success' && t.message.includes('마킹'))).toBe(true);
    });

    // then: /task 로 navigate 되어야 한다
    await waitFor(() => {
      expect(navigateMock).toHaveBeenCalledWith('/task');
    });

    // then(I2): 요청 페이로드는 BE 계약(MarkingRequest.mode)에 맞춰 `mode` 필드여야 한다.
    // 과거엔 `markingMode` 를 보내 BE @NotBlank mode 검증에 걸려 항상 400 이 발생했다.
    expect(postBody.mode).toBe('AUTO');
    expect(postBody).not.toHaveProperty('markingMode');
    // 이벤트명은 영상의 evntTypeCd 에서 서버가 자동 소싱하므로 요청 페이로드에 없어야 한다.
    expect(postBody).not.toHaveProperty('eventName');
  });

  it('I2_수동모드_제출시_요청_페이로드_mode_MANUAL_marks_직렬화', async () => {
    // given: WORKER 로그인 + 마킹 저장 API mock
    setRole('WORKER');
    mock.onGet(/\/videos\/42\/markings/).reply(200, []);
    let postBody: Record<string, unknown> = {};
    mock.onPost(/\/videos\/42\/markings/).reply((config) => {
      postBody = JSON.parse(config.data ?? '{}');
      return [
        200,
        {
          markingSn: 2,
          rawSn: 42,
          eventName: '침입',
          markingMode: 'MANUAL',
          intervalFrames: null,
          videoPath: '/path/to/video',
          marks: [{ frameIndex: 0, timestamp: '00:00' }],
          status: 'PENDING',
          createdAt: '2026-05-27T10:00:00Z',
        },
      ];
    });

    renderWithProviders(<MarkingPage />, { initialEntries: ['/marking/42'] });

    await waitFor(() => {
      expect(screen.getByText(/마킹 — 영상 #42/)).toBeInTheDocument();
    });

    // when: 수동 모드 + 마킹 1건 추가 후 제출 (이벤트명 입력 없음 — 자동 소싱)
    const user = userEvent.setup();
    await user.click(screen.getByText('수동'));
    // 수동 마킹 1건 추가 (스토어 직접 주입 — VideoPlayer stub 으로 키 이벤트 경로 우회)
    useMarkingStore.getState().addMark({ frameIndex: 0, timestamp: '00:00' });

    const submitBtn = screen.getByRole('button', { name: /마킹 완료/ });
    await user.click(submitBtn);

    // then(I2): 요청 페이로드 mode='MANUAL', marks 직렬화, markingMode 부재
    await waitFor(() => {
      expect(postBody.mode).toBe('MANUAL');
    });
    expect(postBody).not.toHaveProperty('markingMode');
    expect(postBody).not.toHaveProperty('eventName');
    expect(postBody.marks).toEqual([{ frameIndex: 0, timestamp: '00:00' }]);
  });

  it('이벤트유형없는영상_400응답시_에러토스트_표시_및_이동안함', async () => {
    // given: WORKER 로그인 + 마킹 저장 API 가 400(INVALID_INPUT) 반환
    // (evntTypeCd 가 null/blank 인 영상에서 BE 가 내려주는 응답)
    setRole('WORKER');
    mock.onGet(/\/videos\/42\/markings/).reply(200, []);
    mock.onPost(/\/videos\/42\/markings/).reply(400, {
      success: false,
      data: null,
      message: '이벤트 유형이 지정되지 않은 영상입니다.',
      errorCode: 'INVALID_INPUT',
    });

    renderWithProviders(<MarkingPage />, { initialEntries: ['/marking/42'] });

    await waitFor(() => {
      expect(screen.getByText(/마킹 — 영상 #42/)).toBeInTheDocument();
    });

    // when: 자동 모드 제출 → BE 400
    const user = userEvent.setup();
    await user.click(screen.getByText('자동'));
    await user.click(screen.getByRole('button', { name: /마킹 완료/ }));

    // then: 에러 토스트(variant:'error')가 추가되어야 한다 (BE 메시지 노출)
    await waitFor(() => {
      const toasts = useUiStore.getState().toasts;
      expect(
        toasts.some(
          (t) => t.variant === 'error' && t.message.includes('이벤트 유형'),
        ),
      ).toBe(true);
    });

    // then: 실패 시 /task 로 이동하지 않는다
    expect(navigateMock).not.toHaveBeenCalled();
  });

  it('저장된_마킹_목록이_렌더되지_않는다', async () => {
    // given: WORKER 로그인 — 마킹 목록(MarkingList) 은 더 이상 화면에 표시되지 않는다.
    setRole('WORKER');

    renderWithProviders(<MarkingPage />, { initialEntries: ['/marking/42'] });

    await waitFor(() => {
      expect(screen.getByText(/마킹 — 영상 #42/)).toBeInTheDocument();
    });

    // then: "저장된 마킹" 섹션 헤더가 없어야 한다.
    expect(screen.queryByText('저장된 마킹')).not.toBeInTheDocument();
    expect(screen.queryByText('저장된 마킹이 없습니다.')).not.toBeInTheDocument();
  });

  // ============================================================
  // 마킹 칩 개별 삭제 (UI-045 MarkingPanel — onRemoveMark)
  //   구 구현은 삭제 수단이 Del/Backspace 단축키뿐이라 <b>마우스만 쓰는 사용자는 개별
  //   마킹을 지울 수 없었다</b>. 칩마다 삭제 버튼을 두되 단축키는 그대로 유지한다.
  // ============================================================

  async function renderWithMarks(marks: { frameIndex: number; timestamp: string }[]) {
    setRole('WORKER');
    mock.onGet(/\/videos\/42\/markings/).reply(200, []);
    renderWithProviders(<MarkingPage />, { initialEntries: ['/marking/42'] });
    await waitFor(() => {
      expect(screen.getByText(/마킹 — 영상 #42/)).toBeInTheDocument();
    });
    act(() => {
      useMarkingStore.getState().setMode('MANUAL');
      marks.forEach((m) => useMarkingStore.getState().addMark(m));
    });
  }

  it('마킹칩마다_어느마킹인지_알수있는_삭제버튼이_있다', async () => {
    // given: 수동 마킹 2건
    await renderWithMarks([
      { frameIndex: 30, timestamp: '00:01' },
      { frameIndex: 90, timestamp: '00:03' },
    ]);

    // then: 각 칩에 <b>실제 button</b> 이 있고, 접근성 이름으로 대상 마킹을 구분할 수 있다.
    //   아이콘만 있는 버튼에 이름이 없으면 스크린리더에 "버튼"으로만 읽힌다.
    const del30 = screen.getByRole('button', { name: '마킹 삭제 F30·00:01' });
    const del90 = screen.getByRole('button', { name: '마킹 삭제 F90·00:03' });
    expect(del30.tagName).toBe('BUTTON');
    expect(del90.tagName).toBe('BUTTON');
  });

  it('삭제버튼_클릭시_그_마킹만_제거된다', async () => {
    // given: 수동 마킹 3건
    await renderWithMarks([
      { frameIndex: 30, timestamp: '00:01' },
      { frameIndex: 90, timestamp: '00:03' },
      { frameIndex: 150, timestamp: '00:05' },
    ]);

    // when: 가운데 마킹의 삭제 버튼만 클릭 (선택 조작 없이 곧바로 삭제 가능해야 한다)
    const user = userEvent.setup();
    await user.click(screen.getByRole('button', { name: '마킹 삭제 F90·00:03' }));

    // then: 그 마킹만 사라지고 나머지는 남는다.
    await waitFor(() => {
      expect(useMarkingStore.getState().localMarks.map((m) => m.frameIndex)).toEqual([30, 150]);
    });
    expect(
      screen.queryByRole('button', { name: '마킹 삭제 F90·00:03' }),
    ).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: '마킹 삭제 F30·00:01' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '마킹 삭제 F150·00:05' })).toBeInTheDocument();
  });

  it('삭제버튼_추가후에도_Del단축키_삭제가_동작한다', async () => {
    // given: 수동 마킹 2건 — 삭제 버튼은 단축키의 <b>대체가 아니라 추가</b>다.
    await renderWithMarks([
      { frameIndex: 30, timestamp: '00:01' },
      { frameIndex: 90, timestamp: '00:03' },
    ]);
    act(() => {
      useMarkingStore.getState().selectMark(0);
    });

    // when: Delete 키
    fireEvent.keyDown(window, { code: 'Delete' });

    // then: 선택된 마킹이 제거된다.
    await waitFor(() => {
      expect(useMarkingStore.getState().localMarks.map((m) => m.frameIndex)).toEqual([90]);
    });

    // when: Backspace 로도 동일하게 동작한다.
    act(() => {
      useMarkingStore.getState().selectMark(0);
    });
    fireEvent.keyDown(window, { code: 'Backspace' });
    await waitFor(() => {
      expect(useMarkingStore.getState().localMarks).toHaveLength(0);
    });
  });

  it('비식별_미완료_영상_직접진입시_마킹차단_백스톱_안내', async () => {
    // given: WORKER 가 URL 직접 진입. 영상 상세가 비식별 미완료(deIdntfYn!=='Y').
    setRole('WORKER');
    mock.onGet(/\/videos\/42\/markings/).reply(200, []);
    mock.onGet('/videos/42').reply(200, {
      success: true,
      data: {
        id: 42,
        rawSn: 42,
        cctvName: 'CCTV-42',
        deIdntfYn: 'N',
        deidentStatus: 'IN_PROGRESS',
      },
      message: null,
      errorCode: null,
    });

    // when: 마킹 화면 진입
    renderWithProviders(<MarkingPage />, { initialEntries: ['/marking/42'] });

    // then: 차단 안내가 노출되고 영상 플레이어/툴바는 렌더되지 않는다(백스톱).
    await waitFor(() => {
      expect(screen.getByText(/비식별 완료 후 마킹/)).toBeInTheDocument();
    });
    expect(screen.queryByTestId('video-player-stub')).not.toBeInTheDocument();
  });

  it('비식별_완료_영상_직접진입시_마킹화면_정상_렌더', async () => {
    // given: 비식별 완료(deIdntfYn='Y') 영상 — 백스톱이 막지 않는다.
    setRole('WORKER');
    mock.onGet(/\/videos\/42\/markings/).reply(200, []);
    mock.onGet('/videos/42').reply(200, {
      success: true,
      data: {
        id: 42,
        rawSn: 42,
        cctvName: 'CCTV-42',
        deIdntfYn: 'Y',
        deidentStatus: 'DONE',
      },
      message: null,
      errorCode: null,
    });

    renderWithProviders(<MarkingPage />, { initialEntries: ['/marking/42'] });

    await waitFor(() => {
      expect(screen.getByText(/마킹 — 영상 #42/)).toBeInTheDocument();
    });
    expect(screen.queryByText(/비식별 완료 후 마킹/)).not.toBeInTheDocument();
  });

  it('마킹_제출_pending_중_Enter_재호출시_추가_POST_미발생', async () => {
    // given: WORKER 로그인. POST 는 응답을 지연(never-resolve)시켜 mutation 을 pending 상태로 고정.
    setRole('WORKER');
    mock.onGet(/\/videos\/42\/markings/).reply(200, []);
    let postCount = 0;
    mock.onPost(/\/videos\/42\/markings/).reply(() => {
      postCount += 1;
      // 응답을 확정하지 않아 createMutation.isPending 이 계속 true 로 유지된다.
      return new Promise(() => {});
    });

    renderWithProviders(<MarkingPage />, { initialEntries: ['/marking/42'] });
    await waitFor(() => {
      expect(screen.getByText(/마킹 — 영상 #42/)).toBeInTheDocument();
    });

    // 수동 모드 + 마킹 1건 (Enter 제출 경로 활성 조건).
    act(() => {
      useMarkingStore.getState().setMode('MANUAL');
      useMarkingStore.getState().addMark({ frameIndex: 3, timestamp: '00:00' });
    });

    // when: 첫 Enter → 마킹 POST 발화 후 pending 유지.
    fireEvent.keyDown(window, { code: 'Enter' });
    await waitFor(() => expect(postCount).toBe(1));

    // when: pending 중 Enter 재입력 (연타 시뮬레이션).
    fireEvent.keyDown(window, { code: 'Enter' });
    // 재렌더/리스너 재등록 이후에도 추가 POST 가 발생하지 않아야 한다.
    await new Promise((r) => setTimeout(r, 50));

    // then: 중복 제출 가드로 POST 는 여전히 1회.
    expect(postCount).toBe(1);
  });

  it('I3_재생전_서명URL_발급후_video_src에_서명URL_설정', async () => {
    // given: WORKER 로그인 + stream-url 발급 mock (beforeEach)
    setRole('WORKER');
    mock.onGet(/\/videos\/42\/markings/).reply(200, []);

    // when: 마킹 화면 진입
    renderWithProviders(<MarkingPage />, { initialEntries: ['/marking/42'] });

    // then: VideoPlayer 의 src 에 서명 URL(exp/sig 쿼리 포함)이 주입되어야 한다.
    // 과거엔 src=`/api/v1/videos/42/stream` (서명 없음) → 401 검정화면이었다.
    await waitFor(() => {
      const stub = screen.getByTestId('video-player-stub');
      expect(stub.getAttribute('data-src')).toBe(
        '/api/v1/videos/42/stream?exp=9999999999&sig=testsig',
      );
    });
  });

  // ============================================================
  // V171 — 마킹 화면 비식별 누락 신고 버튼 (신설)
  // ============================================================

  function stubVideoDetail(extra: Record<string, unknown>) {
    mock.onGet('/videos/42').reply(200, {
      success: true,
      data: {
        id: 42,
        rawSn: 42,
        cctvName: 'CCTV-42',
        deIdntfYn: 'Y',
        deidentStatus: 'DONE',
        status: 'MARKING_READY',
        ...extra,
      },
      message: null,
      errorCode: null,
    });
  }

  it('마킹화면_신고버튼이_영상단위_API를_호출한다', async () => {
    // given — 마킹 대기(MARKING_READY) 상태의 비파생 영상.
    setRole('WORKER');
    mock.onGet(/\/videos\/42\/markings/).reply(200, []);
    stubVideoDetail({ derivative: false });
    let postedUrl: string | undefined;
    let postedBody: Record<string, unknown> = {};
    mock.onPost('/videos/42/deident-report').reply((config) => {
      postedUrl = config.url;
      postedBody = JSON.parse(config.data ?? '{}');
      return [201, { success: true, data: 7, message: null, errorCode: null }];
    });

    renderWithProviders(<MarkingPage />, { initialEntries: ['/marking/42'] });
    const button = await screen.findByTestId('deident-report-button');
    await waitFor(() => expect(button).not.toBeDisabled());

    // when — 신고 모달에서 사유 입력 후 제출
    fireEvent.click(button);
    const form = await screen.findByTestId('deident-report-form');
    fireEvent.change(form.querySelector('textarea')!, {
      target: { value: '00:12 부근 얼굴 블러 누락' },
    });
    await waitFor(() =>
      expect(screen.getByTestId('deident-report-submit')).not.toBeDisabled(),
    );
    fireEvent.click(screen.getByTestId('deident-report-submit'));

    // then — 라벨(프레임) 경로가 아니라 <b>영상 단위</b> 경로로 나간다.
    await waitFor(() => expect(postedUrl).toBe('/videos/42/deident-report'));
    expect(postedBody.reason).toBe('00:12 부근 얼굴 블러 누락');
    expect(
      mock.history.post.filter((r) => (r.url ?? '').includes('/labels/')),
    ).toHaveLength(0);
  });

  it('파생영상이면_신고버튼이_비활성_사유툴팁과_함께_노출된다', async () => {
    setRole('WORKER');
    mock.onGet(/\/videos\/42\/markings/).reply(200, []);
    stubVideoDetail({ derivative: true });

    renderWithProviders(<MarkingPage />, { initialEntries: ['/marking/42'] });

    const button = await screen.findByTestId('deident-report-button');
    await waitFor(() => expect(button).toBeDisabled());
    expect(button.getAttribute('title')).toContain('파생영상');
    // 사유를 다 적은 뒤에야 거부되는 동선을 없앤다 — 모달 자체가 열리지 않는다.
    fireEvent.click(button);
    expect(screen.queryByTestId('deident-report-form')).not.toBeInTheDocument();
  });

  it('마킹단계가_아니면_신고버튼이_비활성화된다', async () => {
    // given — 이미 배치가 돈 영상(COMPLETED). 서버는 412 로 거부하므로 미리 막는다.
    setRole('WORKER');
    mock.onGet(/\/videos\/42\/markings/).reply(200, []);
    stubVideoDetail({ derivative: false, status: 'COMPLETED' });

    renderWithProviders(<MarkingPage />, { initialEntries: ['/marking/42'] });

    const button = await screen.findByTestId('deident-report-button');
    await waitFor(() => expect(button).toBeDisabled());
    expect(button.getAttribute('title')).toContain('라벨링 화면');
  });
});
