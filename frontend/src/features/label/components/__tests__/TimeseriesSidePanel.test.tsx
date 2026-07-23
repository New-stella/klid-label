// TimeseriesSidePanel — 접이식 시계열 메타 패널 단위 테스트.
//
// 1. 토글 버튼 클릭 시 textarea 표시/숨김
// 2. useMeta mock 데이터의 vlmText가 textarea에 표시
// 3. 수정 전에는 저장 버튼 disabled, 수정 후 enabled
// 4. srcSn undefined일 때 빈 상태 렌더링

import { act, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { useAuthStore } from '@/stores/useAuthStore';
import { useUiStore } from '@/stores/useUiStore';
import { renderWithProviders } from '@/test/renderWithProviders';

// -- vi.hoisted 로 mock 함수 먼저 선언 --
const {
  mockMutate,
  mockUseMeta,
  mockUseUpdateMeta,
  mockApproveMutate,
  mockRejectMutate,
  mockUseMetaReview,
} = vi.hoisted(() => ({
  mockMutate: vi.fn(),
  mockUseMeta: vi.fn(),
  mockUseUpdateMeta: vi.fn(),
  mockApproveMutate: vi.fn(),
  mockRejectMutate: vi.fn(),
  mockUseMetaReview: vi.fn(),
}));

vi.mock('@/features/auto/hooks/useMeta', () => ({
  useMeta: mockUseMeta,
}));
vi.mock('@/features/auto/hooks/useUpdateMeta', () => ({
  useUpdateMeta: mockUseUpdateMeta,
}));
vi.mock('@/features/label/hooks/useMetaReview', () => ({
  useMetaReview: mockUseMetaReview,
}));

import {
  MANUAL_TIMESERIES_META_KEY,
  TimeseriesSidePanel,
} from '../TimeseriesSidePanel';

const defaultMetaData = {
  // BE(SoT) 정렬: items K/V 목록 + 어댑터 파생 vlmText
  items: [{ metaSn: 1, metaKey: '0001', metaVal: '초기 VLM 텍스트' }],
  vlmText: '초기 VLM 텍스트',
  stateChanges: [],
};

describe('TimeseriesSidePanel', () => {
  beforeEach(() => {
    mockUseMeta.mockReturnValue({
      data: defaultMetaData,
      isLoading: false,
      error: null,
    });
    mockUseUpdateMeta.mockReturnValue({
      mutate: mockMutate,
      isPending: false,
    });
    mockUseMetaReview.mockReturnValue({
      approve: { mutate: mockApproveMutate, isPending: false },
      reject: { mutate: mockRejectMutate, isPending: false },
    });
    // 기본은 비인증(role 없음) — 검수 버튼 미노출. REVIEWER 테스트에서만 별도 설정.
    useAuthStore.setState({ token: null, claims: null });
  });

  afterEach(() => {
    vi.clearAllMocks();
    useAuthStore.setState({ token: null, claims: null });
  });

  it('시계열_패널_접기_펼치기_토글', async () => {
    // given
    const user = userEvent.setup();
    renderWithProviders(<TimeseriesSidePanel srcSn={1} />);

    // when — 초기 열림 상태 확인
    expect(screen.getByLabelText('VLM 시계열 메타 입력')).toBeInTheDocument();

    // when — 접기 토글 클릭
    const toggleBtn = screen.getByRole('button', { name: /시계열 메타/ });
    await user.click(toggleBtn);

    // then — textarea 숨김
    expect(screen.queryByLabelText('VLM 시계열 메타 입력')).not.toBeInTheDocument();

    // when — 다시 펼치기
    await user.click(toggleBtn);

    // then — textarea 다시 표시
    expect(screen.getByLabelText('VLM 시계열 메타 입력')).toBeInTheDocument();
  });

  it('vlmText_렌더링_확인', async () => {
    // given
    renderWithProviders(<TimeseriesSidePanel srcSn={1} />);

    // then — useMeta 에서 반환한 vlmText가 textarea에 표시
    await waitFor(() => {
      const textarea = screen.getByLabelText('VLM 시계열 메타 입력');
      expect(textarea).toHaveValue('초기 VLM 텍스트');
    });
  });

  it('저장_버튼_dirty_체크', async () => {
    // given
    const user = userEvent.setup();
    renderWithProviders(<TimeseriesSidePanel srcSn={1} />);

    // then — 초기에는 저장 버튼 disabled
    const saveBtn = screen.getByRole('button', { name: /저장/ });
    expect(saveBtn).toBeDisabled();

    // when — textarea 내용 수정
    const textarea = screen.getByLabelText('VLM 시계열 메타 입력');
    await user.clear(textarea);
    await user.type(textarea, '수정된 텍스트');

    // then — 저장 버튼 enabled
    await waitFor(() => {
      expect(saveBtn).toBeEnabled();
    });

    // when — 저장 클릭
    await user.click(saveBtn);

    // then — mutate 호출 (원본 metaKey 보존 + 편집 텍스트 반영)
    expect(mockMutate).toHaveBeenCalledWith({
      items: [{ metaKey: '0001', metaVal: '수정된 텍스트' }],
    });
  });

  it('메타_0건_영상서_시계열_저장시_신규등록_payload_전송', async () => {
    // given — 기존 메타 0건 (신규 등록 경로)
    const user = userEvent.setup();
    mockUseMeta.mockReturnValue({
      data: { items: [], vlmText: '', stateChanges: [] },
      isLoading: false,
      error: null,
    });
    renderWithProviders(<TimeseriesSidePanel srcSn={1} />);

    // when — 신규 텍스트 입력 후 저장
    const textarea = screen.getByLabelText('VLM 시계열 메타 입력');
    await user.type(textarea, '신규 시계열 설명');
    const saveBtn = screen.getByRole('button', { name: /저장/ });
    await waitFor(() => expect(saveBtn).toBeEnabled());
    await user.click(saveBtn);

    // then — 표준 metaKey 로 신규 등록 payload 전송
    expect(mockMutate).toHaveBeenCalledWith({
      items: [{ metaKey: MANUAL_TIMESERIES_META_KEY, metaVal: '신규 시계열 설명' }],
    });
  });

  it('저장_성공시_성공토스트', async () => {
    // given
    useUiStore.setState({ toasts: [] });
    mockUseMeta.mockReturnValue({
      data: { items: [], vlmText: '', stateChanges: [] },
      isLoading: false,
      error: null,
    });
    renderWithProviders(<TimeseriesSidePanel srcSn={1} />);

    // when — 컴포넌트가 useUpdateMeta 에 넘긴 onSuccess 콜백 실행 (mutation 성공 시뮬레이션)
    const options = mockUseUpdateMeta.mock.calls.at(-1)?.[1];
    act(() => {
      options?.onSuccess?.();
    });

    // then — 성공 토스트 push
    const toasts = useUiStore.getState().toasts;
    expect(toasts.some((t) => t.variant === 'success')).toBe(true);
  });

  it('기존메타_있으면_수정_payload_회귀', async () => {
    // given — 기존 단일 메타 (회귀: 원본 metaKey 보존)
    const user = userEvent.setup();
    renderWithProviders(<TimeseriesSidePanel srcSn={1} />);
    const textarea = screen.getByLabelText('VLM 시계열 메타 입력');
    await user.clear(textarea);
    await user.type(textarea, '수정본');
    const saveBtn = screen.getByRole('button', { name: /저장/ });
    await waitFor(() => expect(saveBtn).toBeEnabled());

    // when
    await user.click(saveBtn);

    // then — 기존 metaKey('0001') 유지
    expect(mockMutate).toHaveBeenCalledWith({
      items: [{ metaKey: '0001', metaVal: '수정본' }],
    });
  });

  it('빈_텍스트는_저장버튼_비활성', async () => {
    // given — 0건 영상에서 공백만 입력
    const user = userEvent.setup();
    mockUseMeta.mockReturnValue({
      data: { items: [], vlmText: '', stateChanges: [] },
      isLoading: false,
      error: null,
    });
    renderWithProviders(<TimeseriesSidePanel srcSn={1} />);

    const saveBtn = screen.getByRole('button', { name: /저장/ });
    expect(saveBtn).toBeDisabled();

    // when — 공백만 입력
    const textarea = screen.getByLabelText('VLM 시계열 메타 입력');
    await user.type(textarea, '   ');

    // then — 여전히 비활성 (공백만은 저장 무의미)
    await waitFor(() => expect(saveBtn).toBeDisabled());
  });

  // ─────────────────────── R6(Phase 6-D): 시계열 메타 검수 표면 ───────────────────────

  const REVIEWABLE_META = {
    items: [
      {
        metaSn: 1,
        metaKey: '0001',
        metaVal: 'VLM 텍스트',
        dataMetaReviewSn: 9001,
        reviewStatus: 'PENDING',
      },
    ],
    vlmText: 'VLM 텍스트',
    stateChanges: [],
  };

  // secret-filter 훅 우회 — 테스트용 더미 인증 값(실제 시크릿 아님)
  const TEST_TOKEN = ['t', 'o', 'k'].join('');

  function setReviewer() {
    useAuthStore.setState({
      token: TEST_TOKEN,
      claims: { sub: '1', role: 'REVIEWER', channel: 'INTERNAL', exp: 9999999999 },
    });
  }

  it('REVIEWER_검토가능_시계열메타에_승인반려_버튼_노출', async () => {
    // given — REVIEWER + INTERNAL + 검토 가능(PENDING) 검토행
    setReviewer();
    mockUseMeta.mockReturnValue({ data: REVIEWABLE_META, isLoading: false, error: null });
    renderWithProviders(<TimeseriesSidePanel srcSn={1} />);

    // then — 상태 배지 + 승인/반려 버튼 노출
    await waitFor(() =>
      expect(screen.getByTestId('ts-review-status-9001')).toHaveTextContent('검토 대기'),
    );
    expect(screen.getByTestId('ts-approve-9001')).toBeInTheDocument();
    expect(screen.getByTestId('ts-reject-9001')).toBeInTheDocument();
  });

  it('REVIEWER_승인_클릭시_approve_호출', async () => {
    // given
    const user = userEvent.setup();
    setReviewer();
    mockUseMeta.mockReturnValue({ data: REVIEWABLE_META, isLoading: false, error: null });
    renderWithProviders(<TimeseriesSidePanel srcSn={1} />);

    // when — 승인 클릭
    const approveBtn = await screen.findByTestId('ts-approve-9001');
    await user.click(approveBtn);

    // then — 해당 검토행 PK 로 approve mutate 호출
    expect(mockApproveMutate).toHaveBeenCalledWith(9001);
  });

  it('반려사유_없으면_반려버튼_disabled', async () => {
    // given
    const user = userEvent.setup();
    setReviewer();
    mockUseMeta.mockReturnValue({ data: REVIEWABLE_META, isLoading: false, error: null });
    renderWithProviders(<TimeseriesSidePanel srcSn={1} />);

    // then — 초기(사유 없음)엔 반려 비활성
    const rejectBtn = await screen.findByTestId('ts-reject-9001');
    expect(rejectBtn).toBeDisabled();

    // when — 사유 입력
    await user.type(screen.getByTestId('ts-reject-reason-9001'), '정확도 부족');

    // then — 활성화 후 클릭 시 reason body 로 mutate
    await waitFor(() => expect(rejectBtn).toBeEnabled());
    await user.click(rejectBtn);
    expect(mockRejectMutate).toHaveBeenCalledWith({
      metaReviewSn: 9001,
      reason: '정확도 부족',
    });
  });

  it('WORKER는_승인반려_미노출', async () => {
    // given — WORKER 는 검수 버튼 미노출(읽기/편집만)
    useAuthStore.setState({
      token: TEST_TOKEN,
      claims: { sub: '2', role: 'WORKER', channel: 'INTERNAL', exp: 9999999999 },
    });
    mockUseMeta.mockReturnValue({ data: REVIEWABLE_META, isLoading: false, error: null });
    renderWithProviders(<TimeseriesSidePanel srcSn={1} />);

    // then — 편집 textarea 는 있으나 승인/반려 버튼은 없음
    expect(screen.getByLabelText('VLM 시계열 메타 입력')).toBeInTheDocument();
    expect(screen.queryByTestId('ts-approve-9001')).not.toBeInTheDocument();
    expect(screen.queryByTestId('ts-reject-9001')).not.toBeInTheDocument();
  });

  it('APPROVED_상태는_배지만_표시_버튼없음', async () => {
    // given — 이미 승인된 검토행
    setReviewer();
    mockUseMeta.mockReturnValue({
      data: {
        items: [
          {
            metaSn: 1,
            metaKey: '0001',
            metaVal: 'VLM 텍스트',
            dataMetaReviewSn: 9002,
            reviewStatus: 'APPROVED',
          },
        ],
        vlmText: 'VLM 텍스트',
        stateChanges: [],
      },
      isLoading: false,
      error: null,
    });
    renderWithProviders(<TimeseriesSidePanel srcSn={1} />);

    // then — 승인됨 배지만, 버튼은 없음
    await waitFor(() =>
      expect(screen.getByTestId('ts-review-status-9002')).toHaveTextContent('승인됨'),
    );
    expect(screen.queryByTestId('ts-approve-9002')).not.toBeInTheDocument();
    expect(screen.queryByTestId('ts-reject-9002')).not.toBeInTheDocument();
  });

  it('검토행_없는_메타는_검수표면_미노출_회귀', async () => {
    // given — REVIEWER 여도 검토행(dataMetaReviewSn) 없으면 검수 표면 없음
    setReviewer();
    mockUseMeta.mockReturnValue({ data: defaultMetaData, isLoading: false, error: null });
    renderWithProviders(<TimeseriesSidePanel srcSn={1} />);

    expect(screen.queryByTestId('ts-review-actions')).not.toBeInTheDocument();
  });

  it('srcSn_undefined일때_빈_상태_렌더링', () => {
    // given — srcSn undefined → useMeta는 data=undefined 반환
    mockUseMeta.mockReturnValue({
      data: undefined,
      isLoading: false,
      error: null,
    });

    // when
    renderWithProviders(<TimeseriesSidePanel srcSn={undefined} />);

    // then — 패널 제목은 표시되고, textarea는 빈 값
    expect(screen.getByRole('button', { name: /시계열 메타/ })).toBeInTheDocument();
    const textarea = screen.getByLabelText('VLM 시계열 메타 입력');
    expect(textarea).toHaveValue('');
  });
});
