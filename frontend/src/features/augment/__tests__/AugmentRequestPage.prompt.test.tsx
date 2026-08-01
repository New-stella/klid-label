import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import MockAdapter from 'axios-mock-adapter';
import { act, fireEvent, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { apiClient } from '@/lib/api/client';
import { AugmentRequestPage } from '@/pages/AugmentRequestPage';
import { renderWithProviders } from '@/test/renderWithProviders';
import { useAuthStore } from '@/stores/useAuthStore';

const navigateMock = vi.fn();

vi.mock('react-router-dom', async () => {
  const actual = await vi.importActual<typeof import('react-router-dom')>(
    'react-router-dom',
  );
  return { ...actual, useNavigate: () => navigateMock };
});

/** 보이지 않는 문자는 코드포인트로 만든다(소스 리터럴 금지 — 리뷰에서 식별 불가). */
const cp = (codePoint: number) => String.fromCodePoint(codePoint);
const ZWSP = cp(0x200b);
const NBSP = cp(0x00a0);
const BOM = cp(0xfeff);

interface RequestBody {
  videoIds: number[];
  types: string[];
  prompt?: Record<string, string>;
}

/**
 * SCR-AUG-001 증강 요청 — 구조화 프롬프트 5필드 + 연타 방어 (Phase 4).
 *
 * 배경:
 * - BE 가 `prompt`(time/season/weather/terrain/severity)를 **필수**로 요구한다.
 *   FE 가 안 보내면 모든 요청이 400 INVALID_INPUT 이다.
 * - BE 의 중복 요청 차단(유니크 인덱스·409·속도 제한)이 **전면 해제**됐다.
 *   따라서 연타(오조작) 방어는 **이 화면이 유일한 방어선**이다.
 *   반면 제출이 끝난 뒤의 **의도적 재요청은 정상 동선**이라 막으면 안 된다.
 */
describe('AugmentRequestPage 프롬프트 5필드 + 연타 방어 (Phase 4)', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    navigateMock.mockClear();
    mock = new MockAdapter(apiClient);
    useAuthStore.setState({
      token: 'dummy-token',
      claims: { sub: 'u', role: 'REVIEWER', channel: 'INTERNAL', exp: 9999999999 },
    });
    mock.onGet('/augments').reply(200, {
      success: true,
      data: { content: [], totalElements: 0, totalPages: 0, number: 0, size: 6 },
      message: null,
      errorCode: null,
    });
    mock.onGet('/videos').reply(200, {
      success: true,
      data: {
        content: [
          {
            id: 1,
            cctvName: 'CCTV-1',
            vmsClipId: 'V1',
            eventName: '쓰러짐',
            eventTypeCd: 'FALL',
            frameCount: 100,
            status: 'COMPLETED',
            capturedAt: '2026-05-01T12:00:00Z',
          },
        ],
        totalElements: 1,
        totalPages: 1,
        number: 0,
        size: 20,
      },
      message: null,
      errorCode: null,
    });
  });

  afterEach(() => {
    mock.restore();
    useAuthStore.getState().clear();
  });

  const okReply = (jobId = 7) => [
    200,
    {
      success: true,
      data: {
        jobId,
        requestedAt: '2026-07-31T10:00:00Z',
        videoCount: 1,
        typeCount: 1,
      },
      message: null,
      errorCode: null,
    },
  ];

  const VALID_PROMPT = {
    시간대: 'NIGHT',
    계절: 'WINTER',
    날씨: 'RAIN',
    지형: 'ROAD',
    심각도: 'HIGH',
  } as const;

  /** 5필드를 채운다. 특수문자(보이지 않는 문자 등)도 확정적으로 넣기 위해 fireEvent.change 사용. */
  const fillPrompt = (overrides: Partial<Record<string, string>> = {}) => {
    Object.entries(VALID_PROMPT).forEach(([label, value]) => {
      const input = screen.getByLabelText(new RegExp(label));
      fireEvent.change(input, {
        target: { value: overrides[label] ?? value },
      });
      fireEvent.blur(input);
    });
  };

  const selectKindAndVideo = async (
    user: ReturnType<typeof userEvent.setup>,
    kind: 'WINTER' | 'NIGHT' | 'RAIN' = 'WINTER',
  ) => {
    await user.click(await screen.findByTestId(`process-kind-${kind}`));
    await user.click(await screen.findByRole('radio', { name: /CCTV-1 선택/ }));
  };

  const lastBody = (): RequestBody =>
    JSON.parse(mock.history.post[mock.history.post.length - 1].data as string);

  it('프롬프트_5필드가_요청_페이로드에_담긴다', async () => {
    mock.onPost('/augments/request').reply(() => okReply());
    const user = userEvent.setup();
    renderWithProviders(<AugmentRequestPage />);

    // given: 종류·영상 선택 + 생성 조건 5필드 입력
    await selectKindAndVideo(user, 'WINTER');
    fillPrompt();

    // when
    await user.click(screen.getByTestId('augment-submit'));

    // then
    await waitFor(() => expect(mock.history.post).toHaveLength(1));
    const body = lastBody();
    expect(body.prompt).toEqual({
      time: 'NIGHT',
      season: 'WINTER',
      weather: 'RAIN',
      terrain: 'ROAD',
      severity: 'HIGH',
    });
    expect(body.videoIds).toEqual([1]);
  });

  it('제출_중에는_버튼이_비활성이라_연타해도_1회만_전송된다', async () => {
    // given: 응답을 보류시켜 "제출 중" 상태를 고정한다.
    const deferred: { release: (() => void) | null } = { release: null };
    mock.onPost('/augments/request').reply(
      () =>
        new Promise((resolve) => {
          deferred.release = () => resolve(okReply());
        }),
    );
    const user = userEvent.setup();
    renderWithProviders(<AugmentRequestPage />);

    await selectKindAndVideo(user);
    fillPrompt();

    // when: 연타 — **리렌더가 끼어들 틈 없이** 한 act 안에서 연속 클릭한다.
    // 이렇게 해야 "버튼 비활성"(상태 기반) 이 아직 반영되지 않은 구간을 재현할 수 있고,
    // 동기 락(submitLockRef)이 실제로 방어하고 있는지 검증된다.
    const submit = screen.getByTestId('augment-submit');
    act(() => {
      submit.click();
      submit.click();
      submit.click();
      submit.click();
    });

    // then: 1회만 전송되고 버튼은 비활성
    await waitFor(() => expect(mock.history.post).toHaveLength(1));
    expect(screen.getByTestId('augment-submit')).toBeDisabled();

    // 응답 후에도 추가 전송이 없어야 한다(누적 클릭이 뒤늦게 반영되지 않음)
    deferred.release?.();
    await waitFor(() => expect(navigateMock).toHaveBeenCalled());
    expect(mock.history.post).toHaveLength(1);
  });

  it('필드가_하나라도_비면_제출되지_않는다', async () => {
    mock.onPost('/augments/request').reply(() => okReply());
    const user = userEvent.setup();
    renderWithProviders(<AugmentRequestPage />);

    await selectKindAndVideo(user);
    fillPrompt({ 심각도: '' });

    expect(screen.getByTestId('augment-submit')).toBeDisabled();
    await user.click(screen.getByTestId('augment-submit'));
    expect(mock.history.post).toHaveLength(0);
    // 사용자에게 이유가 보인다 (400 을 대신 보여주지 않는다)
    expect(
      within(screen.getByTestId('augment-prompt-block')).getByRole('alert'),
    ).toBeInTheDocument();
  });

  it('공백만_입력한_필드는_제출되지_않는다', async () => {
    mock.onPost('/augments/request').reply(() => okReply());
    const user = userEvent.setup();
    renderWithProviders(<AugmentRequestPage />);

    await selectKindAndVideo(user);
    fillPrompt({ 지형: '    ' });

    expect(screen.getByTestId('augment-submit')).toBeDisabled();
    await user.click(screen.getByTestId('augment-submit'));
    expect(mock.history.post).toHaveLength(0);
  });

  it('보이지_않는_문자만_입력한_필드는_제출되지_않는다', async () => {
    mock.onPost('/augments/request').reply(() => okReply());
    const user = userEvent.setup();
    renderWithProviders(<AugmentRequestPage />);

    await selectKindAndVideo(user);
    // ZWSP + NBSP + BOM — @NotBlank(trim 기준)는 통과하지만 BE 가 400 으로 거부하는 값
    fillPrompt({ 날씨: ZWSP + NBSP + BOM });

    expect(screen.getByTestId('augment-submit')).toBeDisabled();
    await user.click(screen.getByTestId('augment-submit'));
    expect(mock.history.post).toHaveLength(0);
  });

  it('필드_길이_상한_50자를_넘으면_제출되지_않는다', async () => {
    mock.onPost('/augments/request').reply(() => okReply());
    const user = userEvent.setup();
    renderWithProviders(<AugmentRequestPage />);

    await selectKindAndVideo(user);
    fillPrompt({ 계절: 'A'.repeat(51) });

    expect(screen.getByTestId('augment-submit')).toBeDisabled();
    await user.click(screen.getByTestId('augment-submit'));
    expect(mock.history.post).toHaveLength(0);

    // 50자로 줄이면 제출 가능해진다 (상한 경계)
    fillPrompt({ 계절: 'A'.repeat(50) });
    await waitFor(() =>
      expect(screen.getByTestId('augment-submit')).toBeEnabled(),
    );
  });

  it('증강_종류는_여전히_사용자가_선택한다', async () => {
    mock.onPost('/augments/request').reply(() => okReply());
    const user = userEvent.setup();
    renderWithProviders(<AugmentRequestPage />);

    // 카드는 RAIN 을 고르고 프롬프트 time 은 NIGHT / season 은 WINTER 로 둔다.
    await selectKindAndVideo(user, 'RAIN');
    fillPrompt();
    await user.click(screen.getByTestId('augment-submit'));

    await waitFor(() => expect(mock.history.post).toHaveLength(1));
    // types 는 프롬프트에서 파생되지 않고 선택한 카드 값 그대로다.
    expect(lastBody().types).toEqual(['RAIN']);
  });

  it('제출_완료_후에는_같은_조건으로_다시_요청할_수_있다', async () => {
    mock.onPost('/augments/request').reply(() => okReply());
    const user = userEvent.setup();
    renderWithProviders(<AugmentRequestPage />);

    await selectKindAndVideo(user);
    fillPrompt();

    await user.click(screen.getByTestId('augment-submit'));
    await waitFor(() => expect(mock.history.post).toHaveLength(1));

    // 의도적 재요청 — 같은 조건 그대로 다시 제출할 수 있어야 한다.
    await waitFor(() =>
      expect(screen.getByTestId('augment-submit')).toBeEnabled(),
    );
    await user.click(screen.getByTestId('augment-submit'));
    await waitFor(() => expect(mock.history.post).toHaveLength(2));

    const first = JSON.parse(mock.history.post[0].data as string) as RequestBody;
    const second = lastBody();
    expect(second).toEqual(first);
  });

  it('해상도_변경은_프롬프트_입력이_필요하지_않다', async () => {
    mock.onPost(/\/videos\/\d+\/resolution/).reply(201, {
      success: true,
      data: {
        derivatives: [
          {
            rawSn: 501,
            goalResCd: 'RESL_720P',
            targetW: 1280,
            targetH: 720,
            status: 'CREATED',
          },
        ],
      },
      message: null,
      errorCode: null,
    });
    const user = userEvent.setup();
    renderWithProviders(<AugmentRequestPage />);

    await user.click(await screen.findByTestId('process-kind-RESOLUTION'));
    await user.click(await screen.findByRole('radio', { name: /CCTV-1 선택/ }));

    // 프롬프트 블록은 외부 위탁(증강) 전용이라 노출되지 않고, 제출도 막히지 않는다.
    expect(screen.queryByTestId('augment-prompt-block')).not.toBeInTheDocument();
    expect(screen.getByTestId('augment-submit')).toBeEnabled();
  });

  it('프롬프트_입력에_개인정보_경고와_라벨이_연결되어_있다', async () => {
    const user = userEvent.setup();
    renderWithProviders(<AugmentRequestPage />);

    await selectKindAndVideo(user);

    const block = screen.getByTestId('augment-prompt-block');
    // 외부 AI 전송 안내 (보안 리뷰 권고)
    expect(block).toHaveTextContent(/개인식별정보/);
    expect(block).toHaveTextContent(/외부/);

    // 모든 입력이 label 과 연결되어 있다 (WCAG 2.1 AA / htmlFor)
    Object.keys(VALID_PROMPT).forEach((label) => {
      const input = screen.getByLabelText(new RegExp(label));
      expect(input).toHaveAttribute('id');
      expect(input.tagName).toBe('INPUT');
    });
  });
});
