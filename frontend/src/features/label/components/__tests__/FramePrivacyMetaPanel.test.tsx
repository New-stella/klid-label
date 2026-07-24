// Phase 4 — 개인정보 입력 패널 단위 테스트.
//
// 1. 진입 시 BE 프리필값(익명/가명/개인정보 포함) 체크박스 반영
// 2. 체크박스 토글 저장 시 PUT mutate 호출(Y/N 전송)
// 3. dirty 아니면 저장 비활성
// 4. 프레임(srcSn) 전환 시 로컬상태 동기화
// 5. 익명여부 안내문구 표시(export 미반영 안내)

import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { renderWithProviders } from '@/test/renderWithProviders';

const { mockMutate, mockUseFramePrivacyMeta, mockUseUpdateFramePrivacyMeta } = vi.hoisted(
  () => ({
    mockMutate: vi.fn(),
    mockUseFramePrivacyMeta: vi.fn(),
    mockUseUpdateFramePrivacyMeta: vi.fn(),
  }),
);

vi.mock('../../hooks/useFramePrivacyMeta', () => ({
  useFramePrivacyMeta: mockUseFramePrivacyMeta,
  useUpdateFramePrivacyMeta: mockUseUpdateFramePrivacyMeta,
}));

import { FramePrivacyMetaPanel } from '../FramePrivacyMetaPanel';

function prefill(overrides: Record<string, unknown> = {}) {
  mockUseFramePrivacyMeta.mockReturnValue({
    data: {
      srcSn: 5,
      anonymity: 'N',
      pseudonymity: 'Y',
      privacyIncluded: 'N',
      ...overrides,
    },
    isLoading: false,
    isError: false,
  });
}

describe('FramePrivacyMetaPanel', () => {
  beforeEach(() => {
    prefill();
    mockUseUpdateFramePrivacyMeta.mockReturnValue({
      mutate: mockMutate,
      isPending: false,
      isError: false,
    });
  });

  afterEach(() => {
    vi.clearAllMocks();
  });

  it('진입시_BE_프리필값이_체크박스에_반영된다', async () => {
    // given / when
    renderWithProviders(<FramePrivacyMetaPanel srcSn={5} />);

    // then — 가명여부만 체크(Y), 나머지 미체크(N)
    await waitFor(() => {
      expect(screen.getByLabelText('가명여부')).toBeChecked();
    });
    expect(screen.getByLabelText('익명여부')).not.toBeChecked();
    expect(screen.getByLabelText('개인정보 포함여부')).not.toBeChecked();
  });

  it('토글한_필드만_YN_손안댄_필드는_null로_전송', async () => {
    // given — anonymity='N', pseudonymity='Y', privacyIncluded='N' (beforeEach prefill)
    const user = userEvent.setup();
    renderWithProviders(<FramePrivacyMetaPanel srcSn={5} />);

    // when — 익명여부만 체크(N→Y) 후 저장
    await user.click(screen.getByLabelText('익명여부'));
    const saveBtn = screen.getByRole('button', { name: /저장/ });
    await waitFor(() => expect(saveBtn).toBeEnabled());
    await user.click(saveBtn);

    // then — 토글한 anonymity 만 값, 손 안 댄 필드(프리필=파생)는 null
    //   (프리필값 그대로 재전송 → MANUAL 승격 방지: BE 전체 교체 계약, source 미제공→touched 판정)
    expect(mockMutate).toHaveBeenCalledWith({
      anonymity: 'Y',
      pseudonymity: null,
      privacyIncluded: null,
    });
  });

  it('사용자가_바꾼_필드만_값으로_전송된다', async () => {
    // given — pseudonymity='Y' 를 해제(Y→N)하고 나머지는 손대지 않음
    const user = userEvent.setup();
    renderWithProviders(<FramePrivacyMetaPanel srcSn={5} />);

    // when — 가명여부만 토글(Y→N) 후 저장
    await user.click(screen.getByLabelText('가명여부'));
    const saveBtn = screen.getByRole('button', { name: /저장/ });
    await waitFor(() => expect(saveBtn).toBeEnabled());
    await user.click(saveBtn);

    // then — 바꾼 pseudonymity 만 'N', 손 안 댄 필드는 null
    expect(mockMutate).toHaveBeenCalledWith({
      anonymity: null,
      pseudonymity: 'N',
      privacyIncluded: null,
    });
  });

  it('프리필값_그대로_저장시_손안댄_필드는_null로_전송된다', async () => {
    // given — 개인정보 포함여부만 켜고(N→Y), anonymity/pseudonymity 프리필은 손대지 않음
    const user = userEvent.setup();
    renderWithProviders(<FramePrivacyMetaPanel srcSn={5} />);

    // when
    await user.click(screen.getByLabelText('개인정보 포함여부'));
    const saveBtn = screen.getByRole('button', { name: /저장/ });
    await waitFor(() => expect(saveBtn).toBeEnabled());
    await user.click(saveBtn);

    // then — 손 안 댄 프리필(anonymity/pseudonymity)은 null, 바꾼 privacyIncluded 만 값
    expect(mockMutate).toHaveBeenCalledWith({
      anonymity: null,
      pseudonymity: null,
      privacyIncluded: 'Y',
    });
  });

  it('dirty_아니면_저장버튼_비활성', () => {
    // given / when — 변경 없음
    renderWithProviders(<FramePrivacyMetaPanel srcSn={5} />);

    // then
    expect(screen.getByRole('button', { name: /저장/ })).toBeDisabled();
  });

  it('프레임_전환시_로컬상태_동기화', async () => {
    // given — 첫 프레임 프리필(가명 Y)
    const { rerender } = renderWithProviders(<FramePrivacyMetaPanel srcSn={5} />);
    await waitFor(() => expect(screen.getByLabelText('가명여부')).toBeChecked());

    // when — 다른 프레임(개인정보 포함 Y, 가명 N)
    prefill({ srcSn: 6, anonymity: 'N', pseudonymity: 'N', privacyIncluded: 'Y' });
    rerender(<FramePrivacyMetaPanel srcSn={6} />);

    // then — 새 값 동기화
    await waitFor(() =>
      expect(screen.getByLabelText('개인정보 포함여부')).toBeChecked(),
    );
    expect(screen.getByLabelText('가명여부')).not.toBeChecked();
  });

  it('익명여부_안내문구_표시', () => {
    // given / when
    renderWithProviders(<FramePrivacyMetaPanel srcSn={5} />);

    // then — export 미반영(시스템 자동 결정) 안내
    expect(screen.getByText(/자동 결정/)).toBeInTheDocument();
  });
});
