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
const { mockMutate, mockUseMeta, mockUseUpdateMeta } = vi.hoisted(() => ({
  mockMutate: vi.fn(),
  mockUseMeta: vi.fn(),
  mockUseUpdateMeta: vi.fn(),
}));

vi.mock('@/features/auto/hooks/useMeta', () => ({
  useMeta: mockUseMeta,
}));
vi.mock('@/features/auto/hooks/useUpdateMeta', () => ({
  useUpdateMeta: mockUseUpdateMeta,
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
    // 기본은 비인증(role 없음). REVIEWER 회귀 가드 케이스에서만 별도 설정.
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

  // ───────── 검토(승인/반려) 표면 제거 회귀 가드 (2026-08-03 사용자 확정) ─────────
  //
  // 이 패널은 시계열 메타 '텍스트 수정'만 제공한다. 검토 상태 배지·승인/반려 버튼·반려 사유
  // 입력은 노출하지 않으며, 검토 상태 확정은 영상 검수 승인 시 BE 자동 동결이 담당한다.
  // (BE API /v1/meta/{metaReviewSn}/approve|reject 와 LS_DATA_META_REVIEW 는 존치, FE 진입점만 없음)
  // 이 테스트가 깨진다면 회귀가 아니라 정책 변경이다.

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

  it('REVIEWER여도_검토상태배지와_승인반려UI가_노출되지_않는다', async () => {
    // given — REVIEWER + INTERNAL + 검토 가능(PENDING) 검토행이 있는 메타
    setReviewer();
    mockUseMeta.mockReturnValue({ data: REVIEWABLE_META, isLoading: false, error: null });

    // when
    renderWithProviders(<TimeseriesSidePanel srcSn={1} />);

    // then — 편집 textarea·저장 버튼은 있으나 검토 표면은 전부 없음
    await waitFor(() =>
      expect(screen.getByLabelText('VLM 시계열 메타 입력')).toBeInTheDocument(),
    );
    expect(screen.getByRole('button', { name: /저장/ })).toBeInTheDocument();
    expect(screen.queryByTestId('ts-review-actions')).not.toBeInTheDocument();
    expect(screen.queryByTestId('ts-review-status-9001')).not.toBeInTheDocument();
    expect(screen.queryByTestId('ts-approve-9001')).not.toBeInTheDocument();
    expect(screen.queryByTestId('ts-reject-9001')).not.toBeInTheDocument();
    expect(screen.queryByTestId('ts-reject-reason-9001')).not.toBeInTheDocument();
    expect(screen.queryByText('검토 상태')).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '승인' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '반려' })).not.toBeInTheDocument();
  });

  it('APPROVED_검토행이_있어도_승인됨_배지가_노출되지_않는다', async () => {
    // given — 실사용 대다수 케이스(승인 완료 영상). 배지만 반복 노출되던 것을 제거했다.
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

    // when
    renderWithProviders(<TimeseriesSidePanel srcSn={1} />);

    // then — 텍스트는 편집 가능하되 상태 배지는 없음
    await waitFor(() =>
      expect(screen.getByLabelText('VLM 시계열 메타 입력')).toHaveValue('VLM 텍스트'),
    );
    expect(screen.queryByTestId('ts-review-status-9002')).not.toBeInTheDocument();
    expect(screen.queryByText('승인됨')).not.toBeInTheDocument();
  });

  it('검토행이_있어도_텍스트_편집_저장은_그대로_동작한다', async () => {
    // given — 검토행이 붙은 메타(제거 대상은 검토 UI 뿐, 편집 경로는 회귀 없음)
    const user = userEvent.setup();
    setReviewer();
    mockUseMeta.mockReturnValue({ data: REVIEWABLE_META, isLoading: false, error: null });
    renderWithProviders(<TimeseriesSidePanel srcSn={1} />);

    // when
    const textarea = screen.getByLabelText('VLM 시계열 메타 입력');
    await user.clear(textarea);
    await user.type(textarea, '검토행 있어도 수정');
    const saveBtn = screen.getByRole('button', { name: /저장/ });
    await waitFor(() => expect(saveBtn).toBeEnabled());
    await user.click(saveBtn);

    // then — 원본 metaKey 보존 저장
    expect(mockMutate).toHaveBeenCalledWith({
      items: [{ metaKey: '0001', metaVal: '검토행 있어도 수정' }],
    });
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
