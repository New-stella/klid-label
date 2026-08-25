import { readFileSync } from 'node:fs';
import path from 'node:path';

import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi, beforeEach } from 'vitest';

import { isDisplayNameSource } from '@/components/common/DisplayNameSourceChip';
import { EventTypeManagePage } from '@/pages/manage/EventTypeManagePage';

import * as adminApi from '../adminApi';
import * as verificationApi from '../verificationApi';

vi.mock('../adminApi');
// 같은 화면에 붙은 <b>검증 이벤트 유형·질문</b> 절(SCREEN-038)이 이 모듈을 부른다.
// 모의하지 않으면 실제 HTTP 로 나가 이 파일의 단언과 무관한 실패·경고가 섞인다.
vi.mock('../verificationApi');

/**
 * 이벤트유형 관리 — 표시명 출처 표기·시안 반영 회귀 가드.
 * @design SCREEN-038, API-185, API-186, UI-126
 *
 * 고정하는 계약:
 *  - 출처 칩은 서버가 내려준 dsplNmSource 를 그대로 표시한다. FE 가 원본 이름 필드
 *    (optrIndctNm·evntNm·evntCtgryNm)로 폴백을 다시 판정하지 않는다.
 *  - 저장 성공 시 응답값으로 그 행을 즉시 갱신하고, <b>목록도 다시 조회한다</b>
 *    (저장 응답에는 프리셋 연결 상태가 실리지 않고, 표시명 변경은 같은 그룹의 다른 행도 바꾼다).
 *  - 수집여부 토글은 aria-pressed 로 상태를 노출한다(색·글자 단독 구분 금지).
 *  - 카드 래퍼 · 건수 요약 · 안내 배너(시안 SD-023).
 */
