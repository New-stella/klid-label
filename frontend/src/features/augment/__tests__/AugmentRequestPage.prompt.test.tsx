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

  /**
   * 증강 3종 차별화 — 유형별 생성 조건 프리필 (Phase 6).
   *
   * 배경(실측): 외부 위탁 요청 바디(`GenAiJobSubmitRequest`)에 **증강 유형 필드가 없다**.
   * 전송되는 값 중 유형에 따라 달라질 수 있는 것은 `prompt` 뿐이므로, 유형이 prompt 에
   * 반영되지 않으면 WINTER/NIGHT/RAIN 이 **바이트 단위로 동일한 요청**이 되어 3종을 나눈
   * 의미가 사라진다. 프리필은 그 연결을 FE 에서 복구한다.
   *
   * 경계: 프리필은 **기본값**일 뿐 강제가 아니다 — REVIEWER 가 생성 조건을 조절할 수 있어야
   * 한다는 확정 정책(2026-07-31)을 지키려면 ①수정 가능하고 ②이미 손댄 값은 보존돼야 한다.
   */
  describe('증강 유형별 생성 조건 프리필 (Phase 6)', () => {
    const valueOf = (label: string) =>
      (screen.getByLabelText(new RegExp(label)) as HTMLInputElement).value;

    /** 종류 카드만 고른다(영상 선택 없이도 프롬프트 폼은 노출된다). */
    const pickKind = async (
      user: ReturnType<typeof userEvent.setup>,
      kind: 'WINTER' | 'NIGHT' | 'RAIN' | 'RESOLUTION',
    ) => {
      await user.click(await screen.findByTestId(`process-kind-${kind}`));
    };

    it('유형을_WINTER_로_고르면_계절과_날씨가_채워진다', async () => {
      const user = userEvent.setup();
      renderWithProviders(<AugmentRequestPage />);

      // when
      await pickKind(user, 'WINTER');

      // then: 겨울은 계절·날씨 축이다. 나머지는 비어 있다(지어내지 않는다).
      expect(valueOf('계절')).toBe('겨울');
      expect(valueOf('날씨')).toBe('눈');
      expect(valueOf('시간대')).toBe('');
      expect(valueOf('지형')).toBe('');
      expect(valueOf('심각도')).toBe('');
    });

    it('유형을_NIGHT_로_고르면_시간대가_채워진다', async () => {
      const user = userEvent.setup();
      renderWithProviders(<AugmentRequestPage />);

      await pickKind(user, 'NIGHT');

      expect(valueOf('시간대')).toBe('야간');
      expect(valueOf('계절')).toBe('');
      expect(valueOf('날씨')).toBe('');
    });

    it('유형을_RAIN_으로_고르면_날씨가_채워진다', async () => {
      const user = userEvent.setup();
      renderWithProviders(<AugmentRequestPage />);

      await pickKind(user, 'RAIN');

      expect(valueOf('날씨')).toBe('비');
      expect(valueOf('시간대')).toBe('');
      expect(valueOf('계절')).toBe('');
    });

    it('유형을_바꾸면_이전_유형의_프리필_잔재가_남지_않는다', async () => {
      const user = userEvent.setup();
      renderWithProviders(<AugmentRequestPage />);

      // given: 겨울(계절=겨울, 날씨=눈)
      await pickKind(user, 'WINTER');
      expect(valueOf('계절')).toBe('겨울');

      // when: 야간으로 변경
      await pickKind(user, 'NIGHT');

      // then: 사용자가 손대지 않은 값이므로 새 유형 기준으로 갱신된다.
      //       (남겨두면 '야간인데 계절=겨울' 이라는, 사용자가 고른 적 없는 조건이 전송된다)
      expect(valueOf('시간대')).toBe('야간');
      expect(valueOf('계절')).toBe('');
      expect(valueOf('날씨')).toBe('');
    });

    it('사용자가_이미_입력한_필드는_유형을_바꿔도_덮어쓰지_않는다', async () => {
      const user = userEvent.setup();
      renderWithProviders(<AugmentRequestPage />);

      // given: 야간 선택 후 사용자가 계절을 직접 입력
      await pickKind(user, 'NIGHT');
      const season = screen.getByLabelText(/계절/);
      fireEvent.change(season, { target: { value: '초봄' } });

      // when: 겨울로 변경 — 겨울 프리필은 계절=겨울 이다
      await pickKind(user, 'WINTER');

      // then: 사용자 입력이 이긴다
      expect(valueOf('계절')).toBe('초봄');
      // 손대지 않은 날씨는 겨울 프리필이 적용된다
      expect(valueOf('날씨')).toBe('눈');
    });

    it('사용자가_의도적으로_지운_필드는_유형을_바꿔도_다시_채우지_않는다', async () => {
      const user = userEvent.setup();
      renderWithProviders(<AugmentRequestPage />);

      // given: 겨울 프리필 후 사용자가 날씨를 지운다(빈 값도 사용자의 결정이다)
      await pickKind(user, 'WINTER');
      const weather = screen.getByLabelText(/날씨/);
      fireEvent.change(weather, { target: { value: '' } });
      expect(valueOf('날씨')).toBe('');

      // when: 우천으로 변경 — 우천 프리필은 날씨=비 다
      await pickKind(user, 'RAIN');

      // then: 지운 상태가 유지된다("비었으니 채워도 된다"로 판정하면 여기서 되살아난다)
      expect(valueOf('날씨')).toBe('');
    });

    it('프리필된_값을_사용자가_수정할_수_있다', async () => {
      const user = userEvent.setup();
      renderWithProviders(<AugmentRequestPage />);

      await pickKind(user, 'WINTER');
      const season = screen.getByLabelText(/계절/);
      fireEvent.change(season, { target: { value: '늦겨울' } });

      expect(valueOf('계절')).toBe('늦겨울');
      // 같은 유형을 다시 눌러도(재선택) 사용자 값을 되돌리지 않는다
      await pickKind(user, 'WINTER');
      expect(valueOf('계절')).toBe('늦겨울');
    });

    it('해상도_변경을_거쳐도_사용자_입력은_보존된다', async () => {
      const user = userEvent.setup();
      renderWithProviders(<AugmentRequestPage />);

      await pickKind(user, 'WINTER');
      fireEvent.change(screen.getByLabelText(/지형/), {
        target: { value: '교차로' },
      });

      // 해상도 변경은 프롬프트 폼 자체가 없다
      await pickKind(user, 'RESOLUTION');
      expect(screen.queryByTestId('augment-prompt-block')).not.toBeInTheDocument();

      await pickKind(user, 'WINTER');
      expect(valueOf('지형')).toBe('교차로');
    });

    it('세_유형이_서로_다른_prompt_로_전송된다', async () => {
      mock.onPost('/augments/request').reply(() => okReply());
      const user = userEvent.setup();

      /**
       * 한 유형을 **새 화면 진입에서** 요청하고 전송된 페이로드를 돌려준다.
       *
       * 매번 새로 렌더하는 이유: 사용자가 채운 필드는 유형을 바꿔도 보존되므로(위 테스트),
       * 한 화면에서 세 유형을 연달아 채우면 첫 유형에서 채운 값이 그대로 남아 비교가 흐려진다.
       * 여기서 확인하려는 것은 "같은 사용자 입력에 유형만 다를 때 요청이 갈리는가" 다.
       */
      const submitFrom = async (kind: 'WINTER' | 'NIGHT' | 'RAIN') => {
        const { unmount } = renderWithProviders(<AugmentRequestPage />);
        await pickKind(user, kind);
        await user.click(await screen.findByRole('radio', { name: /CCTV-1 선택/ }));

        // 사용자는 유형과 무관하게 같은 값을 넣는다 — 프리필이 비워 둔 칸만 채운다.
        ['시간대', '계절', '날씨'].forEach((label) => {
          if (valueOf(label) === '') {
            fireEvent.change(screen.getByLabelText(new RegExp(label)), {
              target: { value: '미지정' },
            });
          }
        });
        fireEvent.change(screen.getByLabelText(/지형/), {
          target: { value: '교차로' },
        });
        fireEvent.change(screen.getByLabelText(/심각도/), {
          target: { value: '보통' },
        });

        await waitFor(() =>
          expect(screen.getByTestId('augment-submit')).toBeEnabled(),
        );
        const before = mock.history.post.length;
        await user.click(screen.getByTestId('augment-submit'));
        await waitFor(() =>
          expect(mock.history.post).toHaveLength(before + 1),
        );
        const body = lastBody();
        unmount();
        return body;
      };

      const winter = await submitFrom('WINTER');
      const night = await submitFrom('NIGHT');
      const rain = await submitFrom('RAIN');

      // 이 Phase 의 존재 이유: 유형이 실제로 페이로드를 가른다.
      expect(winter.prompt).not.toEqual(night.prompt);
      expect(night.prompt).not.toEqual(rain.prompt);
      expect(winter.prompt).not.toEqual(rain.prompt);
      expect(winter.prompt?.season).toBe('겨울');
      expect(night.prompt?.time).toBe('야간');
      expect(rain.prompt?.weather).toBe('비');
    });
  });
});
