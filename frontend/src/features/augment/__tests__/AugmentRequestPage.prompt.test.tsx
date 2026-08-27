import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import MockAdapter from 'axios-mock-adapter';
import { act, fireEvent, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { apiClient } from '@/lib/api/client';
import {
  AUGMENT_EVENT_TYPES,
  AUGMENT_FLOOD_SUBTYPES,
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
  evntType?: string;
  evntSubtype?: string;
  mtdt?: Record<string, string>;
  prompt?: string;
}

/**
 * SCR-AUG-001 증강 요청 — **연동명세서 v1.3 정합** 생성 조건 축 + 연타 방어.
 *
 * 배경(2026-08-27 CO):
 * - v1.3 은 생성 조건 다섯 항목을 최상위 `mtdt` 로 옮기고 **허용 코드로 닫았다**. 구 계약의
 *   5필드 자유 문자열 `prompt` 는 계약에서 제외됐고, `prompt` 는 **자유 지시문 문자열**이 됐다.
 * - **이벤트 유형(`evntType`)이 필수**이며 관제 이벤트 코드에서 변환하지 않고 요청자가 고른
 *   값을 그대로 중계한다. 침수 세부 유형은 침수일 때만 허용된다(산불과 함께 보내면 400).
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

  const pickEventType = (value = 'FLOOD') => {
    fireEvent.change(selectOf(/이벤트 유형/), { target: { value } });
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

  it('생성_조건은_최상위_mtdt_객체로_전송된다', async () => {
    mock.onPost('/augments/request').reply(() => okReply());
    const user = userEvent.setup();
    renderWithProviders(<AugmentRequestPage />);

    await selectKindAndVideo(user, 'WINTER');
    pickEventType();
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

  it('이벤트_유형이_요청_본문에_실린다', async () => {
    mock.onPost('/augments/request').reply(() => okReply());
    const user = userEvent.setup();
    renderWithProviders(<AugmentRequestPage />);

    await selectKindAndVideo(user, 'WINTER');
    pickEventType('WILDFIRE');
    fillMtdt();
    await user.click(screen.getByTestId('augment-submit'));

    await waitFor(() => expect(mock.history.post).toHaveLength(1));
    // 영상의 관제 이벤트 코드(FALL)에서 변환하지 않고 화면에서 고른 값 그대로다.
    expect(lastBody().evntType).toBe('WILDFIRE');
  });

  it('이벤트_유형을_고르지_않으면_제출되지_않는다', async () => {
    mock.onPost('/augments/request').reply(() => okReply());
    const user = userEvent.setup();
    renderWithProviders(<AugmentRequestPage />);

    await selectKindAndVideo(user, 'WINTER');
    fillMtdt(); // 생성 조건은 다 골랐지만 이벤트 유형은 미선택

    expect(screen.getByTestId('augment-submit')).toBeDisabled();
    await user.click(screen.getByTestId('augment-submit'));
    expect(mock.history.post).toHaveLength(0);
    // 왜 못 누르는지 알려준다 — 드롭다운은 "건드리지 않으면" 필드 오류가 뜰 계기가 없다.
    expect(screen.getByTestId('augment-block-reason')).toHaveTextContent(
      /이벤트 유형/,
    );
  });

  it('생성_조건이_하나라도_비면_제출되지_않는다', async () => {
    mock.onPost('/augments/request').reply(() => okReply());
    const user = userEvent.setup();
    renderWithProviders(<AugmentRequestPage />);

    await selectKindAndVideo(user);
    pickEventType();
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

    // 이벤트 유형도 같다.
    const evnt = selectOf(/이벤트 유형/);
    expect(evnt.tagName).toBe('SELECT');
    expect(Array.from(evnt.options).map((o) => o.value)).toEqual([
      '',
      ...AUGMENT_EVENT_TYPES,
    ]);
  });

  describe('침수 세부 유형 — 침수일 때만', () => {
    it('침수를_고르면_세부_유형이_나타나고_함께_전송된다', async () => {
      mock.onPost('/augments/request').reply(() => okReply());
      const user = userEvent.setup();
      renderWithProviders(<AugmentRequestPage />);

      await selectKindAndVideo(user);
      pickEventType('FLOOD');

      const subtype = selectOf(/침수 세부 유형/);
      expect(Array.from(subtype.options).map((o) => o.value)).toEqual([
        '',
        ...AUGMENT_FLOOD_SUBTYPES,
      ]);
      fireEvent.change(subtype, { target: { value: 'UNDERPASS_FLOOD' } });
      fillMtdt();

      await user.click(screen.getByTestId('augment-submit'));
      await waitFor(() => expect(mock.history.post).toHaveLength(1));
      expect(lastBody().evntSubtype).toBe('UNDERPASS_FLOOD');
    });

    it('산불을_고르면_세부_유형이_노출되지_않고_전송되지도_않는다', async () => {
      mock.onPost('/augments/request').reply(() => okReply());
      const user = userEvent.setup();
      renderWithProviders(<AugmentRequestPage />);

      await selectKindAndVideo(user);
      pickEventType('WILDFIRE');

      // 계약에 산불 세부 코드가 없다 — 함께 보내면 BE 가 400 이다.
      expect(screen.queryByTestId('aug-evnt-subtype-field')).not.toBeInTheDocument();

      fillMtdt();
      await user.click(screen.getByTestId('augment-submit'));
      await waitFor(() => expect(mock.history.post).toHaveLength(1));
      expect(lastBody()).not.toHaveProperty('evntSubtype');
    });

    it('침수에서_산불로_바꾸면_이전_세부_유형이_남지_않는다', async () => {
      mock.onPost('/augments/request').reply(() => okReply());
      const user = userEvent.setup();
      renderWithProviders(<AugmentRequestPage />);

      await selectKindAndVideo(user);
      // given: 침수 + 세부 유형 선택
      pickEventType('FLOOD');
      fireEvent.change(selectOf(/침수 세부 유형/), {
        target: { value: 'ROAD_FLOOD' },
      });

      // when: 산불로 바꾼다
      pickEventType('WILDFIRE');
      fillMtdt();
      await user.click(screen.getByTestId('augment-submit'));

      // then: 보이지 않는 값이 상태에만 살아남아 전송되지 않는다
      await waitFor(() => expect(mock.history.post).toHaveLength(1));
      expect(lastBody()).not.toHaveProperty('evntSubtype');

      // 다시 침수로 돌아와도 고른 적 없는 값이 되살아나지 않는다
      pickEventType('FLOOD');
      expect(selectOf(/침수 세부 유형/).value).toBe('');
    });
  });

  describe('자유 지시문 — 선택 입력', () => {
    it('비워_두면_prompt_키_자체를_보내지_않는다', async () => {
      mock.onPost('/augments/request').reply(() => okReply());
      const user = userEvent.setup();
      renderWithProviders(<AugmentRequestPage />);

      await selectKindAndVideo(user);
      pickEventType();
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
      pickEventType();
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
      pickEventType();
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
    pickEventType();
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

  it('증강_종류는_여전히_사용자가_선택한다', async () => {
    mock.onPost('/augments/request').reply(() => okReply());
    const user = userEvent.setup();
    renderWithProviders(<AugmentRequestPage />);

    // 카드는 RAIN 을 고르고 생성 조건의 계절은 WINTER 로 둔다.
    await selectKindAndVideo(user, 'RAIN');
    pickEventType();
    fillMtdt();
    await user.click(screen.getByTestId('augment-submit'));

    await waitFor(() => expect(mock.history.post).toHaveLength(1));
    // types 는 생성 조건·이벤트 유형에서 파생되지 않고 선택한 카드 값 그대로다.
    expect(lastBody().types).toEqual(['RAIN']);
  });

  it('제출_완료_후에는_같은_조건으로_다시_요청할_수_있다', async () => {
    mock.onPost('/augments/request').reply(() => okReply());
    const user = userEvent.setup();
    renderWithProviders(<AugmentRequestPage />);

    await selectKindAndVideo(user);
    pickEventType();
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
    [...Object.keys(VALID_MTDT), '이벤트 유형'].forEach((label) => {
      expect(selectOf(new RegExp(label))).toHaveAttribute('id');
    });
    expect(screen.getByLabelText('자유 지시문')).toHaveAttribute('id');
  });

  /**
   * 증강 3종 차별화 — 유형별 생성 조건 프리필.
   *
   * 배경(실측): 외부 위탁 요청 바디에 **증강 유형 필드가 없다**. 유형에 따라 달라질 수 있는
   * 값은 생성 조건과 자유 지시문뿐이므로, 유형이 반영되지 않으면 WINTER/NIGHT/RAIN 이
   * **바이트 단위로 동일한 요청**이 되어 3종을 나눈 의미가 사라진다.
   *
   * 경계: 프리필은 **기본값**일 뿐 강제가 아니다 — REVIEWER 가 조건을 조절할 수 있어야
   * 한다는 확정 정책(2026-07-31)을 지키려면 ①바꿀 수 있고 ②이미 손댄 값은 보존돼야 한다.
   */
  describe('증강 유형별 생성 조건 프리필', () => {
    const valueOf = (label: string) => selectOf(new RegExp(label)).value;

    /** 종류 카드만 고른다(영상 선택 없이도 조건 폼은 노출된다). */
    const pickKind = async (
      user: ReturnType<typeof userEvent.setup>,
      kind: 'WINTER' | 'NIGHT' | 'RAIN' | 'RESOLUTION',
    ) => {
      await user.click(await screen.findByTestId(`process-kind-${kind}`));
    };

    it('유형을_WINTER_로_고르면_계절과_날씨가_채워진다', async () => {
      const user = userEvent.setup();
      renderWithProviders(<AugmentRequestPage />);

      await pickKind(user, 'WINTER');

      // 겨울은 계절·날씨 축이다. 나머지는 비어 있다(지어내지 않는다).
      expect(valueOf('계절')).toBe('WINTER');
      expect(valueOf('날씨')).toBe('SNOW');
      expect(valueOf('시간대')).toBe('');
      expect(valueOf('지형')).toBe('');
      expect(valueOf('심각도')).toBe('');
    });

    it('유형을_NIGHT_로_고르면_시간대가_채워진다', async () => {
      const user = userEvent.setup();
      renderWithProviders(<AugmentRequestPage />);

      await pickKind(user, 'NIGHT');

      expect(valueOf('시간대')).toBe('NIGHT');
      expect(valueOf('계절')).toBe('');
      expect(valueOf('날씨')).toBe('');
    });

    it('유형을_RAIN_으로_고르면_날씨가_채워진다', async () => {
      const user = userEvent.setup();
      renderWithProviders(<AugmentRequestPage />);

      await pickKind(user, 'RAIN');

      expect(valueOf('날씨')).toBe('RAIN');
      expect(valueOf('시간대')).toBe('');
      expect(valueOf('계절')).toBe('');
    });

    it('유형을_바꾸면_이전_유형의_프리필_잔재가_남지_않는다', async () => {
      const user = userEvent.setup();
      renderWithProviders(<AugmentRequestPage />);

      await pickKind(user, 'WINTER');
      expect(valueOf('계절')).toBe('WINTER');

      await pickKind(user, 'NIGHT');

      // 사용자가 손대지 않은 값이므로 새 유형 기준으로 갱신된다.
      // (남겨두면 '야간인데 계절=겨울' 이라는, 사용자가 고른 적 없는 조건이 전송된다)
      expect(valueOf('시간대')).toBe('NIGHT');
      expect(valueOf('계절')).toBe('');
      expect(valueOf('날씨')).toBe('');
    });

    it('사용자가_이미_고른_항목은_유형을_바꿔도_덮어쓰지_않는다', async () => {
      const user = userEvent.setup();
      renderWithProviders(<AugmentRequestPage />);

      // given: 야간 선택 후 사용자가 계절을 직접 고름
      await pickKind(user, 'NIGHT');
      fireEvent.change(selectOf(/계절/), { target: { value: 'SPRING' } });

      // when: 겨울로 변경 — 겨울 프리필은 계절=WINTER 다
      await pickKind(user, 'WINTER');

      // then: 사용자 선택이 이긴다
      expect(valueOf('계절')).toBe('SPRING');
      // 손대지 않은 날씨는 겨울 프리필이 적용된다
      expect(valueOf('날씨')).toBe('SNOW');
    });

    it('사용자가_의도적으로_되돌린_항목은_유형을_바꿔도_다시_채우지_않는다', async () => {
      const user = userEvent.setup();
      renderWithProviders(<AugmentRequestPage />);

      // given: 겨울 프리필 후 사용자가 날씨를 미선택으로 되돌린다(빈 값도 사용자의 결정이다)
      await pickKind(user, 'WINTER');
      fireEvent.change(selectOf(/날씨/), { target: { value: '' } });
      expect(valueOf('날씨')).toBe('');

      // when: 우천으로 변경 — 우천 프리필은 날씨=RAIN 이다
      await pickKind(user, 'RAIN');

      // then: 되돌린 상태가 유지된다("비었으니 채워도 된다"로 판정하면 여기서 되살아난다)
      expect(valueOf('날씨')).toBe('');
    });

    it('해상도_변경을_거쳐도_사용자_선택은_보존된다', async () => {
      const user = userEvent.setup();
      renderWithProviders(<AugmentRequestPage />);

      await pickKind(user, 'WINTER');
      fireEvent.change(selectOf(/지형/), { target: { value: 'UNDERPASS' } });

      // 해상도 변경은 조건 폼 자체가 없다
      await pickKind(user, 'RESOLUTION');
      expect(screen.queryByTestId('augment-prompt-block')).not.toBeInTheDocument();

      await pickKind(user, 'WINTER');
      expect(valueOf('지형')).toBe('UNDERPASS');
    });

    it('세_유형이_서로_다른_mtdt_로_전송된다', async () => {
      mock.onPost('/augments/request').reply(() => okReply());
      const user = userEvent.setup();

      /**
       * 한 유형을 **새 화면 진입에서** 요청하고 전송된 페이로드를 돌려준다.
       *
       * 매번 새로 렌더하는 이유: 사용자가 고른 항목은 유형을 바꿔도 보존되므로(위 테스트),
       * 한 화면에서 세 유형을 연달아 채우면 첫 유형에서 고른 값이 그대로 남아 비교가 흐려진다.
       * 여기서 확인하려는 것은 "같은 사용자 선택에 유형만 다를 때 요청이 갈리는가" 다.
       */
      const submitFrom = async (kind: 'WINTER' | 'NIGHT' | 'RAIN') => {
        const { unmount } = renderWithProviders(<AugmentRequestPage />);
        await pickKind(user, kind);
        await user.click(await screen.findByRole('radio', { name: /CCTV-1 선택/ }));
        pickEventType();

        // 사용자는 유형과 무관하게 같은 값을 넣는다 — 프리필이 비워 둔 칸만 고른다.
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

      // 이 프리필의 존재 이유: 유형이 실제로 페이로드를 가른다.
      expect(winter.mtdt).not.toEqual(night.mtdt);
      expect(night.mtdt).not.toEqual(rain.mtdt);
      expect(winter.mtdt).not.toEqual(rain.mtdt);
      expect(winter.mtdt?.season).toBe('WINTER');
      expect(night.mtdt?.time).toBe('NIGHT');
      expect(rain.mtdt?.weather).toBe('RAIN');
    });
  });
});
