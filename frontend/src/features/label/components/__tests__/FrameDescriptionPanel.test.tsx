// blocker#2 Phase 3 — 프레임 설명 입력 패널 단위 테스트.
//
// 1. 프레임 선택 시 기존 설명 로드(useQuery mock)
// 2. 입력 후 저장 → PUT mutate 호출
// 3. 저장 실패 시 에러 표시
// 4. <script> 입력이 텍스트로 표시(React escape, 저장형 XSS 방어)
// 5. srcSn undefined 시 저장 비활성

import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { renderWithProviders } from '@/test/renderWithProviders';

const { mockMutate, mockUseFrameDescription, mockUseUpdateFrameDescription } = vi.hoisted(() => ({
  mockMutate: vi.fn(),
  mockUseFrameDescription: vi.fn(),
  mockUseUpdateFrameDescription: vi.fn(),
}));

vi.mock('../../hooks/useFrameDescription', () => ({
  useFrameDescription: mockUseFrameDescription,
  useUpdateFrameDescription: mockUseUpdateFrameDescription,
}));

import { FrameDescriptionPanel } from '../FrameDescriptionPanel';

const LABEL = '프레임 설명 입력';

describe('FrameDescriptionPanel', () => {
  beforeEach(() => {
    mockUseFrameDescription.mockReturnValue({
      data: { srcSn: 1, description: '기존 설명 텍스트' },
      isLoading: false,
      isError: false,
    });
    mockUseUpdateFrameDescription.mockReturnValue({
      mutate: mockMutate,
      isPending: false,
      isError: false,
    });
  });

  afterEach(() => {
    vi.clearAllMocks();
  });

  it('프레임_선택시_기존_설명_로드', async () => {
    // given / when
    renderWithProviders(<FrameDescriptionPanel srcSn={1} />);

    // then — 조회한 description 이 textarea 에 표시
    await waitFor(() => {
      expect(screen.getByLabelText(LABEL)).toHaveValue('기존 설명 텍스트');
    });
  });

  it('설명_입력_저장시_PUT_호출_및_반영', async () => {
    // given
    const user = userEvent.setup();
    renderWithProviders(<FrameDescriptionPanel srcSn={1} />);

    const textarea = screen.getByLabelText(LABEL);
    await user.clear(textarea);
    await user.type(textarea, '새로운 프레임 설명');

    // when — 저장 버튼 클릭
    const saveBtn = screen.getByRole('button', { name: /저장/ });
    await waitFor(() => expect(saveBtn).toBeEnabled());
    await user.click(saveBtn);

    // then — mutate 가 편집한 텍스트로 호출
    expect(mockMutate).toHaveBeenCalledWith('새로운 프레임 설명');
  });

  it('저장_실패시_에러_표시', async () => {
    // given — mutation 이 에러 상태
    mockUseUpdateFrameDescription.mockReturnValue({
      mutate: mockMutate,
      isPending: false,
      isError: true,
    });

    // when
    renderWithProviders(<FrameDescriptionPanel srcSn={1} />);

    // then — 에러 알림 노출
    expect(screen.getByRole('alert')).toHaveTextContent(/저장.*실패/);
  });

  it('script태그_입력시_텍스트로_표시_XSS방어', async () => {
    // given — 악성 스크립트가 저장돼 있던 경우
    const payload = '<script>alert("xss")</script>';
    mockUseFrameDescription.mockReturnValue({
      data: { srcSn: 1, description: payload },
      isLoading: false,
      isError: false,
    });

    // when
    renderWithProviders(<FrameDescriptionPanel srcSn={1} />);

    // then — textarea 값으로 텍스트 그대로(escape) 표시, script 요소는 생성되지 않음
    const textarea = screen.getByLabelText(LABEL) as HTMLTextAreaElement;
    expect(textarea.value).toBe(payload);
    expect(document.querySelector('script')).toBeNull();
  });

  it('srcSn_undefined시_저장_비활성', () => {
    // given — 프레임 미선택
    mockUseFrameDescription.mockReturnValue({
      data: undefined,
      isLoading: false,
      isError: false,
    });

    // when
    renderWithProviders(<FrameDescriptionPanel srcSn={undefined} />);

    // then — 저장 버튼 비활성(변경 없음 + srcSn 없음)
    expect(screen.getByRole('button', { name: /저장/ })).toBeDisabled();
  });
});