describe('이벤트유형 관리 — 표시명 출처 표기', () => {
  const rows: adminApi.EventTypeAdminItem[] = [
    {
      evntTypeCd: 'EV01000101',
      dsplNm: '침수(범람) 도로',
      dsplNmSource: 'operator',
      optrIndctNm: '침수(범람) 도로',
      evntNm: null,
      evntCtgryNm: '침수(범람)',
      evntClsfCd: '01',
      evntCtgryCd: '0001',
      clctYn: 'Y',
    },
    {
      evntTypeCd: 'EV01000201',
      dsplNm: '쓰러짐 감지',
      dsplNmSource: 'control',
      optrIndctNm: null,
      evntNm: '쓰러짐 감지',
      evntCtgryNm: '쓰러짐',
      evntClsfCd: '01',
      evntCtgryCd: '0002',
      clctYn: 'Y',
    },
    {
      evntTypeCd: 'EV01000301',
      dsplNm: '교통사고',
      dsplNmSource: 'category',
      optrIndctNm: null,
      evntNm: null,
      evntCtgryNm: '교통사고',
      evntClsfCd: '01',
      evntCtgryCd: '0003',
      clctYn: 'N',
    },
    {
      evntTypeCd: 'EV09000001',
      dsplNm: 'EV09000001',
      dsplNmSource: 'code',
      optrIndctNm: null,
      evntNm: null,
      evntCtgryNm: null,
      evntClsfCd: '09',
      evntCtgryCd: null,
      clctYn: 'N',
    },
  ];

  function renderPage() {
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    return render(
      <QueryClientProvider client={client}>
        <EventTypeManagePage />
      </QueryClientProvider>,
    );
  }

  beforeEach(() => {
    vi.mocked(adminApi.getEventTypeAdminList).mockResolvedValue(rows);
    // 이 파일은 관제 이벤트유형 축만 검증한다 — 검증 유형 축은 빈 목록으로 고정해 격리한다.
    vi.mocked(verificationApi.getVerificationEventTypes).mockResolvedValue([]);
    vi.mocked(adminApi.updateEventTypeAdmin).mockResolvedValue({ ...rows[0] });
  });

  it('표시명_칸에_출처_칩_4종이_각각_병기된다', async () => {
    // given / when
    renderPage();
    await screen.findByText('EV01000101');

    // then: 4단(운영자 지정명 → 관제 수신명 → 카테고리명 → 유형코드) 각각의 칩
    const cell = (code: string) => screen.getByTestId(`event-type-name-${code}`);
    expect(within(cell('EV01000101')).getByText('운영자 지정')).toBeInTheDocument();
    expect(within(cell('EV01000201')).getByText('관제 수신명')).toBeInTheDocument();
    expect(within(cell('EV01000301')).getByText('카테고리명')).toBeInTheDocument();
    expect(within(cell('EV09000001')).getByText('유형코드 그대로')).toBeInTheDocument();
  });

  it('출처는_서버값만_따르고_원본_이름_필드로_재판정하지_않는다', async () => {
    // given: 서버가 code 라고 했는데 운영자 표시명이 채워져 있는(모순) 행.
    //        FE 가 폴백을 재현하면 '운영자 지정' 이 되고, 서버값을 쓰면 '유형코드 그대로' 다.
    vi.mocked(adminApi.getEventTypeAdminList).mockResolvedValue([
      {
        ...rows[0],
        evntTypeCd: 'EVCONFLICT',
        dsplNm: 'EVCONFLICT',
        dsplNmSource: 'code',
        optrIndctNm: '사람이 넣은 이름',
        evntNm: '관제가 보낸 이름',
        evntCtgryNm: '카테고리 이름',
      },
    ]);

    // when
    renderPage();
    const cell = await screen.findByTestId('event-type-name-EVCONFLICT');

    // then
    expect(within(cell).getByText('유형코드 그대로')).toBeInTheDocument();
    expect(within(cell).queryByText('운영자 지정')).toBeNull();
  });

  it('출처_칩_컴포넌트는_원본_이름_필드를_아예_모른다', () => {
    // given: 정적 가드 — 판정이 두 곳으로 갈리는 것을 소스 수준에서 막는다.
    const src = readFileSync(
      path.resolve(__dirname, '../../../components/common/DisplayNameSourceChip.tsx'),
      'utf-8',
    );

    // then: 칩은 source 만 받는다. 원본 이름 필드를 참조하면 그것이 두 번째 판정이다.
    for (const field of ['optrIndctNm', 'evntNm', 'evntCtgryNm']) {
      expect(src).not.toContain(field);
    }
  });

  it('★표시명_저장은_응답값으로_그_행을_갱신하고_목록도_다시_조회한다_구_재조회_금지_폐기', async () => {
    // given: 저장 응답에 갱신된 표시명과 출처가 함께 실려 온다.
    //   ⚠ 그러나 <b>프리셋 연결 상태는 실리지 않는다</b>(서버가 의도적으로 비운다 — 표시명을
    //     바꾸면 표시명 그룹이 쪼개져 대표코드가 바뀌는데 그룹 캐시 무효화가 커밋 이후라 그
    //     트랜잭션은 수정 전 그룹으로 판정한다). 게다가 표시명 변경은 그 행 하나가 아니라
    //     같은 그룹의 다른 행들의 연결 상태까지 바꾼다 — 그래서 목록을 다시 부른다.
    //   구 동작("재조회하지 않는다")의 근거는 "같은 사실을 두 번 받아오는 왕복"이었는데,
    //   응답에 실리지 않는 값이 생기면서 그 전제가 깨졌다.
    vi.mocked(adminApi.updateEventTypeAdmin).mockResolvedValue({
      ...rows[0],
      dsplNm: '수위상승',
      dsplNmSource: 'operator',
      optrIndctNm: '수위상승',
      presetLinkStatus: null,
    });
    // 재조회가 실제로 일어났는지는 <다음 조회 결과가 화면에 반영되는가>로 본다 —
    // 호출 횟수만 세면 캐시에서 온 값인지 서버에서 온 값인지 구분되지 않는다.
    vi.mocked(adminApi.getEventTypeAdminList).mockResolvedValue([
      { ...rows[0], dsplNm: '수위상승', dsplNmSource: 'operator', optrIndctNm: '수위상승',
        presetLinkStatus: 'UNLINKED' },
      ...rows.slice(1),
    ]);
    renderPage();
    const user = userEvent.setup();

    // when
    const row = await screen.findByTestId('event-type-row-EV01000101');
    const listCallsBefore = vi.mocked(adminApi.getEventTypeAdminList).mock.calls.length;
    await user.click(within(row).getByRole('button', { name: '표시명 수정' }));
    const input = screen.getByLabelText('EV01000101 표시명');
    await user.clear(input);
    await user.type(input, '수위상승');
    await user.click(within(row).getByRole('button', { name: '저장' }));

    // then ①: 응답값으로 행이 즉시 갱신된다(표시명 + 출처)
    await waitFor(() => expect(screen.getByText('수위상승')).toBeInTheDocument());
    const cell = screen.getByTestId('event-type-name-EV01000101');
    expect(within(cell).getByText('운영자 지정')).toBeInTheDocument();

    // then ②: 목록을 다시 조회하고, 그 결과가 프리셋 칸을 채운다
    await waitFor(() =>
      expect(vi.mocked(adminApi.getEventTypeAdminList).mock.calls.length).toBeGreaterThan(
        listCallsBefore,
      ),
    );
    await waitFor(() =>
      expect(screen.getByTestId('event-type-preset-EV01000101')).toHaveTextContent('미연결'),
    );
  });

  it('운영자_지정명을_지우면_응답의_내려간_출처가_그대로_드러난다', async () => {
    // given: 해제 저장 → 관제 수신명으로 복귀한 응답
    const released: adminApi.EventTypeAdminItem = {
      ...rows[0]!,
      dsplNm: '침수(범람)',
      dsplNmSource: 'category',
      optrIndctNm: null,
    };
    vi.mocked(adminApi.updateEventTypeAdmin).mockResolvedValue(released);
    // 저장 뒤 목록을 다시 조회하므로(위 테스트) 그 재조회도 해제된 상태를 돌려줘야 한다 —
    // 서버가 방금 저장한 값을 돌려주는 것과 같다. 옛 값을 돌려주게 두면 화면이 되돌아가고,
    // 그건 화면 결함이 아니라 <모의가 서버를 잘못 흉내낸 것>이다.
    vi.mocked(adminApi.getEventTypeAdminList).mockResolvedValue([released, ...rows.slice(1)]);
    renderPage();
    const user = userEvent.setup();

    // when
    const row = await screen.findByTestId('event-type-row-EV01000101');
    await user.click(within(row).getByRole('button', { name: '표시명 수정' }));
    await user.clear(screen.getByLabelText('EV01000101 표시명'));
    await user.click(within(row).getByRole('button', { name: '저장' }));

    // then
    await waitFor(() =>
      expect(adminApi.updateEventTypeAdmin).toHaveBeenCalledWith('EV01000101', {
        optrIndctNm: '',
      }),
    );
    const cell = await screen.findByTestId('event-type-name-EV01000101');
    await waitFor(() =>
      expect(within(cell).getByText('카테고리명')).toBeInTheDocument(),
    );
  });

  it('수집여부_토글은_aria_pressed_로_상태를_노출한다', async () => {
    // given / when
    renderPage();
    await screen.findByText('EV01000101');

    // then: 노출(Y)=true · 숨김(N)=false — 색·글자 단독 구분이 아니다
    expect(
      screen.getByRole('button', { name: 'EV01000101 수집여부 토글' }),
    ).toHaveAttribute('aria-pressed', 'true');
    expect(
      screen.getByRole('button', { name: 'EV01000301 수집여부 토글' }),
    ).toHaveAttribute('aria-pressed', 'false');
  });

  it('카드_래퍼와_건수_요약과_안내_배너가_렌더된다', async () => {
    // given / when
    renderPage();

    // then: 시안 SD-023 — 목록 카드 헤더의 건수 요약(전체/노출/숨김)
    expect(await screen.findByText('전체 4개 · 노출 2개 · 숨김 2개')).toBeInTheDocument();
    expect(screen.getByTestId('event-type-list-card')).toBeInTheDocument();
    expect(
      screen.getByText('표시명을 지정하면 목록 필터의 이벤트유형 옵션이 갈립니다'),
    ).toBeInTheDocument();
  });
});

