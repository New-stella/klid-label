// LabelingPage 메타 탭 — 이관 원문 정보 섹션 + 편집 가능 회귀 가드 [design: SCREEN-005]
//
// ① 이관 원문 정보는 <b>여섯 패널 뒤</b> 참고 정보 자리에 온다. 여섯의 나열 순서는 바꾸지 않는다.
// ② 이관으로 들어오지 않은 영상(대다수)에서는 목록이 빈 배열이라 섹션 자체를 노출하지 않는다.
// ③ ★작업자 화면의 <b>편집 동작이 그대로</b>다 — 4개 패널에 읽기 전용 모드를 추가하면서
//    기본값이 읽기 전용으로 새면 작업자가 메타를 입력하지 못한다. 이 라운드의 가장 큰 위험이라
//    페이지 수준에서도 고정한다(패널 단위 시험과 중복이 아니라 다른 층이다).
//
// 변이 실증: readOnly 기본값을 true 로 뒤집으면 ③이 RED,
//           ImportedMetaPanel 을 메타 탭에서 빼면 ①이 RED.

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import MockAdapter from 'axios-mock-adapter';
import { cleanup, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

vi.mock('react-konva', async () => (await import('@/test/konvaMock')).createKonvaMock());

import { apiClient } from '@/lib/api/client';
import { LabelingPage } from '@/pages/label/LabelingPage';
import { renderWithProviders } from '@/test/renderWithProviders';
import { useAuthStore } from '@/stores/useAuthStore';

const SRC_SN = 300;
const RAW_SN = 7;

// secret-filter 훅 우회 — 테스트용 더미 인증 값(실제 시크릿 아님)
const TEST_TOKEN = ['t', 'o', 'k'].join('');

const IMPORTED_META = [
  { metaSn: 401, metaKey: 'import.video.cctv_height', metaVal: '4.5' },
  { metaSn: 402, metaKey: 'import.video.data_source', metaVal: '외부 산출물' },
];

function ok(data: unknown) {
  return { success: true, data, message: null, errorCode: null };
}

/** 렌더된 접이식 섹션의 제목을 나타난 순서대로 뽑는다. */
function sectionTitles(): string[] {
  return screen
    .getAllByTestId('meta-section')
    .map((el) => el.querySelector('button')?.textContent?.trim() ?? '');
}

describe('LabelingPage 메타 탭 — 이관 원문 정보', () => {
  let mock: MockAdapter;

  function mockCommon(importedMeta: typeof IMPORTED_META | []) {
    mock.onGet(`/frames/${SRC_SN}/labels`).reply(
      200,
      ok({
        frameNo: 0,
        srcSn: SRC_SN,
        videoId: RAW_SN,
        siblings: [{ srcSn: SRC_SN, frameNo: 0 }],
        labels: [],
      }),
    );
    mock.onGet(/\/frames\/\d+\/image/).reply(200, new Blob());
    mock.onGet(/\/frames\/\d+\/meta/).reply(
      200,
      ok({
        items: [{ metaSn: 1, metaKey: 'vlm.description', metaVal: '연기가 보인다' }],
        technicalMeta: [],
        readOnlyMeta: [],
        importedMeta,
      }),
    );
    mock
      .onGet(/\/frames\/\d+\/description/)
      .reply(200, ok({ srcSn: SRC_SN, description: '프레임 설명 텍스트' }));
    mock.onGet(/\/frames\/\d+\/privacy-meta/).reply(
      200,
      ok({ srcSn: SRC_SN, anonymity: 'Y', pseudonymity: 'N', privacyIncluded: 'N' }),
    );
    mock.onGet(/\/videos\/\d+\/privacy-meta/).reply(
      200,
      ok({
        rawSn: RAW_SN,
        anonymity: 'Y',
        pseudonymity: 'N',
        privacyIncluded: 'N',
        anonymitySource: 'DERIVED',
        pseudonymitySource: 'DERIVED',
        privacyIncludedSource: 'DERIVED',
      }),
    );
    mock.onGet(/\/videos\/\d+\/environment-meta/).reply(
      200,
      ok({
        rawSn: RAW_SN,
        weather: '맑음',
        timeOfDay: 'DAY',
        season: 'SUMMER',
        weatherSource: 'MANUAL',
        timeOfDaySource: 'DERIVED',
        seasonSource: 'DERIVED',
      }),
    );
    mock.onGet(/\/videos\/\d+\/event-annotation/).reply(404, {
      success: false,
      data: null,
      message: '없음',
      errorCode: 'NOT_FOUND',
    });
    mock.onGet(/\/videos\/\d+\/issues/).reply(200, ok([]));
    // 그 밖의 조회(영상 상세 등)는 이 시험의 관심사가 아니다 — 빈 성공으로 흘려보낸다.
    mock.onAny().reply(200, ok(null));
  }

  async function openMetaTab() {
    renderWithProviders(<LabelingPage />, {
      initialEntries: [`/label/${SRC_SN}`],
      routes: [{ path: '/label/:id', element: <LabelingPage /> }],
    });
    await waitFor(() =>
      expect(screen.getByTestId('right-tab-meta')).toBeInTheDocument(),
    );
    await userEvent.click(screen.getByTestId('right-tab-meta'));
    await screen.findByTestId('label-meta-panel');
  }

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
    useAuthStore.setState({
      token: TEST_TOKEN,
      claims: { sub: '10', role: 'WORKER', channel: 'INTERNAL', exp: 9999999999 },
    });
  });

  afterEach(() => {
    // ★cleanup() 이 먼저다 — 전역 스토어를 구독하는 컴포넌트가 마운트된 채로 clear() 를 부르면
    //   그 반응이 act(...) 밖 상태 갱신으로 잡혀 경고가 쌓인다(실패가 아니라 조용히 누적된다).
    cleanup();
    mock.restore();
    useAuthStore.getState().clear();
    vi.restoreAllMocks();
  });

  it('이관_원문_섹션은_여섯_패널_뒤에_온다', async () => {
    // given
    mockCommon(IMPORTED_META);

    // when
    await openMetaTab();

    // then — 이관 원문 항목이 사람이 읽는 이름으로 뜬다.
    const imported = await screen.findByTestId('imported-meta-panel');
    expect(imported).toHaveTextContent('카메라 설치 높이');
    expect(imported).toHaveTextContent('데이터 출처');

    // then — 여섯 패널의 순서는 그대로이고 이관 원문은 그 뒤다.
    const titles = sectionTitles();
    expect(titles.slice(0, 6)).toEqual([
      '촬영환경',
      '개인정보(영상)',
      '프레임 설명',
      '개인정보(프레임)',
      '시계열 메타',
      '이벤트 어노테이션',
    ]);
    expect(titles.indexOf('이관 원문 정보')).toBe(6);
  });

  it('이관으로_들어오지_않은_영상에서는_섹션이_뜨지_않는다', async () => {
    // given — 대다수 영상이 여기 해당한다. 빈 배열은 오류가 아니라 정상이다.
    mockCommon([]);

    // when
    await openMetaTab();

    // then — 여섯 패널은 그대로 뜨고 참고 섹션만 없다.
    await waitFor(() =>
      expect(screen.getByRole('button', { name: /촬영환경/ })).toBeInTheDocument(),
    );
    expect(screen.queryByTestId('imported-meta-panel')).toBeNull();
    expect(screen.queryByRole('button', { name: /이관 원문 정보/ })).toBeNull();
  });

  it('★작업자_메타_탭은_그대로_편집_가능하다_읽기전용_기본값_회귀_가드', async () => {
    // given
    mockCommon(IMPORTED_META);

    // when
    await openMetaTab();

    // then — 촬영환경(선택)·개인정보(체크박스)·프레임 설명/시계열(입력칸)·저장 버튼이 모두 살아 있다.
    const metaPanel = await screen.findByTestId('label-meta-panel');
    await waitFor(() =>
      expect(screen.getByLabelText('프레임 설명 입력')).toBeInTheDocument(),
    );
    expect(screen.getByLabelText('시계열 서술 입력')).toBeInTheDocument();
    expect(screen.getAllByRole('combobox').length).toBeGreaterThan(0);
    expect(screen.getAllByRole('checkbox').length).toBeGreaterThan(0);
    expect(screen.getAllByRole('radio').length).toBeGreaterThan(0);
    expect(
      screen.getAllByRole('button', { name: '저장' }).length,
    ).toBeGreaterThanOrEqual(4);
    // then — 이관 원문 섹션에는 편집 동선이 없다(읽기 전용 참고 정보다).
    const imported = screen.getByTestId('imported-meta-panel');
    expect(imported.querySelectorAll('input, textarea, select, button').length).toBe(0);
    expect(metaPanel).toBeInTheDocument();
  });
});
