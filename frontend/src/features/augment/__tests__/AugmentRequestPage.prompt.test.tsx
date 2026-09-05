import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import MockAdapter from 'axios-mock-adapter';
import { act, fireEvent, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { apiClient } from '@/lib/api/client';
import {
  AUGMENT_CONDITION_PRESET_IDS,
  AUGMENT_MTDT_CODES,
  AUGMENT_MTDT_FIELD_KEYS,
  AUGMENT_MTDT_FIELD_META,
  AUGMENT_PROMPT_MAX_LENGTH,
} from '@/features/augment/types';
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

interface RequestBody {
  videoIds: number[];
  types: string[];
  mtdt?: Record<string, string>;
  prompt?: string;
}

/**
 * SCR-AUG-001 증강 요청 — **연동명세서 v1.3 정합** 생성 조건 축 + 연타 방어.
 *
 * 배경(2026-08-27 CO):
 * - v1.3 은 생성 조건 다섯 항목을 최상위 `mtdt` 로 옮기고 **허용 코드로 닫았다**. 구 계약의
 *   5필드 자유 문자열 `prompt` 는 계약에서 제외됐고, `prompt` 는 **자유 지시문 문자열**이 됐다.
 * - **이벤트 유형·침수 세부 유형은 걷어냈다**(2026-09-02 CO · ADR-059). 요청 본문에 그 필드
 *   자체가 없고 서버가 중립값을 고정 송신한다. 겨울·야간·우천은 카드가 아니라 **생성 조건
 *   프리셋**이며 증강 종류는 프리셋과 무관하게 단일값 `AUGMENT` 다.
 * - BE 의 중복 요청 차단이 전면 해제돼 있어 연타(오조작) 방어는 **이 화면이 유일한 방어선**이다.
 *   반면 제출이 끝난 뒤의 **의도적 재요청은 정상 동선**이라 막으면 안 된다.
 */
describe('AugmentRequestPage 생성 조건 축 (연동명세서 v1.3)', () => {
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

  const VALID_MTDT: Record<string, string> = {
    시간대: 'NIGHT',
    계절: 'WINTER',
    날씨: 'RAIN',
    지형: 'ROAD',
    심각도: 'HIGH',
  };

  const selectOf = (label: string | RegExp) =>
    screen.getByLabelText(label) as HTMLSelectElement;

  /** 다섯 항목을 고른다. `overrides` 로 특정 항목만 비우거나 바꾼다. */
  const fillMtdt = (overrides: Record<string, string> = {}) => {
    Object.entries(VALID_MTDT).forEach(([label, value]) => {
      fireEvent.change(selectOf(new RegExp(label)), {
        target: { value: overrides[label] ?? value },
      });
    });
  };

  const selectKindAndVideo = async (user: ReturnType<typeof userEvent.setup>) => {
    await user.click(await screen.findByTestId('process-kind-AUGMENT'));
    await user.click(await screen.findByRole('radio', { name: /CCTV-1 선택/ }));
  };

  const lastBody = (): RequestBody =>
    JSON.parse(mock.history.post[mock.history.post.length - 1].data as string);

  it('생성_조건은_최상위_mtdt_객체로_전송된다', async () => {
    mock.onPost('/augments/request').reply(() => okReply());
    const user = userEvent.setup();
    renderWithProviders(<AugmentRequestPage />);

    await selectKindAndVideo(user);
    fillMtdt();

    await user.click(screen.getByTestId('augment-submit'));

    await waitFor(() => expect(mock.history.post).toHaveLength(1));
    const body = lastBody();
    expect(body.mtdt).toEqual({
      time: 'NIGHT',
      season: 'WINTER',
      weather: 'RAIN',
      terrain: 'ROAD',
      severity: 'HIGH',
    });
    expect(body.videoIds).toEqual([1]);
    // 구 계약(5필드 객체 prompt)으로 되돌아가지 않는다.
    expect(body).not.toHaveProperty('prompt');
  });

  it('이벤트_유형_입력이_화면에_없다', async () => {
    // ADR-059 — 침수/산불은 벤더가 배경에 장면을 만들어 넣는 축이라 우리 화면에서 걷어냈다.
    const user = userEvent.setup();
    renderWithProviders(<AugmentRequestPage />);

    await selectKindAndVideo(user);

    expect(screen.queryByLabelText(/이벤트 유형/)).not.toBeInTheDocument();
    expect(screen.queryByTestId('aug-evnt-type-field')).not.toBeInTheDocument();
    expect(screen.queryByTestId('aug-evnt-subtype-field')).not.toBeInTheDocument();
    const block = screen.getByTestId('augment-prompt-block');
    expect(block).not.toHaveTextContent('침수');
    expect(block).not.toHaveTextContent('산불');
  });

  it('이벤트_유형_없이도_요청이_성공하고_그_값이_전송되지_않는다', async () => {
    // 구 동작은 미선택이면 제출 자체가 막혔다 — 그 게이트도 함께 걷어냈다.
    mock.onPost('/augments/request').reply(() => okReply());
    const user = userEvent.setup();
    renderWithProviders(<AugmentRequestPage />);

    await selectKindAndVideo(user);
    fillMtdt();

    expect(screen.getByTestId('augment-submit')).toBeEnabled();
    // 요청 불가 사유 안내도 뜨지 않는다(막을 이유가 없다).
    expect(screen.queryByTestId('augment-block-reason')).not.toBeInTheDocument();

    await user.click(screen.getByTestId('augment-submit'));
    await waitFor(() => expect(mock.history.post).toHaveLength(1));

    // ⚠ BE 는 모르는 필드를 400 이 아니라 조용히 무시한다 — 값 축으로 고정해야 회귀가 잡힌다.
    expect(lastBody()).not.toHaveProperty('evntType');
    expect(lastBody()).not.toHaveProperty('evntSubtype');
  });

  it('생성_조건이_하나라도_비면_제출되지_않는다', async () => {
    mock.onPost('/augments/request').reply(() => okReply());
    const user = userEvent.setup();
    renderWithProviders(<AugmentRequestPage />);

    await selectKindAndVideo(user);
    fillMtdt({ 심각도: '' });

    expect(screen.getByTestId('augment-submit')).toBeDisabled();
    await user.click(screen.getByTestId('augment-submit'));
    expect(mock.history.post).toHaveLength(0);
    // 사용자에게 이유가 보인다 (400 을 대신 보여주지 않는다)
    expect(
      within(screen.getByTestId('augment-mtdt-block')).getByRole('alert'),
    ).toBeInTheDocument();
    expect(screen.getByTestId('augment-block-reason')).toHaveTextContent(/심각도/);
  });

  it('허용_코드_밖의_값을_보낼_수단이_화면에_없다', async () => {
    const user = userEvent.setup();
    renderWithProviders(<AugmentRequestPage />);
    await selectKindAndVideo(user);

    // 다섯 축 — 드롭다운이고 보기는 계약 허용 코드 + 미선택 placeholder 뿐이다.
    AUGMENT_MTDT_FIELD_KEYS.forEach((key) => {
      const el = selectOf(new RegExp(AUGMENT_MTDT_FIELD_META[key].label));
      expect(el.tagName, `${key} 항목`).toBe('SELECT');
      expect(
        Array.from(el.options).map((o) => o.value),
        `${key} 보기`,
      ).toEqual(['', ...AUGMENT_MTDT_CODES[key]]);
    });

  });

  /**
   * 생성 조건 프리셋 — 겨울·야간·우천.
   *
   * 구 동작은 이 셋이 **처리 종류 카드**였다. 카드가 생성 조건의 부분집합이라 "겨울을 고른 뒤
   * 계절을 여름으로" 처럼 둘이 어긋날 수 있었고, 그래서 카드는 사실상 조건의 프리셋 역할밖에
   * 하지 못했다. 이제 그 역할만 남기고 종류는 단일값 `AUGMENT` 로 합쳤다(ADR-059).
   */
  describe('생성 조건 프리셋 — 증강 종류가 아니다', () => {
    const valueOf = (label: string) => selectOf(new RegExp(label)).value;

    /** 증강 AI 카드만 고른다(영상 선택 없이도 조건 폼은 노출된다). */
    const pickAugment = async (user: ReturnType<typeof userEvent.setup>) => {
      await user.click(await screen.findByTestId('process-kind-AUGMENT'));
    };

    const clickPreset = async (
      user: ReturnType<typeof userEvent.setup>,
      preset: 'WINTER' | 'NIGHT' | 'RAIN',
    ) => {
      await user.click(screen.getByTestId(`augment-preset-${preset}`));
    };

    it('프리셋_세_개가_토글_버튼_그룹으로_노출된다', async () => {
      const user = userEvent.setup();
      renderWithProviders(<AugmentRequestPage />);
      await pickAugment(user);

      // Step 1 카드가 이미 radiogroup 이라 프리셋은 role=group + aria-pressed 다(사양).
      const group = screen.getByRole('group', { name: /생성 조건 프리셋/ });
      const buttons = within(group).getAllByRole('button');
      expect(buttons.map((b) => b.textContent)).toEqual(['겨울', '야간', '우천']);
      buttons.forEach((b) => expect(b).toHaveAttribute('aria-pressed', 'false'));
      expect(AUGMENT_CONDITION_PRESET_IDS).toHaveLength(buttons.length);
    });

    it('겨울_프리셋은_계절과_날씨를_채운다', async () => {
      const user = userEvent.setup();
      renderWithProviders(<AugmentRequestPage />);
      await pickAugment(user);

      await clickPreset(user, 'WINTER');

      expect(valueOf('계절')).toBe('WINTER');
      expect(valueOf('날씨')).toBe('SNOW');
      // 나머지는 비어 있다(지어내지 않는다).
      expect(valueOf('시간대')).toBe('');
      expect(valueOf('지형')).toBe('');
      expect(valueOf('심각도')).toBe('');
      expect(screen.getByTestId('augment-preset-WINTER')).toHaveAttribute(
        'aria-pressed',
        'true',
      );
    });

    it('야간_프리셋은_시간대를_채운다', async () => {
      const user = userEvent.setup();
      renderWithProviders(<AugmentRequestPage />);
      await pickAugment(user);

      await clickPreset(user, 'NIGHT');

      expect(valueOf('시간대')).toBe('NIGHT');
      expect(valueOf('계절')).toBe('');
      expect(valueOf('날씨')).toBe('');
    });

    it('우천_프리셋은_날씨를_채운다', async () => {
      const user = userEvent.setup();
      renderWithProviders(<AugmentRequestPage />);
      await pickAugment(user);

      await clickPreset(user, 'RAIN');

      expect(valueOf('날씨')).toBe('RAIN');
      expect(valueOf('시간대')).toBe('');
      expect(valueOf('계절')).toBe('');
    });

    it('프리셋을_바꾸면_이전_프리셋_잔재가_남지_않는다', async () => {
      const user = userEvent.setup();
      renderWithProviders(<AugmentRequestPage />);
      await pickAugment(user);

      await clickPreset(user, 'WINTER');
      expect(valueOf('계절')).toBe('WINTER');

      await clickPreset(user, 'NIGHT');

      // 사용자가 손대지 않은 값이므로 새 프리셋 기준으로 갱신된다.
      // (남겨두면 '야간인데 계절=겨울' 이라는, 사용자가 고른 적 없는 조건이 전송된다)
      expect(valueOf('시간대')).toBe('NIGHT');
      expect(valueOf('계절')).toBe('');
      expect(valueOf('날씨')).toBe('');
      expect(screen.getByTestId('augment-preset-WINTER')).toHaveAttribute(
        'aria-pressed',
        'false',
      );
    });

    it('이미_입력한_항목은_프리셋을_바꿔도_덮어쓰지_않는다', async () => {
      const user = userEvent.setup();
      renderWithProviders(<AugmentRequestPage />);
      await pickAugment(user);

      // given: 야간 프리셋 후 사용자가 계절을 직접 고름
      await clickPreset(user, 'NIGHT');
      fireEvent.change(selectOf(/계절/), { target: { value: 'SPRING' } });

      // when: 겨울로 변경 — 겨울 프리셋은 계절=WINTER 다
      await clickPreset(user, 'WINTER');

      // then: 사용자 선택이 이긴다
      expect(valueOf('계절')).toBe('SPRING');
      // 손대지 않은 날씨는 겨울 프리셋이 적용된다
      expect(valueOf('날씨')).toBe('SNOW');
    });

    it('사용자가_의도적으로_되돌린_항목은_프리셋을_바꿔도_다시_채우지_않는다', async () => {
      const user = userEvent.setup();
      renderWithProviders(<AugmentRequestPage />);
      await pickAugment(user);

      // given: 겨울 프리셋 후 사용자가 날씨를 미선택으로 되돌린다(빈 값도 사용자의 결정이다)
      await clickPreset(user, 'WINTER');
      fireEvent.change(selectOf(/날씨/), { target: { value: '' } });
      expect(valueOf('날씨')).toBe('');

      // when: 우천으로 변경 — 우천 프리셋은 날씨=RAIN 이다
      await clickPreset(user, 'RAIN');

      // then: 되돌린 상태가 유지된다("비었으니 채워도 된다"로 판정하면 여기서 되살아난다)
      expect(valueOf('날씨')).toBe('');
    });

    it('같은_프리셋을_다시_누르면_해제되고_채운_값이_비워진다', async () => {
      const user = userEvent.setup();
      renderWithProviders(<AugmentRequestPage />);
      await pickAugment(user);

      await clickPreset(user, 'WINTER');
      await clickPreset(user, 'WINTER');

      expect(screen.getByTestId('augment-preset-WINTER')).toHaveAttribute(
        'aria-pressed',
        'false',
      );
      expect(valueOf('계절')).toBe('');
      expect(valueOf('날씨')).toBe('');
    });

    it('프리셋이_채운_항목에만_고쳐도_된다는_안내가_붙는다', async () => {
      const user = userEvent.setup();
      renderWithProviders(<AugmentRequestPage />);
      await pickAugment(user);

      await clickPreset(user, 'WINTER');

      // 겨울이 채우는 축에만 안내가 붙는다.
      expect(screen.getByTestId('aug-mtdt-season-preset-hint')).toHaveTextContent(
        /겨울 프리셋으로 채워/,
      );
      expect(screen.getByTestId('aug-mtdt-weather-preset-hint')).toBeInTheDocument();
      expect(
        screen.queryByTestId('aug-mtdt-terrain-preset-hint'),
      ).not.toBeInTheDocument();

      // 사용자가 그 값을 고치면 안내가 사라진다(더 이상 시스템이 채운 값이 아니다).
      fireEvent.change(selectOf(/계절/), { target: { value: 'SPRING' } });
      expect(
        screen.queryByTestId('aug-mtdt-season-preset-hint'),
      ).not.toBeInTheDocument();
    });

    it('프리셋은_전송되는_증강_종류를_바꾸지_않는다', async () => {
      mock.onPost('/augments/request').reply(() => okReply());
      const user = userEvent.setup();
      renderWithProviders(<AugmentRequestPage />);

      await selectKindAndVideo(user);
      await clickPreset(user, 'RAIN');
      fillMtdt();
      await user.click(screen.getByTestId('augment-submit'));

      await waitFor(() => expect(mock.history.post).toHaveLength(1));
      // 프리셋을 무엇으로 고르든 종류는 단일값이다(ADR-059).
      expect(lastBody().types).toEqual(['AUGMENT']);
    });

    it('해상도_변경을_거쳐도_사용자_선택은_보존된다', async () => {
      const user = userEvent.setup();
      renderWithProviders(<AugmentRequestPage />);

      await pickAugment(user);
      fireEvent.change(selectOf(/지형/), { target: { value: 'UNDERPASS' } });

      // 해상도 변경은 조건 폼 자체가 없다
      await user.click(screen.getByTestId('process-kind-RESOLUTION'));
      expect(screen.queryByTestId('augment-prompt-block')).not.toBeInTheDocument();

      await pickAugment(user);
      expect(valueOf('지형')).toBe('UNDERPASS');
    });

    it('프리셋에_따라_서로_다른_mtdt_가_전송된다', async () => {
      mock.onPost('/augments/request').reply(() => okReply());
      const user = userEvent.setup();

      /**
       * 한 프리셋을 **새 화면 진입에서** 요청하고 전송된 페이로드를 돌려준다.
       *
       * 매번 새로 렌더하는 이유: 사용자가 고른 항목은 프리셋을 바꿔도 보존되므로(위 테스트),
       * 한 화면에서 세 프리셋을 연달아 채우면 첫 프리셋에서 고른 값이 그대로 남아 비교가 흐려진다.
       * 여기서 확인하려는 것은 "같은 사용자 선택에 프리셋만 다를 때 요청이 갈리는가" 다.
       */
      const submitFrom = async (preset: 'WINTER' | 'NIGHT' | 'RAIN') => {
        const { unmount } = renderWithProviders(<AugmentRequestPage />);
        await selectKindAndVideo(user);
        await clickPreset(user, preset);

        // 사용자는 프리셋과 무관하게 같은 값을 넣는다 — 프리셋이 비워 둔 칸만 고른다.
        const fallback: Record<string, string> = {
          시간대: 'DAY',
          계절: 'SUMMER',
          날씨: 'CLEAR',
        };
        Object.entries(fallback).forEach(([label, value]) => {
          if (valueOf(label) === '') {
            fireEvent.change(selectOf(new RegExp(label)), { target: { value } });
          }
        });
        fireEvent.change(selectOf(/지형/), { target: { value: 'UNDERPASS' } });
        fireEvent.change(selectOf(/심각도/), { target: { value: 'MEDIUM' } });

        await waitFor(() =>
          expect(screen.getByTestId('augment-submit')).toBeEnabled(),
        );
        const before = mock.history.post.length;
        await user.click(screen.getByTestId('augment-submit'));
        await waitFor(() => expect(mock.history.post).toHaveLength(before + 1));
        const body = lastBody();
        unmount();
        return body;
      };

      const winter = await submitFrom('WINTER');
      const night = await submitFrom('NIGHT');
      const rain = await submitFrom('RAIN');

      // 이 프리셋의 존재 이유: 고른 프리셋이 실제로 페이로드를 가른다.
      expect(winter.mtdt).not.toEqual(night.mtdt);
      expect(night.mtdt).not.toEqual(rain.mtdt);
      expect(winter.mtdt).not.toEqual(rain.mtdt);
      expect(winter.mtdt?.season).toBe('WINTER');
      expect(night.mtdt?.time).toBe('NIGHT');
      expect(rain.mtdt?.weather).toBe('RAIN');

      // 종류는 프리셋과 무관하게 같다.
      [winter, night, rain].forEach((b) => expect(b.types).toEqual(['AUGMENT']));
    });
  });

  describe('자유 지시문 — 선택 입력', () => {
    it('비워_두면_prompt_키_자체를_보내지_않는다', async () => {
      mock.onPost('/augments/request').reply(() => okReply());
      const user = userEvent.setup();
      renderWithProviders(<AugmentRequestPage />);

      await selectKindAndVideo(user);
      fillMtdt();
      await user.click(screen.getByTestId('augment-submit'));

      await waitFor(() => expect(mock.history.post).toHaveLength(1));
      expect(lastBody()).not.toHaveProperty('prompt');
    });

    it('입력하면_문자열로_전송된다', async () => {
      mock.onPost('/augments/request').reply(() => okReply());
      const user = userEvent.setup();
      renderWithProviders(<AugmentRequestPage />);

      await selectKindAndVideo(user);
      fillMtdt();
      fireEvent.change(screen.getByLabelText('자유 지시문'), {
        target: { value: '  원본 카메라 시점을 유지해줘.  ' },
      });
      await user.click(screen.getByTestId('augment-submit'));

      await waitFor(() => expect(mock.history.post).toHaveLength(1));
      // BE 와 같은 기준으로 정규화한 값이 나간다(앞뒤 공백 제거).
      expect(lastBody().prompt).toBe('원본 카메라 시점을 유지해줘.');
    });

    it('상한을_넘으면_제출되지_않는다', async () => {
      mock.onPost('/augments/request').reply(() => okReply());
      const user = userEvent.setup();
      renderWithProviders(<AugmentRequestPage />);

      await selectKindAndVideo(user);
      fillMtdt();
      // maxLength 는 붙여넣기 일부 경로를 막지 못하므로 검증도 함께 건다(BE 는 자르지 않고 400).
      fireEvent.change(screen.getByLabelText('자유 지시문'), {
        target: { value: 'A'.repeat(AUGMENT_PROMPT_MAX_LENGTH + 1) },
      });

      expect(screen.getByTestId('augment-submit')).toBeDisabled();
      await user.click(screen.getByTestId('augment-submit'));
      expect(mock.history.post).toHaveLength(0);

      // 상한까지 줄이면 제출 가능해진다 (경계)
      fireEvent.change(screen.getByLabelText('자유 지시문'), {
        target: { value: 'A'.repeat(AUGMENT_PROMPT_MAX_LENGTH) },
      });
      await waitFor(() => expect(screen.getByTestId('augment-submit')).toBeEnabled());
    });
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
    fillMtdt();

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

  it('증강_종류는_단일값_AUGMENT_로_전송된다', async () => {
    mock.onPost('/augments/request').reply(() => okReply());
    const user = userEvent.setup();
    renderWithProviders(<AugmentRequestPage />);

    // 생성 조건의 계절을 WINTER 로 둬도 종류는 달라지지 않는다.
    await selectKindAndVideo(user);
    fillMtdt();
    await user.click(screen.getByTestId('augment-submit'));

    await waitFor(() => expect(mock.history.post).toHaveLength(1));
    // types 는 생성 조건에서 파생되지 않는다 — 파생하면 산출물 경로가 조작될 수 있다.
    expect(lastBody().types).toEqual(['AUGMENT']);
  });

  it('제출_완료_후에는_같은_조건으로_다시_요청할_수_있다', async () => {
    mock.onPost('/augments/request').reply(() => okReply());
    const user = userEvent.setup();
    renderWithProviders(<AugmentRequestPage />);

    await selectKindAndVideo(user);
    fillMtdt();

    await user.click(screen.getByTestId('augment-submit'));
    await waitFor(() => expect(mock.history.post).toHaveLength(1));

    // 의도적 재요청 — 같은 조건 그대로 다시 제출할 수 있어야 한다.
    await waitFor(() => expect(screen.getByTestId('augment-submit')).toBeEnabled());
    await user.click(screen.getByTestId('augment-submit'));
    await waitFor(() => expect(mock.history.post).toHaveLength(2));

    const first = JSON.parse(mock.history.post[0].data as string) as RequestBody;
    const second = lastBody();
    expect(second).toEqual(first);
  });

  it('해상도_변경은_생성_조건_입력이_필요하지_않다', async () => {
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

    // 조건 블록은 외부 위탁(증강) 전용이라 노출되지 않고, 제출도 막히지 않는다.
    expect(screen.queryByTestId('augment-prompt-block')).not.toBeInTheDocument();
    expect(screen.getByTestId('augment-submit')).toBeEnabled();
  });

  it('조건_입력에_개인정보_경고와_라벨이_연결되어_있다', async () => {
    const user = userEvent.setup();
    renderWithProviders(<AugmentRequestPage />);

    await selectKindAndVideo(user);

    const block = screen.getByTestId('augment-prompt-block');
    // 외부 AI 전송 안내 (보안 리뷰 권고)
    expect(block).toHaveTextContent(/개인식별정보/);
    expect(block).toHaveTextContent(/외부/);

    // 모든 입력이 label 과 연결되어 있다 (WCAG 2.1 AA / htmlFor)
    Object.keys(VALID_MTDT).forEach((label) => {
      expect(selectOf(new RegExp(label))).toHaveAttribute('id');
    });
    expect(screen.getByLabelText('자유 지시문')).toHaveAttribute('id');
  });
});
