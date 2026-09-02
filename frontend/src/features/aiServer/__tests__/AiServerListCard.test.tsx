import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import MockAdapter from 'axios-mock-adapter';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';

import { useAdminSessionStore } from '@/features/adminSession/store';
import { apiClient } from '@/lib/api/client';
import { renderWithProviders } from '@/test/renderWithProviders';
import { selectRadixOption } from '@/test/selectTestUtils';

import { AiServerListCard } from '../components/AiServerListCard';
import type { AiSrvr } from '../types';

/**
 * 테스트용 더미 문자열은 **조립해서** 만든다 — 리터럴로 두면 자격증명 스캐너가 소스에 박힌
 * 비밀로 오인한다(연동 주소 카드 시험과 같은 이유). 값 자체에는 아무 의미가 없다.
 */
const DUMMY_PW = ['fixture', 'admin', 'value'].join('-');
const DUMMY_SESSION = ['fixture', 'session', 'value'].join('-');

const FUTURE = () => new Date(Date.now() + 10 * 60 * 1000).toISOString();

function ok<T>(data: T) {
  return [200, { success: true, data, message: null, errorCode: null }] as const;
}

function fail(status: number, errorCode: string, message: string) {
  return [status, { success: false, data: null, message, errorCode }] as const;
}

function server(overrides: Partial<AiSrvr> = {}): AiSrvr {
  return {
    srvrId: 'gpu01',
    srvrNm: 'klid-ai-gpu-01',
    srvrAddr: 'http://10.0.0.11:9300',
    srvrTypeCd: 'INFERENCE',
    srvrSttsCd: 'AVAILABLE',
    chckDt: '2026-09-01T01:00:00Z',
    chckFailNocs: 0,
    chckScsNocs: 12,
    regDt: '2026-08-01T00:00:00Z',
    mdfrId: '1001',
    mdfcnDt: null,
    loads: [
      { usgTypeCd: 'BATCH', prcsNocs: 1, wtngNocs: 2, effectiveLoad: 3, chckDt: '2026-09-01T01:00:00Z' },
    ],
    ...overrides,
  };
}

/** 유형별 목록을 각각 배선한다 — 두 유형이 서로 다른 응답을 받아야 탭 축이 검증된다. */
function wireList(mock: MockAdapter, inference: AiSrvr[], timeseries: AiSrvr[]) {
  mock
    .onGet('/manage/ai-servers', { params: { srvrTypeCd: 'INFERENCE' } })
    .reply(() => ok(inference) as never);
  mock
    .onGet('/manage/ai-servers', { params: { srvrTypeCd: 'TIMESERIES' } })
    .reply(() => ok(timeseries) as never);
}

/** 관리자 유효창을 미리 열어 둔다(쓰기 조작이 가능한 상태). */
function openWindow() {
  useAdminSessionStore.getState().open({ token: DUMMY_SESSION, expiresAt: FUTURE() });
}

