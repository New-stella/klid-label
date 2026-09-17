// 영상 단위 개인정보 입력 패널 단위 테스트.
//
// 1. 진입 시 BE 프리필값(익명/가명/개인정보 포함) 체크박스 반영 + DERIVED 표기
// 2. 손대지 않은 DERIVED 프리필은 null 로 전송(기본상수의 MANUAL 승격 방지)
// 3. 원본이 MANUAL 이면 손대지 않아도 값을 전송(수동값 유실 방지)
// 4. dirty 아니면 저장 비활성
// 5. 영상(rawSn) 전환 시 로컬상태 동기화
// 6. 반영 범위 안내문구(비식별만 반영 / 원천은 판정 안 함)

import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { renderWithProviders } from '@/test/renderWithProviders';

const { mockMutate, mockUseVideoPrivacyMeta, mockUseUpdateVideoPrivacyMeta } = vi.hoisted(
  () => ({
    mockMutate: vi.fn(),
    mockUseVideoPrivacyMeta: vi.fn(),
    mockUseUpdateVideoPrivacyMeta: vi.fn(),
  }),
);

vi.mock('../../hooks/useVideoPrivacyMeta', () => ({
  useVideoPrivacyMeta: mockUseVideoPrivacyMeta,
  useUpdateVideoPrivacyMeta: mockUseUpdateVideoPrivacyMeta,
}));

import { MetaHelpProvider } from '../metaHelp';
import { VideoPrivacyMetaPanel } from '../VideoPrivacyMetaPanel';

/** BE 기본상수 프리필(익명 Y / 가명 N / 개인정보포함 N) — 전부 DERIVED. */
function prefill(overrides: Record<string, unknown> = {}) {
  mockUseVideoPrivacyMeta.mockReturnValue({
    data: {
      rawSn: 7,
      anonymity: 'Y',
      pseudonymity: 'N',
      privacyIncluded: 'N',
      anonymitySource: 'DERIVED',
      pseudonymitySource: 'DERIVED',
      privacyIncludedSource: 'DERIVED',
      ...overrides,
    },
    isLoading: false,
    isError: false,
  });
}

describe('VideoPrivacyMetaPanel', () => {
  beforeEach(() => {
    prefill();
    mockUseUpdateVideoPrivacyMeta.mockReturnValue({
      mutate: mockMutate,
      isPending: false,
      isError: false,
    });
  });

  afterEach(() => {
    vi.clearAllMocks();
  });

  it('진입시_BE_프리필값이_체크박스에_반영되고_기본값_출처가_표기된다', async () => {
    // given / when
    renderWithProviders(<VideoPrivacyMetaPanel rawSn={7} />);

    // then — 익명여부만 체크(Y), 나머지 미체크(N)
    await waitFor(() => {
      expect(screen.getByLabelText('익명여부')).toBeChecked();
    });
    expect(screen.getByLabelText('가명여부')).not.toBeChecked();
    expect(screen.getByLabelText('개인정보 포함여부')).not.toBeChecked();
    // and — 프리필(DERIVED)임을 색상이 아닌 텍스트로 표기(a11y)
    expect(screen.getAllByText('기본값')).toHaveLength(3);
  });

  it('손대지_않은_DERIVED_프리필은_null로_전송된다', async () => {
    // given — 3필드 모두 DERIVED 프리필
    const user = userEvent.setup();
    renderWithProviders(<VideoPrivacyMetaPanel rawSn={7} />);

    // when — 개인정보 포함여부만 토글(N→Y) 후 저장
    await user.click(screen.getByLabelText('개인정보 포함여부'));
    const saveBtn = screen.getByRole('button', { name: /저장/ });
    await waitFor(() => expect(saveBtn).toBeEnabled());
    await user.click(saveBtn);

    // then — 기본상수가 사람의 판정으로 승격되지 않도록 손 안 댄 필드는 null
    expect(mockMutate).toHaveBeenCalledWith({
      anonymity: null,
      pseudonymity: null,
      privacyIncluded: 'Y',
    });
  });

  it('원본이_MANUAL이면_손대지_않아도_값을_전송한다', async () => {
    // given — 익명여부는 이미 사람이 저장한 값(MANUAL)
    prefill({ anonymity: 'N', anonymitySource: 'MANUAL' });
    const user = userEvent.setup();
    renderWithProviders(<VideoPrivacyMetaPanel rawSn={7} />);

    // when — 가명여부만 토글(N→Y) 후 저장
    await user.click(screen.getByLabelText('가명여부'));
    const saveBtn = screen.getByRole('button', { name: /저장/ });
    await waitFor(() => expect(saveBtn).toBeEnabled());
    await user.click(saveBtn);

    // then — 전체 교체 계약이라 MANUAL 필드를 null 로 보내면 기존 수동값이 삭제된다
    expect(mockMutate).toHaveBeenCalledWith({
      anonymity: 'N',
      pseudonymity: 'Y',
      privacyIncluded: null,
    });
  });

  it('dirty_아니면_저장버튼_비활성', () => {
    // given / when — 변경 없음
    renderWithProviders(<VideoPrivacyMetaPanel rawSn={7} />);

    // then
    expect(screen.getByRole('button', { name: /저장/ })).toBeDisabled();
  });

  it('영상_전환시_로컬상태가_동기화된다', async () => {
    // given — 첫 영상(기본상수 프리필)
    const { rerender } = renderWithProviders(<VideoPrivacyMetaPanel rawSn={7} />);
    await waitFor(() => expect(screen.getByLabelText('익명여부')).toBeChecked());

    // when — 다른 영상(익명 N / 개인정보 포함 Y, 사람이 판정)
    prefill({
      rawSn: 8,
      anonymity: 'N',
      privacyIncluded: 'Y',
      anonymitySource: 'MANUAL',
      privacyIncludedSource: 'MANUAL',
    });
    rerender(<VideoPrivacyMetaPanel rawSn={8} />);

    // then
    await waitFor(() =>
      expect(screen.getByLabelText('개인정보 포함여부')).toBeChecked(),
    );
    expect(screen.getByLabelText('익명여부')).not.toBeChecked();
  });

  it('반영범위_안내문구는_도움말을_켜야_보인다', () => {
    // ⚠ 2026-09-15 전제 변경 — 이 안내는 <b>구역 설명문</b>이 되어 메타 탭 도움말 뒤로 들어갔다
    //   (기본 감춤). 좁은 탭에서 설명이 값을 아래로 밀어낸다는 사용자 지적의 반영이며 회귀가 아니다.
    //   ★문구 자체는 그대로다 — 「지웠다」와 「도움말 뒤로 옮겼다」를 구분하려고 두 상태를 다 센다.
    const { unmount } = renderWithProviders(<VideoPrivacyMetaPanel rawSn={7} />);
    expect(screen.queryByText(/비식별 학습데이터에 반영/)).toBeNull();
    unmount();

    // when — 도움말을 켠 상태
    renderWithProviders(
      <MetaHelpProvider visible>
        <VideoPrivacyMetaPanel rawSn={7} />
      </MetaHelpProvider>,
    );

    // then — 영상 단위 판정 + 비식별만 반영 + 원천은 판정 안 함
    expect(screen.getByText(/영상 전체 기준/)).toBeInTheDocument();
    expect(screen.getByText(/비식별 학습데이터에 반영/)).toBeInTheDocument();
    expect(screen.getByText(/판정하지 않습니다/)).toBeInTheDocument();
  });
});
