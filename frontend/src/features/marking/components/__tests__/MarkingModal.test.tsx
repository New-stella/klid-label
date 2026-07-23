import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { renderWithProviders } from '@/test/renderWithProviders';
import { useUiStore } from '@/stores/useUiStore';

import { MarkingModal } from '../MarkingModal';
import type { MarkingRequest, MarkingResponse } from '../../types';

// ── useCreateMarking mock ──────────────────────────────────────────────
// 컴포넌트가 훅에 넘긴 onSuccess/onError 콜백을 가로채서 성공/실패를 시뮬레이션한다.
type HookOptions = {
  onSuccess?: (data: MarkingResponse) => void;
  onError?: (err: unknown) => void;
};
let capturedOptions: HookOptions | undefined;
const mutateMock = vi.fn<[MarkingRequest], void>();
// isPending 을 케이스별로 토글 — 진행중(true)이면 제출/입력이 비활성화되어야 한다.
let mockIsPending = false;

vi.mock('../../hooks/useMarkings', () => ({
  useCreateMarking: (_rawSn: number | undefined, options?: HookOptions) => {
    capturedOptions = options;
    return { mutate: mutateMock, isPending: mockIsPending };
  },
}));

// ── AssignModal stub ───────────────────────────────────────────────────
// 실제 배정 API/유저 조회를 격리. props(mode/videoId) 노출 + onSuccess 트리거 버튼 제공.
vi.mock('@/features/task/components/AssignModal', () => ({
  AssignModal: ({
    open,
    mode,
    videoId,
    onSuccess,
  }: {
    open: boolean;
    mode: string;
    videoId?: number;
    onSuccess?: () => void;
  }) =>
    open ? (
      <div
        data-testid="assign-modal-stub"
        data-mode={mode}
        data-video-id={String(videoId ?? '')}
      >
        <button type="button" onClick={() => onSuccess?.()}>
          배정완료-stub
        </button>
      </div>
    ) : null,
}));

function makeResponse(): MarkingResponse {
  return {
    markingSn: 1,
    rawSn: 42,
    eventName: '화재',
    markingMode: 'AUTO',
    intervalFrames: 5,
    videoPath: '/path',
    marks: [],
    status: 'PENDING',
    createdAt: '2026-07-16T00:00:00Z',
  };
}

