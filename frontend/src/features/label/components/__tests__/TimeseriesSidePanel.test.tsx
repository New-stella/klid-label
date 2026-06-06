// TimeseriesSidePanel — 접이식 시계열 메타 패널 단위 테스트.
//
// 1. 토글 버튼 클릭 시 textarea 표시/숨김
// 2. useMeta mock 데이터의 vlmText가 textarea에 표시
// 3. 수정 전에는 저장 버튼 disabled, 수정 후 enabled
// 4. srcSn undefined일 때 빈 상태 렌더링

import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';

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

import { TimeseriesSidePanel } from '../TimeseriesSidePanel';

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
  });

  afterEach(() => {
    vi.clearAllMocks();
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
