// Phase 4 — 촬영환경 입력 패널 단위 테스트.
//
// 1. 진입 시 BE 프리필값(날씨/시간대/계절) 표시
// 2. 날씨 변경 저장 시 PUT mutate 호출(코드값 전송)
// 3. dirty 아니면 저장 비활성
// 4. 영상(rawSn) 전환 시 로컬상태 동기화
// 5. <script> 저장값이 select 옵션에 텍스트로 노출되지 않음(XSS 방어 — 고정 코드만)

import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { renderWithProviders } from '@/test/renderWithProviders';
import { selectRadixOption } from '@/test/selectTestUtils';

const { mockMutate, mockUseEnvironmentMeta, mockUseUpdateEnvironmentMeta } = vi.hoisted(() => ({
  mockMutate: vi.fn(),
  mockUseEnvironmentMeta: vi.fn(),
  mockUseUpdateEnvironmentMeta: vi.fn(),
}));

vi.mock('../../hooks/useEnvironmentMeta', () => ({
  useEnvironmentMeta: mockUseEnvironmentMeta,
  useUpdateEnvironmentMeta: mockUseUpdateEnvironmentMeta,
}));

import { EnvironmentMetaPanel } from '../EnvironmentMetaPanel';

function prefill(overrides: Record<string, unknown> = {}) {
  mockUseEnvironmentMeta.mockReturnValue({
    data: {
      rawSn: 10,
      weather: '맑음',
      timeOfDay: 'DAY',
      season: 'SUMMER',
      weatherSource: 'MANUAL',
      timeOfDaySource: 'DERIVED',
      seasonSource: 'DERIVED',
      ...overrides,
    },
    isLoading: false,
    isError: false,
  });
}

describe('EnvironmentMetaPanel', () => {
  beforeEach(() => {
    prefill();
    mockUseUpdateEnvironmentMeta.mockReturnValue({
      mutate: mockMutate,
      isPending: false,
      isError: false,
    });
  });

  afterEach(() => {
    vi.clearAllMocks();
  });

  it('진입시_BE_프리필값이_표시된다', async () => {
    // given / when
    renderWithProviders(<EnvironmentMetaPanel rawSn={10} />);

    // then — 날씨 select 프리필 + 시간대 라디오(주간) 선택 + 계절 select(여름)
    await waitFor(() => {
      expect(screen.getByLabelText('날씨')).toHaveTextContent('맑음');
    });
    expect(screen.getByRole('radio', { name: '주간' })).toHaveAttribute(
      'aria-checked',
      'true',
    );
    expect(screen.getByLabelText('계절')).toHaveTextContent('여름');
  });

  it('날씨_변경_저장시_바꾼필드만_값_손안댄_DERIVED는_null로_전송', async () => {
    // given — weather=MANUAL, timeOfDay/season=DERIVED (beforeEach prefill)
    const user = userEvent.setup();
    renderWithProviders(<EnvironmentMetaPanel rawSn={10} />);

    // when — 날씨(MANUAL)만 '비'로 변경 후 저장
    await selectRadixOption(user, screen.getByLabelText('날씨'), '비');
    const saveBtn = screen.getByRole('button', { name: /저장/ });
    await waitFor(() => expect(saveBtn).toBeEnabled());
    await user.click(saveBtn);

    // then — 바꾼 weather 만 값, 손대지 않은 DERIVED(timeOfDay/season)는 null
    //   (파생값 재전송 → MANUAL 승격 방지: BE 전체 교체 계약)
    expect(mockMutate).toHaveBeenCalledWith({
      weather: '비',
      timeOfDay: null,
      season: null,
    });
  });

  it('사용자가_바꾼_필드만_값으로_전송된다', async () => {
    // given — weather=MANUAL(손 안 댐), timeOfDay=DERIVED(손 안 댐), season=DERIVED(변경)
    const user = userEvent.setup();
    renderWithProviders(<EnvironmentMetaPanel rawSn={10} />);

    // when — 계절만 '겨울'(WINTER)로 변경 후 저장
    await selectRadixOption(user, screen.getByLabelText('계절'), '겨울');
    const saveBtn = screen.getByRole('button', { name: /저장/ });
    await waitFor(() => expect(saveBtn).toBeEnabled());
    await user.click(saveBtn);

    // then — 바꾼 season 은 값, 손 안 댄 MANUAL(weather)은 값 유지, 손 안 댄 DERIVED(timeOfDay)는 null
    expect(mockMutate).toHaveBeenCalledWith({
      weather: '맑음',
      timeOfDay: null,
      season: 'WINTER',
    });
  });

  it('프리필값_그대로_저장시_손안댄_DERIVED필드는_null로_전송된다', async () => {
    // given — 3필드 모두 DERIVED 프리필
    const user = userEvent.setup();
    prefill({
      weatherSource: 'DERIVED',
      timeOfDaySource: 'DERIVED',
      seasonSource: 'DERIVED',
    });
    renderWithProviders(<EnvironmentMetaPanel rawSn={10} />);

    // when — 시간대만 야간(NGT)으로 토글해 저장 트리거(그 외 프리필값은 손대지 않음)
    await user.click(screen.getByRole('radio', { name: '야간' }));
    const saveBtn = screen.getByRole('button', { name: /저장/ });
    await waitFor(() => expect(saveBtn).toBeEnabled());
    await user.click(saveBtn);

    // then — 손 안 댄 DERIVED 프리필(weather/season)은 null, 바꾼 timeOfDay 만 값
    expect(mockMutate).toHaveBeenCalledWith({
      weather: null,
      timeOfDay: 'NGT',
      season: null,
    });
  });

  it('dirty_아니면_저장버튼_비활성', () => {
    // given / when — 프리필만 하고 변경 없음
    renderWithProviders(<EnvironmentMetaPanel rawSn={10} />);

    // then
    expect(screen.getByRole('button', { name: /저장/ })).toBeDisabled();
  });

  it('영상_전환시_로컬상태_동기화', async () => {
    // given — 첫 영상 프리필
    const { rerender } = renderWithProviders(<EnvironmentMetaPanel rawSn={10} />);
    await waitFor(() => expect(screen.getByLabelText('날씨')).toHaveTextContent('맑음'));

    // when — 다른 영상으로 전환(새 프리필)
    prefill({ rawSn: 20, weather: '눈', timeOfDay: 'NGT', season: 'WINTER' });
    rerender(<EnvironmentMetaPanel rawSn={20} />);

    // then — 새 값으로 동기화
    await waitFor(() => expect(screen.getByLabelText('날씨')).toHaveTextContent('눈'));
    expect(screen.getByRole('radio', { name: '야간' })).toHaveAttribute(
      'aria-checked',
      'true',
    );
    expect(screen.getByLabelText('계절')).toHaveTextContent('겨울');
  });

  it('날씨select에_고정코드만_노출_XSS방어', async () => {
    // given — 악성 문자열이 저장돼 있어도 select 옵션은 화이트리스트 고정
    prefill({ weather: '<script>alert(1)</script>' });

    // when
    renderWithProviders(<EnvironmentMetaPanel rawSn={10} />);

    // then — script 요소 미생성, 옵션은 고정 5종만
    expect(document.querySelector('script')).toBeNull();
    const user = userEvent.setup();
    await user.click(screen.getByLabelText('날씨'));
    expect(await screen.findByRole('option', { name: '맑음' })).toBeInTheDocument();
  });
});