describe('MarkingModal', () => {
  beforeEach(() => {
    mutateMock.mockReset();
    capturedOptions = undefined;
    mockIsPending = false;
    useUiStore.setState({ toasts: [] });
  });

  afterEach(() => {
    vi.clearAllMocks();
  });

  it('자동_선택시_프레임간격_입력폼이_표시된다', async () => {
    // given: 모달 오픈
    const user = userEvent.setup();
    renderWithProviders(
      <MarkingModal rawSn={42} videoName="CCTV-42" open onClose={vi.fn()} />,
    );

    // when: 자동 선택
    await user.click(screen.getByText('자동'));

    // then: 프레임 간격 라벨/입력이 노출
    expect(screen.getByLabelText(/프레임 간격/)).toBeInTheDocument();
  });

  it('마킹모달_초기_프레임간격_300_프리필', async () => {
    // given: 모달 오픈
    const user = userEvent.setup();
    renderWithProviders(<MarkingModal rawSn={42} open onClose={vi.fn()} />);

    // when: 자동 단계 진입
    await user.click(screen.getByText('자동'));

    // then: 입력창에 스토어 기본값(300)이 프리필되어 있다
    expect(screen.getByLabelText(/프레임 간격/)).toHaveValue('300');
  });

  it('마킹모달_닫았다_열어도_300_유지', async () => {
    // given: 자동 단계에서 값을 임의로 변경
    const user = userEvent.setup();
    const { rerender } = renderWithProviders(
      <MarkingModal rawSn={42} open onClose={vi.fn()} />,
    );
    await user.click(screen.getByText('자동'));
    const input = screen.getByLabelText(/프레임 간격/);
    await user.clear(input);
    await user.type(input, '10');
    expect(screen.getByLabelText(/프레임 간격/)).toHaveValue('10');

    // when: 닫았다가(open=false) 다시 열기(open=true) → 자동 재진입
    rerender(<MarkingModal rawSn={42} open={false} onClose={vi.fn()} />);
    rerender(<MarkingModal rawSn={42} open onClose={vi.fn()} />);
    await user.click(screen.getByText('자동'));

    // then: 다시 기본값 300 으로 프리필(이전 입력 잔존 없음)
    expect(screen.getByLabelText(/프레임 간격/)).toHaveValue('300');
  });

  it('프레임간격_미입력_또는_0이하면_제출_차단_또는_검증메시지', async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <MarkingModal rawSn={42} open onClose={vi.fn()} />,
    );
    await user.click(screen.getByText('자동'));

    const submit = screen.getByRole('button', { name: /자동 마킹 시작/ });
    const input = screen.getByLabelText(/프레임 간격/);

    // 빈값 → 제출 차단 + alert (기본 프리필 300 을 지운 상태)
    await user.clear(input);
    await user.click(submit);
    expect(mutateMock).not.toHaveBeenCalled();
    expect(screen.getByRole('alert')).toBeInTheDocument();

    // 0 → 차단
    await user.type(input, '0');
    await user.click(submit);
    expect(mutateMock).not.toHaveBeenCalled();

    // 음수 → 차단
    await user.clear(input);
    await user.type(input, '-3');
    await user.click(submit);
    expect(mutateMock).not.toHaveBeenCalled();

    // 비정수 → 차단
    await user.clear(input);
    await user.type(input, '1.5');
    await user.click(submit);
    expect(mutateMock).not.toHaveBeenCalled();
  });

  it('유효한_프레임간격_제출시_createMarking을_AUTO모드로_호출한다', async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <MarkingModal rawSn={42} open onClose={vi.fn()} />,
    );
    await user.click(screen.getByText('자동'));

    await user.clear(screen.getByLabelText(/프레임 간격/));
    await user.type(screen.getByLabelText(/프레임 간격/), '5');
    await user.click(screen.getByRole('button', { name: /자동 마킹 시작/ }));

    expect(mutateMock).toHaveBeenCalledTimes(1);
    expect(mutateMock).toHaveBeenCalledWith({ mode: 'AUTO', intervalFrames: 5 });
    // eventName 은 서버가 자동 소싱 — 요청에 넣지 않는다.
    const body = mutateMock.mock.calls[0]![0];
    expect(body).not.toHaveProperty('eventName');
  });

  it('큰_프레임간격_정수도_상한없이_유효처리되어_mutate호출된다', async () => {
    // 상한 미검증은 의도된 동작(BE 백스톱). 큰 정수 문자열도 그대로 전달됨을 고정한다.
    const user = userEvent.setup();
    renderWithProviders(<MarkingModal rawSn={42} open onClose={vi.fn()} />);
    await user.click(screen.getByText('자동'));

    await user.clear(screen.getByLabelText(/프레임 간격/));
    await user.type(screen.getByLabelText(/프레임 간격/), '100000');
    await user.click(screen.getByRole('button', { name: /자동 마킹 시작/ }));

    expect(mutateMock).toHaveBeenCalledTimes(1);
    expect(mutateMock).toHaveBeenCalledWith({ mode: 'AUTO', intervalFrames: 100000 });
  });

  it('자동마킹_성공시_onMarked_콜백과_닫기가_호출된다', async () => {
    const onMarked = vi.fn();
    const onClose = vi.fn();
    // 성공 시뮬레이션: 컴포넌트가 훅에 넘긴 onSuccess 를 호출
    mutateMock.mockImplementation(() => capturedOptions?.onSuccess?.(makeResponse()));

    const user = userEvent.setup();
    renderWithProviders(
      <MarkingModal rawSn={42} open onClose={onClose} onMarked={onMarked} />,
    );
    await user.click(screen.getByText('자동'));
    await user.type(screen.getByLabelText(/프레임 간격/), '5');
    await user.click(screen.getByRole('button', { name: /자동 마킹 시작/ }));

    await waitFor(() => expect(onMarked).toHaveBeenCalledTimes(1));
    expect(onClose).toHaveBeenCalledTimes(1);
    const toasts = useUiStore.getState().toasts;
    expect(toasts.some((t) => t.variant === 'success')).toBe(true);
  });

  it('자동마킹_실패시_에러_토스트를_표시한다', async () => {
    const onMarked = vi.fn();
    const onClose = vi.fn();
    mutateMock.mockImplementation(() =>
      capturedOptions?.onError?.({
        response: { data: { message: '이벤트 유형이 지정되지 않은 영상입니다.' } },
      }),
    );

    const user = userEvent.setup();
    renderWithProviders(
      <MarkingModal rawSn={42} open onClose={onClose} onMarked={onMarked} />,
    );
    await user.click(screen.getByText('자동'));
    await user.type(screen.getByLabelText(/프레임 간격/), '5');
    await user.click(screen.getByRole('button', { name: /자동 마킹 시작/ }));

    await waitFor(() => {
      const toasts = useUiStore.getState().toasts;
      expect(
        toasts.some(
          (t) => t.variant === 'error' && t.message.includes('이벤트 유형'),
        ),
      ).toBe(true);
    });
    // 실패 시 닫히거나 onMarked 되지 않는다.
    expect(onMarked).not.toHaveBeenCalled();
    expect(onClose).not.toHaveBeenCalled();
  });

  it('제출_진행중이면_버튼과_입력이_비활성화되어_중복제출_차단', async () => {
    // given: mutation 진행중(isPending=true)
    mockIsPending = true;
    const user = userEvent.setup();
    renderWithProviders(<MarkingModal rawSn={42} open onClose={vi.fn()} />);

    // when: 자동 단계 진입
    await user.click(screen.getByText('자동'));

    // then: 입력/제출 버튼이 비활성 → 중복 제출 불가
    const input = screen.getByLabelText(/프레임 간격/);
    const submit = screen.getByRole('button', { name: /자동 마킹 시작/ });
    expect(input).toBeDisabled();
    expect(submit).toBeDisabled();

    // 비활성 버튼 클릭해도 mutate 호출되지 않는다.
    await user.click(submit);
    expect(mutateMock).not.toHaveBeenCalled();
  });

  it('자동마킹_실패시_응답없거나_메시지없으면_고정_fallback_토스트', async () => {
    const user = userEvent.setup();
    renderWithProviders(<MarkingModal rawSn={42} open onClose={vi.fn()} />);
    await user.click(screen.getByText('자동'));
    await user.type(screen.getByLabelText(/프레임 간격/), '5');

    // ① response 자체가 없는 에러 → 고정 fallback 문구
    mutateMock.mockImplementationOnce(() =>
      capturedOptions?.onError?.(new Error('network down')),
    );
    await user.click(screen.getByRole('button', { name: /자동 마킹 시작/ }));
    await waitFor(() => {
      const toasts = useUiStore.getState().toasts;
      expect(
        toasts.some(
          (t) => t.variant === 'error' && t.message === '자동 마킹에 실패했습니다',
        ),
      ).toBe(true);
    });

    // ② response 는 있으나 data.message 가 없음 → 동일 fallback
    useUiStore.setState({ toasts: [] });
    mutateMock.mockImplementationOnce(() =>
      capturedOptions?.onError?.({ response: { data: {} } }),
    );
    await user.click(screen.getByRole('button', { name: /자동 마킹 시작/ }));
    await waitFor(() => {
      const toasts = useUiStore.getState().toasts;
      expect(
        toasts.some(
          (t) => t.variant === 'error' && t.message === '자동 마킹에 실패했습니다',
        ),
      ).toBe(true);
    });
  });

  it('수동_선택시_작업자_배치_흐름(AssignModal)으로_전환된다', async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <MarkingModal rawSn={42} videoName="CCTV-42" open onClose={vi.fn()} />,
    );

    await user.click(screen.getByText('수동'));

    const stub = await screen.findByTestId('assign-modal-stub');
    expect(stub).toHaveAttribute('data-mode', 'assign');
    expect(stub).toHaveAttribute('data-video-id', '42');
  });

  it('수동_배정_성공시_onMarked와_닫기가_호출된다', async () => {
    const onMarked = vi.fn();
    const onClose = vi.fn();
    const user = userEvent.setup();
    renderWithProviders(
      <MarkingModal rawSn={42} open onClose={onClose} onMarked={onMarked} />,
    );

    await user.click(screen.getByText('수동'));
    await user.click(await screen.findByText('배정완료-stub'));

    expect(onMarked).toHaveBeenCalledTimes(1);
    expect(onClose).toHaveBeenCalledTimes(1);
  });

  it('이전_클릭시_검증오류와_입력값이_초기화된다', async () => {
    const user = userEvent.setup();
    renderWithProviders(<MarkingModal rawSn={42} open onClose={vi.fn()} />);

    // auto 진입 → 잘못된 값 제출 → alert 노출 (기본 프리필 300 을 지우고 0 입력)
    await user.click(screen.getByText('자동'));
    await user.clear(screen.getByLabelText(/프레임 간격/));
    await user.type(screen.getByLabelText(/프레임 간격/), '0');
    await user.click(screen.getByRole('button', { name: /자동 마킹 시작/ }));
    expect(screen.getByRole('alert')).toBeInTheDocument();

    // 이전 → 자동 재진입: 오류메시지 미노출 + 입력 기본값(300)으로 리셋
    await user.click(screen.getByRole('button', { name: '이전' }));
    await user.click(screen.getByText('자동'));
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
    expect(screen.getByLabelText(/프레임 간격/)).toHaveValue('300');
  });

  it('모달_닫았다_다시열면_select_단계로_리셋된다', async () => {
    const user = userEvent.setup();
    const { rerender } = renderWithProviders(
      <MarkingModal rawSn={42} open onClose={vi.fn()} />,
    );

    // auto 단계로 이동
    await user.click(screen.getByText('자동'));
    expect(screen.getByLabelText(/프레임 간격/)).toBeInTheDocument();

    // 닫았다가(open=false) 다시 열기(open=true)
    rerender(<MarkingModal rawSn={42} open={false} onClose={vi.fn()} />);
    rerender(<MarkingModal rawSn={42} open onClose={vi.fn()} />);

    // select 단계(자동/수동 선택 버튼) 노출
    expect(screen.getByText('자동')).toBeInTheDocument();
    expect(screen.getByText('수동')).toBeInTheDocument();
    expect(screen.queryByLabelText(/프레임 간격/)).not.toBeInTheDocument();
  });

  it('ESC_또는_닫기로_모달이_닫힌다', async () => {
    const onClose = vi.fn();
    const user = userEvent.setup();
    renderWithProviders(<MarkingModal rawSn={42} open onClose={onClose} />);

    // ESC
    await user.keyboard('{Escape}');
    expect(onClose).toHaveBeenCalled();

    // 닫기 버튼(X)
    onClose.mockClear();
    await user.click(screen.getByRole('button', { name: '닫기' }));
    expect(onClose).toHaveBeenCalled();
  });
});
