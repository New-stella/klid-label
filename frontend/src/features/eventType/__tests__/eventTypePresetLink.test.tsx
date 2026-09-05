import { readFileSync } from 'node:fs';
import path from 'node:path';

import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { EventTypeManagePage } from '@/pages/manage/EventTypeManagePage';

import * as adminApi from '../adminApi';
import { isPresetLinkStatus, PRESET_LINK_WITHHELD } from '../presetLinkStatus';

vi.mock('../adminApi');

/**
 * 이벤트유형 관리 — 프리셋 연결 상태 칸 · 오토라벨 보류 거르기.
 *
 * <h3>이 화면이 짊어진 것</h3>
 * 프리셋이 없거나 무효인 이벤트유형의 영상은 <b>오토라벨링이 보류</b>된다(CO-014). 보류는 실패가
 * 아니라 조용한 정지라, 운영자가 그 사실을 알아차리는 자리가 이 목록이다.
 *
 * <h3>고정하는 계약</h3>
 * <ul>
 *   <li>연결 상태 4값을 서버가 준 그대로 표기한다 — 화면이 프리셋 유무·실효 여부를 다시 판정하지 않는다.</li>
 *   <li>「연결됨(무효)」와 「연결됨(제외)」를 <b>갈라</b> 보여준다 — 앞은 사고, 뒤는 사람이 뺀 선언이다.</li>
 *   <li>거르기는 <b>보류 전체를 뜻하는 값 하나</b>를 서버에 보낸다 — 화면이 상태 둘을 조합하지 않는다.</li>
 *   <li>미지정이면 파라미터를 싣지 않는다(서버에 기본값이 없다).</li>
 *   <li>조건을 켠 채 저장해 그 행이 조건에서 벗어나도 <b>사라지게 하지 않는다</b>.</li>
 * </ul>
 *
 * @design SCREEN-038, API-185, API-186, AC-114, AC-116, AC-119
 */
const ALL_ROWS: adminApi.EventTypeAdminItem[] = [
  {
    evntTypeCd: 'EV03000101',
    dsplNm: '쓰러짐',
    dsplNmSource: 'category',
    optrIndctNm: null,
    evntNm: null,
    evntCtgryNm: '쓰러짐',
    evntClsfCd: '03',
    evntCtgryCd: '0001',
    clctYn: 'Y',
    presetLinkStatus: 'LINKED',
  },
  {
    // 사고 — 라벨은 담았는데 전부 AI 검출 클래스 미매핑이라 오토라벨이 보류된다.
    evntTypeCd: 'EV02000101',
    dsplNm: '화재',
    dsplNmSource: 'category',
    optrIndctNm: null,
    evntNm: null,
    evntCtgryNm: '화재',
    evntClsfCd: '02',
    evntCtgryCd: '0001',
    clctYn: 'Y',
    presetLinkStatus: 'LINKED_INEFFECTIVE',
  },
  {
    // 선언 — 라벨을 비워 오토라벨 대상에서 뺐다. 보류가 아니다.
    evntTypeCd: 'EV07000201',
    dsplNm: '기타 상황',
    dsplNmSource: 'category',
    optrIndctNm: null,
    evntNm: null,
    evntCtgryNm: '기타 상황',
    evntClsfCd: '07',
    evntCtgryCd: '0002',
    clctYn: 'N',
    presetLinkStatus: 'LINKED_EXCLUDED',
  },
  {
    evntTypeCd: 'INTRUSION',
    dsplNm: 'INTRUSION',
    dsplNmSource: 'code',
    optrIndctNm: null,
    evntNm: null,
    evntCtgryNm: null,
    evntClsfCd: null,
    evntCtgryCd: null,
    clctYn: 'Y',
    presetLinkStatus: 'UNLINKED',
  },
];

/** 보류를 유발하는 것은 둘뿐이다 — 「연결됨(제외)」는 사람이 뺀 선언이라 여기 없다. */
const WITHHELD_ROWS = ALL_ROWS.filter((r) =>
  ['LINKED_INEFFECTIVE', 'UNLINKED'].includes(String(r.presetLinkStatus)),
);

function renderPage() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <EventTypeManagePage />
    </QueryClientProvider>,
  );
}