describe('AiServerListCard — AI 장비 목록 (SCREEN-042)', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
  });

  afterEach(() => {
    mock.restore();
  });

  it('★유형_탭이_목록을_가른다 — 추론과_시계열이_서로_다른_행을_보여준다', async () => {
    wireList(
      mock,
      [server({ srvrId: 'gpu01' })],
      [server({ srvrId: 'vlm01', srvrTypeCd: 'TIMESERIES', loads: [] })],
    );
    renderWithProviders(<AiServerListCard />);

    // given: 처음에는 추론 탭
    await screen.findByTestId('ai-server-row-gpu01');
    expect(screen.queryByTestId('ai-server-row-vlm01')).not.toBeInTheDocument();

    // when: 시계열 탭으로 옮긴다
    fireEvent.click(screen.getByRole('tab', { name: '외부 시계열 분석' }));

    // then: 그 유형의 행만 남는다 — 두 유형이 한 표에 섞이지 않는다
    await screen.findByTestId('ai-server-row-vlm01');
    expect(screen.queryByTestId('ai-server-row-gpu01')).not.toBeInTheDocument();
  });

  it('★부하를_세는_축이_유형마다_다르다는_사실을_탭마다_밝힌다', async () => {
    wireList(mock, [server()], [server({ srvrId: 'vlm01', srvrTypeCd: 'TIMESERIES' })]);
    renderWithProviders(<AiServerListCard />);

    await screen.findByTestId('ai-server-row-gpu01');
    expect(screen.getByTestId('ai-server-load-axis-note')).toHaveTextContent('큐 길이가 곧 부하');

    fireEvent.click(screen.getByRole('tab', { name: '외부 시계열 분석' }));

    await waitFor(() =>
      expect(screen.getByTestId('ai-server-load-axis-note')).toHaveTextContent(
        '우리 원장의 미결 위탁 수',
      ),
    );
  });

  it('★상태_배지_4종이_구분된다 — 특히_이용불가와_비활성은_라벨도_사유도_다르다', async () => {
    wireList(
      mock,
      [
        server({ srvrId: 'gpu01', srvrSttsCd: 'AVAILABLE' }),
        server({ srvrId: 'gpu02', srvrSttsCd: 'UNAVAILABLE' }),
        server({ srvrId: 'gpu03', srvrSttsCd: 'DRAINING' }),
        server({ srvrId: 'gpu04', srvrSttsCd: 'DISABLED' }),
      ],
      [],
    );
    renderWithProviders(<AiServerListCard />);

    const unavailable = within(await screen.findByTestId('ai-server-row-gpu02'));
    const disabled = within(screen.getByTestId('ai-server-row-gpu04'));

    // 라벨이 다르다
    expect(unavailable.getByText('이용불가')).toBeInTheDocument();
    expect(disabled.getByText('비활성')).toBeInTheDocument();

    // ★누가 그렇게 만들었는가까지 다르다 — 기계가 배제한 것과 사람이 내려 둔 것
    expect(unavailable.getByText('상태점검 실패로 자동 배제')).toBeInTheDocument();
    expect(disabled.getByText('관리자가 내려 둠')).toBeInTheDocument();
    // 색만으로 가르지 않는다는 뜻이기도 하다 — 두 행의 문구가 서로 바뀌어 있지 않다
    expect(unavailable.queryByText('관리자가 내려 둠')).not.toBeInTheDocument();
    expect(disabled.queryByText('상태점검 실패로 자동 배제')).not.toBeInTheDocument();

    // 나머지 둘
    expect(within(screen.getByTestId('ai-server-row-gpu01')).getByText('가용')).toBeInTheDocument();
    expect(
      within(screen.getByTestId('ai-server-row-gpu03')).getByText('정비중'),
    ).toBeInTheDocument();
  });

  it('★관측된_적_없는_용도는_0이_아니라_관측_없음으로_적는다', async () => {
    wireList(mock, [server({ srvrId: 'gpu01', loads: [] })], []);
    renderWithProviders(<AiServerListCard />);

    const cell = await screen.findByTestId('ai-server-loads-none-gpu01');
    expect(cell).toHaveTextContent('관측 없음');
    // 0 으로 채우면 가장 한가한 장비로 오해한다
    expect(within(screen.getByTestId('ai-server-row-gpu01')).queryByText(/대기 0/)).toBeNull();
  });

  describe('주소 검증 — 화면이 먼저 막지 않는다', () => {
    it('★평문_http_사설대역_주소가_그대로_전송된다', async () => {
      openWindow();
      wireList(mock, [server()], []);
      const posted: Array<Record<string, unknown>> = [];
      mock.onPost('/manage/ai-servers').reply((config) => {
        posted.push(JSON.parse(config.data ?? '{}'));
        return ok(server({ srvrId: 'gpu09' })) as never;
      });

      renderWithProviders(<AiServerListCard />);
      await screen.findByTestId('ai-server-row-gpu01');

      fireEvent.click(screen.getByRole('button', { name: '장비 등록' }));
      fireEvent.change(await screen.findByLabelText('장비 식별자'), {
        target: { value: 'gpu09' },
      });
      fireEvent.change(screen.getByLabelText('주소'), {
        target: { value: 'http://192.168.10.20:9300' },
      });
      fireEvent.click(screen.getByRole('button', { name: '등록' }));

      await waitFor(() => expect(posted).toHaveLength(1));
      expect(posted[0]).toMatchObject({
        srvrId: 'gpu09',
        srvrAddr: 'http://192.168.10.20:9300',
        srvrTypeCd: 'INFERENCE',
      });
    });

    it('스킴이_아닌_값은_화면에서_1차로_거른다', async () => {
      openWindow();
      wireList(mock, [server()], []);
      let posted = 0;
      mock.onPost('/manage/ai-servers').reply(() => {
        posted += 1;
        return ok(server()) as never;
      });

      renderWithProviders(<AiServerListCard />);
      await screen.findByTestId('ai-server-row-gpu01');

      fireEvent.click(screen.getByRole('button', { name: '장비 등록' }));
      fireEvent.change(await screen.findByLabelText('장비 식별자'), { target: { value: 'gpu09' } });
      fireEvent.change(screen.getByLabelText('주소'), { target: { value: 'ftp://x.example' } });
      fireEvent.click(screen.getByRole('button', { name: '등록' }));

      expect(
        await screen.findByText('http:// 또는 https:// 로 시작하는 주소를 입력해주세요'),
      ).toBeInTheDocument();
      expect(posted).toBe(0);
    });

    it('식별자_형식_위반도_화면에서_1차로_거른다', async () => {
      openWindow();
      wireList(mock, [server()], []);
      renderWithProviders(<AiServerListCard />);
      await screen.findByTestId('ai-server-row-gpu01');

      fireEvent.click(screen.getByRole('button', { name: '장비 등록' }));
      fireEvent.change(await screen.findByLabelText('장비 식별자'), { target: { value: 'GPU-09' } });
      fireEvent.change(screen.getByLabelText('주소'), { target: { value: 'http://10.0.0.9:9300' } });
      fireEvent.click(screen.getByRole('button', { name: '등록' }));

      expect(
        await screen.findByText('식별자는 소문자와 숫자만 20자 이내로 사용할 수 있습니다'),
      ).toBeInTheDocument();
    });
  });

  describe('409 — 세 사유를 뭉뚱그리지 않는다', () => {
    async function attemptStatusChange(message: string) {
      openWindow();
      wireList(mock, [server({ srvrId: 'gpu01' }), server({ srvrId: 'gpu02' })], []);
      mock
        .onPatch('/manage/ai-servers/gpu01/status')
        .reply(() => fail(409, 'CONFLICT', message) as never);

      const user = userEvent.setup();
      renderWithProviders(<AiServerListCard />);
      await screen.findByTestId('ai-server-row-gpu01');

      await user.click(screen.getByRole('button', { name: 'gpu01 상태 바꾸기' }));
      await selectRadixOption(user, await screen.findByLabelText('바꿀 상태'), '정비중');
      await user.click(screen.getByRole('button', { name: '상태 바꾸기' }));
    }

    it('같은_상태로의_전이는_그_문구_그대로_보인다', async () => {
      await attemptStatusChange('이미 그 상태입니다.');
      expect(await screen.findByTestId('ai-server-status-error')).toHaveTextContent(
        '이미 그 상태입니다.',
      );
    });

    it('허용되지_않는_전이는_그_문구_그대로_보인다', async () => {
      await attemptStatusChange('허용되지 않는 상태 전이입니다. DRAINING 에서 UNAVAILABLE 로는 바꿀 수 없습니다.');
      expect(await screen.findByTestId('ai-server-status-error')).toHaveTextContent(
        '허용되지 않는 상태 전이입니다.',
      );
      // ★다른 사유의 문구로 덮이지 않는다
      expect(screen.getByTestId('ai-server-status-error')).not.toHaveTextContent('이미 그 상태');
    });

    it('마지막_가용_장비는_그_문구_그대로_보인다', async () => {
      await attemptStatusChange(
        '그 유형의 마지막 가용 장비라 상태를 내릴 수 없습니다. 다른 장비를 먼저 등록하세요.',
      );
      expect(await screen.findByTestId('ai-server-status-error')).toHaveTextContent(
        '그 유형의 마지막 가용 장비라 상태를 내릴 수 없습니다.',
      );
      expect(screen.getByTestId('ai-server-status-error')).not.toHaveTextContent(
        '허용되지 않는 상태 전이',
      );
    });
  });

  it('★마지막_가용_장비는_누르기_전에_미리_알린다 — 어느_유형이_비는지와_다음_행동까지', async () => {
    openWindow();
    // 가용은 하나뿐이고 나머지는 비활성 — 이 상태를 화면이 목록만 보고 스스로 센다
    wireList(
      mock,
      [
        server({ srvrId: 'gpu01', srvrSttsCd: 'AVAILABLE' }),
        server({ srvrId: 'gpu02', srvrSttsCd: 'DISABLED' }),
      ],
      [],
    );
    renderWithProviders(<AiServerListCard />);
    await screen.findByTestId('ai-server-row-gpu01');

    fireEvent.click(screen.getByRole('button', { name: 'gpu01 상태 바꾸기' }));

    const warning = await screen.findByTestId('ai-server-last-available-warning');
    expect(warning).toHaveTextContent('추론 유형의 가용 장비는 이 장비뿐입니다');
    expect(warning).toHaveTextContent('새 장비를 먼저 등록한 뒤');
  });

  it('가용_장비가_둘_이상이면_그_안내를_띄우지_않는다', async () => {
    openWindow();
    wireList(
      mock,
      [
        server({ srvrId: 'gpu01', srvrSttsCd: 'AVAILABLE' }),
        server({ srvrId: 'gpu02', srvrSttsCd: 'AVAILABLE' }),
      ],
      [],
    );
    renderWithProviders(<AiServerListCard />);
    await screen.findByTestId('ai-server-row-gpu01');

    fireEvent.click(screen.getByRole('button', { name: 'gpu01 상태 바꾸기' }));

    expect(await screen.findByLabelText('바꿀 상태')).toBeInTheDocument();
    expect(screen.queryByTestId('ai-server-last-available-warning')).not.toBeInTheDocument();
  });

  describe('관리자 유효창', () => {
    it('★잠긴_상태에서_장비_등록을_누르면_기존_관리자_확인_창이_열린다', async () => {
      wireList(mock, [server()], []);
      renderWithProviders(<AiServerListCard />);
      await screen.findByTestId('ai-server-row-gpu01');

      fireEvent.click(screen.getByRole('button', { name: '장비 등록' }));

      // 새 동선을 만들지 않는다 — 이 화면이 이미 갖고 있는 확인 창이 그대로 열린다
      expect(await screen.findByLabelText('관리자 패스워드')).toBeInTheDocument();
      // 등록 창은 아직 열리지 않는다
      expect(screen.queryByLabelText('장비 식별자')).not.toBeInTheDocument();
    });

    it('★확인을_마치면_누르던_조작을_이어서_연다', async () => {
      wireList(mock, [server()], []);
      mock
        .onPost('/manage/admin-session')
        .reply(() => ok({ token: DUMMY_SESSION, expiresAt: FUTURE() }) as never);

      renderWithProviders(<AiServerListCard />);
      await screen.findByTestId('ai-server-row-gpu01');

      fireEvent.click(screen.getByRole('button', { name: '장비 등록' }));
      fireEvent.change(await screen.findByLabelText('관리자 패스워드'), {
        target: { value: DUMMY_PW },
      });
      fireEvent.click(screen.getByRole('button', { name: '인증' }));

      expect(await screen.findByLabelText('장비 식별자')).toBeInTheDocument();
    });

    it('★서버가_403으로_거절하면_다시_확인할_창이_열린다', async () => {
      openWindow();
      wireList(mock, [server({ srvrId: 'gpu01' }), server({ srvrId: 'gpu02' })], []);
      mock
        .onPatch('/manage/ai-servers/gpu01/status')
        .reply(() => fail(403, 'FORBIDDEN', '권한이 없습니다.') as never);

      const user = userEvent.setup();
      renderWithProviders(<AiServerListCard />);
      await screen.findByTestId('ai-server-row-gpu01');

      await user.click(screen.getByRole('button', { name: 'gpu01 상태 바꾸기' }));
      await selectRadixOption(user, await screen.findByLabelText('바꿀 상태'), '정비중');
      await user.click(screen.getByRole('button', { name: '상태 바꾸기' }));

      expect(await screen.findByLabelText('관리자 패스워드')).toBeInTheDocument();
      expect(screen.getByTestId('ai-server-status-error')).toHaveTextContent(
        '관리자 확인이 만료되었습니다',
      );
    });
  });

  it('삭제_거부_사유도_서버_문구를_그대로_보여준다', async () => {
    openWindow();
    wireList(mock, [server({ srvrId: 'gpu01' })], []);
    mock
      .onDelete('/manage/ai-servers/gpu01')
      .reply(
        () =>
          fail(
            409,
            'CONFLICT',
            '이 장비에 배정된 영상이 있어 삭제할 수 없습니다. 비활성으로 내려 주세요.',
          ) as never,
      );

    renderWithProviders(<AiServerListCard />);
    await screen.findByTestId('ai-server-row-gpu01');

    fireEvent.click(screen.getByRole('button', { name: 'gpu01 삭제' }));
    fireEvent.click(await screen.findByRole('button', { name: '삭제' }));

    expect(await screen.findByTestId('ai-server-list-error')).toHaveTextContent(
      '이 장비에 배정된 영상이 있어 삭제할 수 없습니다.',
    );
  });

  it('수정_창은_이름과_주소만_연다 — 식별자와_유형은_읽기_전용이다', async () => {
    openWindow();
    wireList(mock, [server({ srvrId: 'gpu01' })], []);
    const patched: Array<Record<string, unknown>> = [];
    mock.onPatch('/manage/ai-servers/gpu01').reply((config) => {
      patched.push(JSON.parse(config.data ?? '{}'));
      return ok(server()) as never;
    });

    renderWithProviders(<AiServerListCard />);
    await screen.findByTestId('ai-server-row-gpu01');

    fireEvent.click(screen.getByRole('button', { name: 'gpu01 수정' }));

    expect(await screen.findByTestId('ai-server-form-id')).toHaveTextContent('gpu01');
    expect(screen.queryByLabelText('장비 식별자')).not.toBeInTheDocument();

    fireEvent.change(screen.getByLabelText('주소'), { target: { value: 'http://10.0.0.99:9300' } });
    fireEvent.click(screen.getByRole('button', { name: '저장' }));

    await waitFor(() => expect(patched).toHaveLength(1));
    // 유형·식별자는 이 창구가 받지 않는다 — 보내지 않는 것이 계약이다
    expect(patched[0]).toEqual({ srvrNm: 'klid-ai-gpu-01', srvrAddr: 'http://10.0.0.99:9300' });
  });
});