/**
 * 미지 출처 토큰 — <b>fail-closed 판정이 유일한 방어선</b>이다.
 *
 * 칩은 알려진 출처 4종만 표기 조회표를 갖는다. 판정(isDisplayNameSource)이 사라지면 미지 값이
 * 그대로 칩으로 들어가 조회표에서 없는 항목을 참조하고, 그 결과는 "칩이 안 보인다"가 아니라
 * <b>그 행·화면 렌더가 통째로 터지는 것</b>이다.
 *
 * ⚠ 위 4종 표기 테스트는 소문자 4종만 넣으므로 판정을 지워도 통과한다 — 이 블록이 그 구멍을 막는다.
 * ⚠ mutation 확인 절차: EventTypeManagePage 의 `isDisplayNameSource(row.dsplNmSource) &&` 가드를
 *   지우면 이 블록이 실패해야 한다. (실제로 확인함)
 */
describe('이벤트유형 관리 — 미지 출처 토큰', () => {
  // 우리가 모르는 값 · 빈 문자열 · 대문자(서버 표기가 갈리는 경우) — 어느 것도 추측하지 않는다.
  const unknownRows: adminApi.EventTypeAdminItem[] = [
    {
      evntTypeCd: 'EVUNKNOWN',
      dsplNm: '미지 출처 유형',
      // 서버가 새 단계를 추가했거나 표기가 갈린 경우를 가정한다(타입 밖 값이라 단언이 필요하다).
      dsplNmSource: 'legacy' as adminApi.EventTypeAdminItem['dsplNmSource'],
      optrIndctNm: null,
      evntNm: null,
      evntCtgryNm: null,
      evntClsfCd: '01',
      evntCtgryCd: null,
      clctYn: 'Y',
    },
    {
      evntTypeCd: 'EVEMPTY',
      dsplNm: '빈 출처 유형',
      dsplNmSource: '' as adminApi.EventTypeAdminItem['dsplNmSource'],
      optrIndctNm: null,
      evntNm: null,
      evntCtgryNm: null,
      evntClsfCd: '01',
      evntCtgryCd: null,
      clctYn: 'Y',
    },
    {
      evntTypeCd: 'EVUPPER',
      dsplNm: '대문자 출처 유형',
      dsplNmSource: 'OPERATOR' as adminApi.EventTypeAdminItem['dsplNmSource'],
      optrIndctNm: '운영자가 넣은 이름',
      evntNm: null,
      evntCtgryNm: null,
      evntClsfCd: '01',
      evntCtgryCd: null,
      clctYn: 'Y',
    },
  ];

  const CHIP_LABELS = ['운영자 지정', '관제 수신명', '카테고리명', '유형코드 그대로'];

  function renderPage() {
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    return render(
      <QueryClientProvider client={client}>
        <EventTypeManagePage />
      </QueryClientProvider>,
    );
  }

  beforeEach(() => {
    vi.mocked(adminApi.getEventTypeAdminList).mockResolvedValue(unknownRows);
  });

  it.each([
    ['우리가 모르는 값', 'EVUNKNOWN', '미지 출처 유형'],
    ['빈 문자열', 'EVEMPTY', '빈 출처 유형'],
    ['대문자', 'EVUPPER', '대문자 출처 유형'],
  ])('%s 출처여도 렌더가 터지지 않고 표시명은 남는다', async (_case, code, name) => {
    // given / when
    renderPage();

    // then ①: 그 행이 정상 표시된다(렌더가 터지면 여기서 실패한다)
    const cell = await screen.findByTestId(`event-type-name-${code}`);
    expect(within(cell).getByText(name)).toBeInTheDocument();
    expect(screen.getByTestId(`event-type-row-${code}`)).toBeInTheDocument();

    // then ②: 칩은 생략되지만 표시명 텍스트는 남는다(정보가 사라지지 않는다)
    for (const label of CHIP_LABELS) {
      expect(within(cell).queryByText(label)).toBeNull();
    }
  });

  it('미지_출처여도_화면_전체가_계속_동작한다', async () => {
    // given / when: 세 행이 모두 미지 출처다
    renderPage();
    await screen.findByTestId('event-type-name-EVUNKNOWN');

    // then: 목록·요약·다른 행 모두 살아 있다(부분 손상 없이 화면이 성립한다)
    expect(screen.getByText('전체 3개 · 노출 3개 · 숨김 0개')).toBeInTheDocument();
    expect(screen.getByTestId('event-type-list-card')).toBeInTheDocument();
    expect(
      screen.getByRole('button', { name: 'EVUPPER 수집여부 토글' }),
    ).toHaveAttribute('aria-pressed', 'true');
  });

  it('판정_함수가_미지_토큰을_알려진_출처로_승격시키지_않는다', () => {
    // given / when / then: 값이 아니라 **형태**로 막는다 — 모르는 값은 어떤 단계로도 추측하지 않는다
    for (const token of ['legacy', '', 'OPERATOR', 'Operator', ' operator', null, undefined, 3]) {
      expect(isDisplayNameSource(token), `${String(token)} 를 알려진 출처로 봤다`).toBe(false);
    }
  });
});
