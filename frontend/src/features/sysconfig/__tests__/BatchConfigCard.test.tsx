import { fireEvent, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import MockAdapter from 'axios-mock-adapter';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';

import { apiClient } from '@/lib/api/client';
import { renderWithProviders } from '@/test/renderWithProviders';

import { BatchConfigCard } from '../components/BatchConfigCard';

const baseConfigs = {
  BATCH_INTERVAL_SEC: 60,
  BATCH_CONCURRENCY: 1,
};

describe('BatchConfigCard', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
  });

  afterEach(() => {
    mock.restore();
  });

  it('처리_주기_동시_처리_수_두_컨트롤_렌더', () => {
    renderWithProviders(<BatchConfigCard configs={baseConfigs} />);
    expect(screen.getByLabelText(/처리 주기/i)).toBeInTheDocument();
    expect(screen.getByLabelText(/동시 처리 수/i)).toBeInTheDocument();
  });

  it('각_필드의_help_설명_텍스트_렌더', () => {
    renderWithProviders(<BatchConfigCard configs={baseConfigs} />);
    expect(screen.getByText(/신규 영상을 픽업해 처리하는 주기/)).toBeInTheDocument();
    expect(screen.getByText(/병렬 처리할 영상 수/)).toBeInTheDocument();
  });

  it('한_필드만_변경해_저장하면_그_키만_전송된다', async () => {
    // given: 구 버그 — onSubmit 이 dirty 여부와 무관하게 카드 내 전체 키를 항상 mutate 해,
    // 손대지 않은 값까지 매번 재전송됐다(동시 편집 시 다른 사용자 변경을 되돌릴 위험).
    const calledKeys: string[] = [];
    mock.onPut(/\/manage\/configs\/.+/).reply((config) => {
      calledKeys.push(decodeURIComponent(config.url?.split('/').pop() ?? ''));
      return [
        200,
        {
          success: true,
          data: { key: calledKeys[calledKeys.length - 1], value: '999', description: '' },
          message: null,
          errorCode: null,
        },
      ];
    });

    renderWithProviders(<BatchConfigCard configs={baseConfigs} />);

    // when: '처리 주기'만 변경하고 '동시 처리 수'는 손대지 않는다
    fireEvent.change(screen.getByLabelText(/처리 주기/i), { target: { value: '90' } });
    fireEvent.click(screen.getByRole('button', { name: '저장' }));

    // then: 변경한 키만 전송된다
    await waitFor(() => {
      expect(calledKeys).toEqual(['BATCH_INTERVAL_SEC']);
    });
  });

  // ★ 시계열 위탁 **전체 건너뛰기** — 벤더 미연동 구간을 통째로 덮는 스위치. [@design SCREEN-025]
  //   [@design ADR-050]
  //
  //   ★ 사유가 필수인 이유: 이 문구가 건너뜀 표식의 사유로 그대로 기록되어, 나중에 그 영상의
  //     시계열이 왜 비어 있는지 되짚는 유일한 근거가 된다. 사유 없이 켜지면 몇 달 뒤 아무도
  //     이유를 모르는 시계열 공백만 남는다.
  describe('시계열 위탁 전체 건너뛰기', () => {
    const SKIP_KEY = 'batch.vlm.skip-by-default';
    const REASON_KEY = 'batch.vlm.skip-by-default-reason';

    /**
     * PUT 을 기록하는 목 — 키/값 쌍을 **도착 순서대로** 모은다.
     *
     * ★ 순서가 기록되는 것이 이 목의 핵심이다. 서버는 두 키를 따로 받으면서 서로를 검사하므로
     *   (켜는 저장은 저장된 사유를, 사유를 비우는 저장은 저장된 스위치를 읽는다) 두 요청의 순서가
     *   틀리면 한쪽이 400 이 된다. 구 구현은 스위치를 먼저, 그것도 기다리지 않고 던졌다.
     *
     * @param failKey 이 키의 PUT 을 500 으로 떨어뜨린다(앞이 실패하면 뒤가 안 나가는지 확인용)
     */
    function capturePuts(failKey?: string) {
      const puts: { key: string; value: string }[] = [];
      mock.onPut(/\/manage\/configs\/.+/).reply((config) => {
        const key = decodeURIComponent(config.url?.split('/').pop() ?? '');
        puts.push({ key, value: JSON.parse(config.data).value });
        if (key === failKey) {
          return [500, { success: false, data: null, message: '실패', errorCode: 'INTERNAL' }];
        }
        return [
          200,
          { success: true, data: { configKey: key, configVl: '' }, message: null, errorCode: null },
        ];
      });
      return puts;
    }

    it('스위치와_사유_입력을_보여준다', () => {
      renderWithProviders(<BatchConfigCard configs={baseConfigs} configStrings={{}} />);

      expect(screen.getByLabelText(/시계열 위탁 전체 건너뛰기/)).toBeInTheDocument();
      expect(screen.getByLabelText(/건너뛰기 사유/)).toBeInTheDocument();
    });

    // ★★ HIGH — 사유가 비어 있으면 스위치를 켤 수 없고 저장 요청 자체가 나가지 않는다.
    it('★사유가_비어_있으면_스위치를_켤_수_없고_저장이_나가지_않는다', async () => {
      const puts = capturePuts();
      const user = userEvent.setup();
      renderWithProviders(<BatchConfigCard configs={baseConfigs} configStrings={{}} />);

      const toggle = screen.getByLabelText(/시계열 위탁 전체 건너뛰기/);
      await user.click(toggle);

      expect(toggle).not.toBeChecked();
      expect(screen.getByRole('button', { name: '저장' })).toBeDisabled();
      expect(puts).toHaveLength(0);
    });

    it('★공백만_적어도_켤_수_없다', async () => {
      const puts = capturePuts();
      const user = userEvent.setup();
      renderWithProviders(<BatchConfigCard configs={baseConfigs} configStrings={{}} />);

      await user.type(screen.getByLabelText(/건너뛰기 사유/), '   ');
      await user.click(screen.getByLabelText(/시계열 위탁 전체 건너뛰기/));

      expect(screen.getByLabelText(/시계열 위탁 전체 건너뛰기/)).not.toBeChecked();
      expect(screen.getByRole('button', { name: '저장' })).toBeDisabled();
      expect(puts).toHaveLength(0);
    });

    it('사유를_적고_켜면_두_키를_각각_저장한다', async () => {
      const puts = capturePuts();
      const user = userEvent.setup();
      renderWithProviders(<BatchConfigCard configs={baseConfigs} configStrings={{}} />);

      await user.type(screen.getByLabelText(/건너뛰기 사유/), '외부 시계열 분석 벤더 연동 전');
      await user.click(screen.getByLabelText(/시계열 위탁 전체 건너뛰기/));
      await user.click(screen.getByRole('button', { name: '저장' }));

      await waitFor(() => expect(puts).toHaveLength(2));
      // ⚠ 구 단언은 `arrayContaining` 이라 **순서를 전혀 보지 않아** 이 결함을 통과시켰다 → 폐기.
      //   순서 자체가 계약이므로 완전일치로 고정한다.
      expect(puts).toEqual([
        { key: REASON_KEY, value: '외부 시계열 분석 벤더 연동 전' },
        { key: SKIP_KEY, value: 'true' },
      ]);
      // 손대지 않은 숫자 키는 전송되지 않는다(기존 규칙 불변).
      expect(puts.map((p) => p.key)).not.toContain('BATCH_INTERVAL_SEC');
    });

    // ★★ HIGH 회귀 가드 — 스위치 켜기 첫 저장이 실패하던 결함. [@design ADR-050]
    //
    //   서버는 **켜는 저장** 시 저장된 사유를 읽는다. 구 구현은 스위치 PUT 을 사유 PUT 보다 먼저,
    //   그것도 둘 다 기다리지 않고 던져서 사유 커밋이 늦으면 스위치가 400 으로 떨어졌다 — 사유만
    //   저장되고 스위치는 꺼진 채 남으며, 사용자는 원인을 알 수 없는 실패 토스트만 본다.
    //   즉 **활성화 정상 동선이 1회차에 실패**했다.
    it('★★켤_때는_사유가_스위치보다_먼저_나간다', async () => {
      const puts = capturePuts();
      const user = userEvent.setup();
      renderWithProviders(<BatchConfigCard configs={baseConfigs} configStrings={{}} />);

      await user.type(screen.getByLabelText(/건너뛰기 사유/), '벤더 연동 전');
      await user.click(screen.getByLabelText(/시계열 위탁 전체 건너뛰기/));
      await user.click(screen.getByRole('button', { name: '저장' }));

      await waitFor(() => expect(puts).toHaveLength(2));
      expect(puts.map((p) => p.key)).toEqual([REASON_KEY, SKIP_KEY]);
    });

    // ★ 끌 때는 반대다 — 스위치가 **켜져 있는 동안**에는 사유를 비울 수 없으므로(서버 400),
    //   스위치를 먼저 내려야 두 검사 사이에 갇히지 않는다.
    it('★끌_때는_스위치가_사유보다_먼저_나간다', async () => {
      const puts = capturePuts();
      const user = userEvent.setup();
      renderWithProviders(
        <BatchConfigCard
          configs={baseConfigs}
          configStrings={{ [SKIP_KEY]: 'true', [REASON_KEY]: '벤더 연동 전' }}
        />,
      );

      await user.click(screen.getByLabelText(/시계열 위탁 전체 건너뛰기/));
      await user.clear(screen.getByLabelText(/건너뛰기 사유/));
      await user.type(screen.getByLabelText(/건너뛰기 사유/), '연동 완료');
      await user.click(screen.getByRole('button', { name: '저장' }));

      await waitFor(() => expect(puts).toHaveLength(2));
      expect(puts).toEqual([
        { key: SKIP_KEY, value: 'false' },
        { key: REASON_KEY, value: '연동 완료' },
      ]);
    });

    // ★★ 순서만으로는 부족하다 — **앞 요청이 응답할 때까지 뒤를 보내지 않는지**까지 본다.
    //
    //   구 구현의 실패 원인은 순서만이 아니라 **기다리지 않은 것**이었다. 서버가 읽는 것은 커밋된
    //   값이라, 두 요청이 겹쳐 날아가면 도착 순서가 뒤집혀 켜는 저장이 400 으로 떨어진다.
    //   ⚠ 목(axios-mock-adapter)은 즉시 응답하므로 "던진 순서 = 도착 순서"가 되어, 순서 단언만으로는
    //     직렬화 여부가 드러나지 않는다. 그래서 앞 요청을 **의도적으로 붙잡아** 확인한다.
    it('★★사유가_응답하기_전에는_스위치를_보내지_않는다_직렬화', async () => {
      const puts: string[] = [];
      let releaseReason: (() => void) | null = null;
      mock.onPut(/\/manage\/configs\/.+/).reply((config) => {
        const key = decodeURIComponent(config.url?.split('/').pop() ?? '');
        puts.push(key);
        const ok: [number, unknown] = [
          200,
          { success: true, data: { configKey: key, configVl: '' }, message: null, errorCode: null },
        ];
        if (key !== REASON_KEY) return ok;
        return new Promise((resolve) => {
          releaseReason = () => resolve(ok);
        });
      });

      const user = userEvent.setup();
      renderWithProviders(<BatchConfigCard configs={baseConfigs} configStrings={{}} />);

      await user.type(screen.getByLabelText(/건너뛰기 사유/), '벤더 연동 전');
      await user.click(screen.getByLabelText(/시계열 위탁 전체 건너뛰기/));
      await user.click(screen.getByRole('button', { name: '저장' }));

      // 사유가 아직 응답하지 않은 구간 — 스위치는 나가 있으면 안 된다.
      await waitFor(() => expect(puts).toEqual([REASON_KEY]));
      await new Promise((resolve) => setTimeout(resolve, 50));
      expect(puts).toEqual([REASON_KEY]);

      // 사유가 커밋되고 나서야 스위치가 나간다.
      releaseReason!();
      await waitFor(() => expect(puts).toEqual([REASON_KEY, SKIP_KEY]));
    });

    // ★ 앞이 실패하면 뒤를 보내지 않는다 — 보내면 어차피 400 이거나, 더 나쁘게는 절반만 적용된다.
    it('★★사유_저장이_실패하면_스위치를_보내지_않는다', async () => {
      const puts = capturePuts(REASON_KEY);
      const user = userEvent.setup();
      renderWithProviders(<BatchConfigCard configs={baseConfigs} configStrings={{}} />);

      await user.type(screen.getByLabelText(/건너뛰기 사유/), '벤더 연동 전');
      await user.click(screen.getByLabelText(/시계열 위탁 전체 건너뛰기/));
      await user.click(screen.getByRole('button', { name: '저장' }));

      await waitFor(() => expect(puts).toHaveLength(1));
      expect(puts[0]!.key).toBe(REASON_KEY);
      // 뒤따르는 스위치 PUT 이 끝내 나가지 않는다(부분 적용 방지).
      await new Promise((resolve) => setTimeout(resolve, 50));
      expect(puts.map((p) => p.key)).not.toContain(SKIP_KEY);
    });

    // ★ 숫자 키(처리 주기·동시 처리 수)의 저장 동작은 바꾸지 않는다 — 직렬로 흘려보낼 뿐이다.
    it('숫자_키와_함께_저장해도_스위치_사유_순서는_지켜진다', async () => {
      const puts = capturePuts();
      const user = userEvent.setup();
      renderWithProviders(<BatchConfigCard configs={baseConfigs} configStrings={{}} />);

      fireEvent.change(screen.getByLabelText(/처리 주기/i), { target: { value: '90' } });
      await user.type(screen.getByLabelText(/건너뛰기 사유/), '벤더 연동 전');
      await user.click(screen.getByLabelText(/시계열 위탁 전체 건너뛰기/));
      await user.click(screen.getByRole('button', { name: '저장' }));

      await waitFor(() => expect(puts).toHaveLength(3));
      expect(puts.map((p) => p.key)).toEqual(['BATCH_INTERVAL_SEC', REASON_KEY, SKIP_KEY]);
    });

    it('저장값이_있으면_그_상태로_시작한다', () => {
      renderWithProviders(
        <BatchConfigCard
          configs={baseConfigs}
          configStrings={{ [SKIP_KEY]: 'true', [REASON_KEY]: '벤더 연동 전' }}
        />,
      );

      expect(screen.getByLabelText(/시계열 위탁 전체 건너뛰기/)).toBeChecked();
      expect(screen.getByLabelText(/건너뛰기 사유/)).toHaveValue('벤더 연동 전');
    });

    // ★ 켜져 있는 상태를 카드가 눈에 띄게 드러내고, 끄는 것을 잊으면 계속 건너뛴다는 사실을 알린다.
    it('★켜져_있으면_그_사실과_끄는_것을_잊었을_때의_결과를_알린다', () => {
      renderWithProviders(
        <BatchConfigCard
          configs={baseConfigs}
          configStrings={{ [SKIP_KEY]: 'true', [REASON_KEY]: '벤더 연동 전' }}
        />,
      );

      // ⚠ 한글은 음절 단위로 합성되므로 어간(`건너뛰`)으로 부분일치를 노리면 활용형(`건너뛴`·
      //   `건너뜁니다`)을 놓친다 — 실제로 이 단언이 한 번 그렇게 헛돌았다.
      const notice = screen.getByTestId('vlm-skip-default-on-notice');
      expect(notice).toHaveTextContent(/건너뛴|건너뜁/);
      expect(notice).toHaveTextContent(/연동/);
    });

    it('꺼져_있으면_켜짐_안내를_두지_않는다', () => {
      renderWithProviders(
        <BatchConfigCard configs={baseConfigs} configStrings={{ [SKIP_KEY]: 'false' }} />,
      );

      expect(screen.queryByTestId('vlm-skip-default-on-notice')).not.toBeInTheDocument();
    });

    // ★ 켜 둔 채로 사유를 지우면 저장할 수 없다 — 서버도 400 이지만 화면에서 먼저 막는다.
    it('★켜진_상태에서_사유를_지우면_저장이_막힌다', async () => {
      const puts = capturePuts();
      const user = userEvent.setup();
      renderWithProviders(
        <BatchConfigCard
          configs={baseConfigs}
          configStrings={{ [SKIP_KEY]: 'true', [REASON_KEY]: '벤더 연동 전' }}
        />,
      );

      await user.clear(screen.getByLabelText(/건너뛰기 사유/));

      expect(screen.getByRole('button', { name: '저장' })).toBeDisabled();
      expect(puts).toHaveLength(0);
    });

    // ★ 스위치만 끄는 것은 사유와 무관하게 언제나 가능해야 한다 — 못 끄면 켜진 채로 고착된다.
    it('★끄는_것은_사유와_무관하게_가능하다', async () => {
      const puts = capturePuts();
      const user = userEvent.setup();
      renderWithProviders(
        <BatchConfigCard
          configs={baseConfigs}
          configStrings={{ [SKIP_KEY]: 'true', [REASON_KEY]: '벤더 연동 전' }}
        />,
      );

      await user.click(screen.getByLabelText(/시계열 위탁 전체 건너뛰기/));
      await user.click(screen.getByRole('button', { name: '저장' }));

      await waitFor(() => expect(puts).toHaveLength(1));
      expect(puts[0]).toEqual({ key: SKIP_KEY, value: 'false' });
    });
  });
});