describe('이벤트유형 관리 — 프리셋 연결 상태', () => {
  beforeEach(() => {
    vi.mocked(adminApi.getEventTypeAdminList).mockImplementation((filter) =>
      Promise.resolve(filter === PRESET_LINK_WITHHELD ? WITHHELD_ROWS : ALL_ROWS),
    );
    vi.mocked(adminApi.updateEventTypeAdmin).mockResolvedValue({ ...ALL_ROWS[0]! });
  });

  const presetCell = (code: string) => screen.getByTestId(`event-type-preset-${code}`);

  it('★연결_상태_4값이_각각의_확정_문구로_표기된다', async () => {
    renderPage();
    await screen.findByText('EV03000101');

    expect(presetCell('EV03000101')).toHaveTextContent('연결됨');
    expect(presetCell('EV02000101')).toHaveTextContent('연결됨(무효)');
    expect(presetCell('EV07000201')).toHaveTextContent('연결됨(제외)');
    expect(presetCell('INTRUSION')).toHaveTextContent('미연결');
  });

  it('★연결됨_무효와_연결됨_제외는_한눈에_갈라_보인다', async () => {
    // 앞은 <사고>(등록해 뒀는데 안 먹어서 보류된다)이고 뒤는 <사람이 일부러 뺀 선언>이다.
    // 같아 보이면 사고를 의도로 오인해 지나친다 — 그래서 글자와 색이 모두 달라야 한다.
    renderPage();
    await screen.findByText('EV03000101');

    const ineffective = within(presetCell('EV02000101')).getByText('연결됨(무효)');
    const excluded = within(presetCell('EV07000201')).getByText('연결됨(제외)');

    expect(ineffective.className).not.toBe(excluded.className);
    // 사고 축은 위험 색이다 — 중립으로 내리면 두 상태가 다시 붙어 보인다.
    expect(ineffective.className).toContain('danger');
    expect(excluded.className).not.toContain('danger');
  });

  it('연결_상태가_없거나_모르는_값이면_추측하지_않고_비운다', async () => {
    // 구 서버·수정 응답은 이 값을 싣지 않는다. 어떤 상태로도 추측하면 정반대로 안내하게 된다.
    vi.mocked(adminApi.getEventTypeAdminList).mockResolvedValue([
      { ...ALL_ROWS[0]!, evntTypeCd: 'EVNULL', presetLinkStatus: null },
      {
        ...ALL_ROWS[0]!,
        evntTypeCd: 'EVUNKNOWN',
        presetLinkStatus: 'SOMETHING_NEW' as adminApi.EventTypeAdminItem['presetLinkStatus'],
      },
    ]);
    renderPage();
    await screen.findByText('EVNULL');

    // 렌더가 터지지 않고(행이 살아 있고) 칩만 생략된다.
    expect(presetCell('EVNULL')).toHaveTextContent('-');
    expect(presetCell('EVUNKNOWN')).toHaveTextContent('-');
    expect(screen.getByTestId('event-type-row-EVUNKNOWN')).toBeInTheDocument();
  });

  it('판정_함수가_미지_토큰을_알려진_상태로_승격시키지_않는다', () => {
    for (const token of ['WITHHELD', 'linked', '', null, undefined, 3]) {
      expect(isPresetLinkStatus(token), `${String(token)} 를 알려진 상태로 봤다`).toBe(false);
    }
  });
});

describe('이벤트유형 관리 — 오토라벨 보류 거르기', () => {
  beforeEach(() => {
    vi.mocked(adminApi.getEventTypeAdminList).mockImplementation((filter) =>
      Promise.resolve(filter === PRESET_LINK_WITHHELD ? WITHHELD_ROWS : ALL_ROWS),
    );
    vi.mocked(adminApi.updateEventTypeAdmin).mockResolvedValue({ ...ALL_ROWS[1]! });
  });

  it('★진입_시에는_조건을_싣지_않아_전체가_돌아온다', async () => {
    renderPage();
    await screen.findByText('EV03000101');

    // 서버에 기본값이 없다 — 조건을 실으면 그 순간 모집단이 달라진다.
    expect(vi.mocked(adminApi.getEventTypeAdminList)).toHaveBeenCalledWith(undefined);
    expect(screen.getAllByTestId(/^event-type-row-/)).toHaveLength(4);
    expect(
      (screen.getByRole('checkbox', { name: '오토라벨 보류만 보기' }) as HTMLElement).getAttribute(
        'aria-checked',
      ),
    ).toBe('false');
  });

  it('★조건을_켜면_보류_전체를_뜻하는_값_하나를_그대로_보낸다_조합_금지', async () => {
    const user = userEvent.setup();
    renderPage();
    await screen.findByText('EV03000101');

    await user.click(screen.getByRole('checkbox', { name: '오토라벨 보류만 보기' }));

    // ★화면이 LINKED_INEFFECTIVE + UNLINKED 를 조합해 보내지 않는다 — 무엇이 보류를 유발하는지의
    //   정의는 서버가 소유한다. 조합하면 보류 조건이 늘 때마다 화면도 함께 고쳐야 하고,
    //   그 사이에는 새 상태의 유형이 목록에서 조용히 빠진다.
    await waitFor(() =>
      expect(vi.mocked(adminApi.getEventTypeAdminList)).toHaveBeenCalledWith('WITHHELD'),
    );
    // 서버가 걸러 준 결과만 그린다 — 「연결됨(제외)」는 보류가 아니라 빠져 있다.
    await waitFor(() => expect(screen.getAllByTestId(/^event-type-row-/)).toHaveLength(2));
    expect(screen.queryByTestId('event-type-row-EV07000201')).toBeNull();
  });

  it('조건을_끄면_다시_조건_없이_조회한다', async () => {
    const user = userEvent.setup();
    renderPage();
    await screen.findByText('EV03000101');

    const checkbox = screen.getByRole('checkbox', { name: '오토라벨 보류만 보기' });
    await user.click(checkbox);
    await waitFor(() => expect(screen.getAllByTestId(/^event-type-row-/)).toHaveLength(2));
    await user.click(checkbox);

    await waitFor(() => expect(screen.getAllByTestId(/^event-type-row-/)).toHaveLength(4));
  });

  it('★조건을_켠_채_저장해_그_행이_조건에서_벗어나도_사라지지_않는다', async () => {
    const user = userEvent.setup();
    renderPage();
    await screen.findByText('EV03000101');
    await user.click(screen.getByRole('checkbox', { name: '오토라벨 보류만 보기' }));
    await screen.findByTestId('event-type-row-EV02000101');

    // given: 저장 뒤 재조회에서 그 행이 조건에서 빠진다(표시명을 바꾸면 표시명 그룹이 쪼개져
    //   대표코드가 바뀌고, 따라서 어느 프리셋이 걸리는지도 바뀐다).
    vi.mocked(adminApi.updateEventTypeAdmin).mockResolvedValue({
      ...ALL_ROWS[1]!,
      dsplNm: '일반화재',
      dsplNmSource: 'operator',
      optrIndctNm: '일반화재',
      presetLinkStatus: null,
    });
    vi.mocked(adminApi.getEventTypeAdminList).mockImplementation((filter) =>
      Promise.resolve(
        filter === PRESET_LINK_WITHHELD
          ? WITHHELD_ROWS.filter((r) => r.evntTypeCd !== 'EV02000101')
          : ALL_ROWS,
      ),
    );

    // when
    const row = screen.getByTestId('event-type-row-EV02000101');
    await user.click(within(row).getByRole('button', { name: '표시명 수정' }));
    await user.type(screen.getByLabelText('EV02000101 표시명'), '일반화재');
    await user.click(within(row).getByRole('button', { name: '저장' }));

    // then: 재조회가 끝나 다른 행이 빠진 뒤에도 방금 저장한 행은 화면에 남는다 —
    //   그냥 사라지면 무엇을 저장했는지 확인할 자리가 없어진다.
    await waitFor(() =>
      expect(vi.mocked(adminApi.getEventTypeAdminList).mock.calls.length).toBeGreaterThan(2),
    );
    await waitFor(() => expect(screen.getByText('일반화재')).toBeInTheDocument());
    expect(screen.getByTestId('event-type-row-EV02000101')).toBeInTheDocument();
  });

  it('★화면은_보류_상태_집합을_스스로_만들지_않는다_정적_가드', () => {
    // 조합을 코드에 적는 순간 「무엇이 보류인가」의 두 번째 진실원이 생긴다.
    // 값 테스트만으로는 못 잡는다 — 조합해 보내도 같은 결과가 나오는 모수가 있기 때문이다.
    const src = readFileSync(
      path.resolve(__dirname, '../../../pages/manage/EventTypeManagePage.tsx'),
      'utf-8',
    );
    for (const token of ['LINKED_INEFFECTIVE', 'UNLINKED', 'LINKED_EXCLUDED']) {
      expect(src, `화면이 ${token} 를 직접 다룬다`).not.toContain(token);
    }
  });
});
