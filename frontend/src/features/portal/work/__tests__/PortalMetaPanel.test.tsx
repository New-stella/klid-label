// 포털 메타 패널 — 렌더·전송·거부 안내. @design SCREEN-029 @design API-234 @design API-235
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import MockAdapter from 'axios-mock-adapter';

import { apiClient } from '@/lib/api/client';
import { renderWithProviders } from '@/test/renderWithProviders';
import { selectRadixOption } from '@/test/selectTestUtils';

import { PortalMetaPanel } from '../components/PortalMetaPanel';
import { FRAME_DESCRIPTION_MAX_LENGTH, PORTAL_META_KEYS } from '../metaFields';
import { PORTAL_WORK_ERROR_UNAVAILABLE } from '../workError';
import type { PortalMetaItem } from '../types';

const SRC_SN = 555;

/** 저장이 나가야 하는 <b>정확한 자리</b>. 같은 값을 고치는 내부 창구는 `/frames/{srcSn}/meta` 다. */
const PORTAL_META_URL = `/portal/frames/${SRC_SN}/meta`;

function ok(data: unknown) {
  return { success: true, data, message: null, errorCode: null };
}

/**
 * 저장 요청 1건을 <b>주소까지</b> 확인하고 본문을 돌려준다.
 *
 * ★본문만 단언하면 <b>주소를 내부 창구로 바꾸는 변이가 살아남는다</b> — 미매치 요청도 history 에는
 *   남아 `history.put[0].data` 가 그대로 읽히기 때문이다. 실제로 그 변이가 포털 시험 전건을
 *   통과했다. 내부 창구를 부르면 원장 컬럼 쓰기·재검토 표시·관제 통지·동결본 재동결이 함께 일어나
 *   「원본·데이터마트를 수정하지 않는다(단방향)」가 한 번에 깨진다.
 * ★mock 미등록으로 인한 404 는 「안 불렀다」와 구분되지 않으므로 <b>값(주소)으로</b> 판정한다.
 */
function sentMetaItems(mock: MockAdapter) {
  expect(mock.history.put).toHaveLength(1);
  expect(mock.history.put[0].url).toBe(PORTAL_META_URL);
  return (
    JSON.parse(mock.history.put[0].data) as {
      items: { metaKey: string; metaVl: string | null; scope: string }[];
    }
  ).items;
}

function meta(over: Partial<PortalMetaItem> & Pick<PortalMetaItem, 'metaKey' | 'scope'>): PortalMetaItem {
  return { metaVl: null, overridden: false, source: 'DERIVED', ...over };
}

/**
 * 기본 픽스처 — ★개인정보 익명여부가 <b>두 축에 같은 이름</b>으로 들어 있다(값이 다르다).
 * 축을 섞는 회귀가 나면 한쪽 값이 다른 쪽 자리에 나타난다.
 */
const ITEMS: PortalMetaItem[] = [
  meta({ metaKey: PORTAL_META_KEYS.ENV_WEATHER, scope: 'video', metaVl: null, source: 'NONE' }),
  meta({ metaKey: PORTAL_META_KEYS.ENV_TIME_OF_DAY, scope: 'video', metaVl: 'DAY', source: 'DERIVED' }),
  meta({ metaKey: PORTAL_META_KEYS.ENV_SEASON, scope: 'video', metaVl: 'WINTER', source: 'DERIVED' }),
  meta({ metaKey: PORTAL_META_KEYS.PRIVACY_ANONYMITY, scope: 'video', metaVl: 'Y', source: 'MANUAL' }),
  meta({ metaKey: PORTAL_META_KEYS.PRIVACY_ANONYMITY, scope: 'frame', metaVl: 'N', source: 'DERIVED' }),
  meta({
    metaKey: PORTAL_META_KEYS.FRAME_DESCRIPTION,
    scope: 'frame',
    metaVl: '기존 설명',
    source: 'MANUAL',
  }),
];

function metaPayload(over: Record<string, unknown> = {}) {
  return ok({
    rawSn: 5,
    srcSn: SRC_SN,
    items: ITEMS,
    readOnlyMeta: [meta({ metaKey: 'import.video.location', scope: 'video', metaVl: '서울', source: 'STORED' })],
    technicalMeta: [meta({ metaKey: 'video.fps', scope: 'video', metaVl: '30', source: 'STORED' })],
    ...over,
  });
}

describe('PortalMetaPanel', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
  });

  afterEach(() => mock.restore());

  /**
   * 렌더 후 <b>조회가 끝날 때까지</b> 기다린다 — 껍데기(`portal-meta-panel`)는 로딩 중에도 서므로
   * 그것만 기다리면 행이 하나도 없는 상태에서 단언하게 된다.
   */
  async function renderPanel() {
    const view = renderWithProviders(<PortalMetaPanel srcSn={SRC_SN} />);
    await screen.findByTestId(`portal-meta-row-frame:${PORTAL_META_KEYS.FRAME_DESCRIPTION}`);
    return view;
  }

  it('영상축과_프레임축의_같은_이름_항목이_섞이지_않는다', async () => {
    mock.onGet(`/portal/frames/${SRC_SN}/meta`).reply(200, metaPayload());
    await renderPanel();

    const videoRow = await screen.findByTestId(
      `portal-meta-row-video:${PORTAL_META_KEYS.PRIVACY_ANONYMITY}`,
    );
    const frameRow = screen.getByTestId(`portal-meta-row-frame:${PORTAL_META_KEYS.PRIVACY_ANONYMITY}`);

    // 두 행이 각자 서고 값도 따로 논다(영상=예 / 프레임=아니오).
    expect(within(videoRow).getByRole('combobox')).toHaveTextContent('예');
    expect(within(frameRow).getByRole('combobox')).toHaveTextContent('아니오');
  });

  it('두_표시는_서로_다른_것을_말한다_덮음과_원본_출처가_함께_선다', async () => {
    mock.onGet(`/portal/frames/${SRC_SN}/meta`).reply(
      200,
      metaPayload({
        items: [
          // 내가 덮었고, 그 아래는 자동으로 계산된 값이었다.
          meta({
            metaKey: PORTAL_META_KEYS.ENV_SEASON,
            scope: 'video',
            metaVl: 'SPRING',
            overridden: true,
            source: 'DERIVED',
          }),
        ],
      }),
    );
    // ★이 케이스는 픽스처를 좁혀 두었으므로 공용 대기(프레임 설명 행)를 쓰지 않는다.
    renderWithProviders(<PortalMetaPanel srcSn={SRC_SN} />);

    const badges = await screen.findByTestId(
      `portal-meta-badges-video:${PORTAL_META_KEYS.ENV_SEASON}`,
    );
    expect(badges).toHaveTextContent('내가 고침');
    expect(badges).toHaveTextContent('자동으로 계산된 값');
  });

  it('손대지_않은_자동_계산값은_전송_payload_에_담기지_않는다', async () => {
    mock.onGet(`/portal/frames/${SRC_SN}/meta`).reply(200, metaPayload());
    mock.onPut(`/portal/frames/${SRC_SN}/meta`).reply(200, metaPayload());
    const user = userEvent.setup();
    await renderPanel();

    // 프레임 축 익명여부(자동 계산값)만 고친다.
    const frameRow = screen.getByTestId(`portal-meta-row-frame:${PORTAL_META_KEYS.PRIVACY_ANONYMITY}`);
    await selectRadixOption(user, within(frameRow).getByRole('combobox'), '예');
    await user.click(screen.getByRole('button', { name: '메타 저장' }));

    await waitFor(() => expect(mock.history.put).toHaveLength(1));
    const keys = sentMetaItems(mock).map((i) => `${i.scope}:${i.metaKey}`);

    // 고친 항목 + 원본이 이미 사람이 고른 값(MANUAL)인 항목만 실린다.
    expect(keys).toContain(`frame:${PORTAL_META_KEYS.PRIVACY_ANONYMITY}`);
    expect(keys).toContain(`video:${PORTAL_META_KEYS.PRIVACY_ANONYMITY}`);
    expect(keys).toContain(`frame:${PORTAL_META_KEYS.FRAME_DESCRIPTION}`);
    // ★손대지 않은 자동 계산값(시간대·계절)은 실리지 않는다 — 실리면 사람의 판정으로 승격된다.
    expect(keys).not.toContain(`video:${PORTAL_META_KEYS.ENV_TIME_OF_DAY}`);
    expect(keys).not.toContain(`video:${PORTAL_META_KEYS.ENV_SEASON}`);
    // 값 자체가 없던 자리도 손대지 않았으면 실리지 않는다.
    expect(keys).not.toContain(`video:${PORTAL_META_KEYS.ENV_WEATHER}`);
  });

  it('시계열_메타를_새로_더할_자리가_있고_그_값이_전송된다', async () => {
    // 「확인·수정」뿐 아니라 「추가」도 되어야 한다. 열쇠는 내부 화면과 같은 표준 열쇠를 쓴다.
    mock.onGet(`/portal/frames/${SRC_SN}/meta`).reply(200, metaPayload());
    mock.onPut(`/portal/frames/${SRC_SN}/meta`).reply(200, metaPayload());
    const user = userEvent.setup();
    await renderPanel();

    const slot = screen.getByTestId('portal-meta-row-video:manual-timeseries');
    await user.type(within(slot).getByRole('textbox'), '내가 쓴 시계열 서술');
    await user.click(screen.getByRole('button', { name: '메타 저장' }));

    await waitFor(() => expect(mock.history.put).toHaveLength(1));
    expect(sentMetaItems(mock)).toContainEqual({
      metaKey: 'manual-timeseries',
      metaVl: '내가 쓴 시계열 서술',
      // 메타 원장은 (영상, 키) 단위라 이 축은 영상이다.
      scope: 'video',
    });
  });

  /**
   * ★구 동작은 「편집 가능한 시계열 항목이 <b>하나도 없을 때만</b>」 자리를 뒀다 — 서버가 항목을
   * 하나라도 내려주는 순간 <b>추가가 불가능</b>해졌다. 사양은 다섯 축 전부에 확인·수정·추가를
   * 요구한다. 픽스처는 그 조건(기존 항목 1건)을 실제로 만들어야 회귀를 잡는다.
   */
  it('기존_시계열_항목이_있어도_새로_더할_자리가_사라지지_않는다', async () => {
    mock.onGet(`/portal/frames/${SRC_SN}/meta`).reply(
      200,
      metaPayload({
        items: [...ITEMS, meta({ metaKey: 'vlm.description', scope: 'video', metaVl: '기존 서술' })],
      }),
    );
    mock.onPut(`/portal/frames/${SRC_SN}/meta`).reply(200, metaPayload());
    const user = userEvent.setup();
    await renderPanel();

    // 기존 항목은 그대로 서고,
    expect(screen.getByTestId('portal-meta-row-video:vlm.description')).toBeInTheDocument();
    // 새로 더할 자리도 함께 선다.
    const slot = screen.getByTestId('portal-meta-row-video:manual-timeseries');
    await user.type(within(slot).getByRole('textbox'), '덧붙인 서술');
    await user.click(screen.getByRole('button', { name: '메타 저장' }));

    await waitFor(() => expect(mock.history.put).toHaveLength(1));
    const sent = sentMetaItems(mock);
    expect(sent).toContainEqual({
      metaKey: 'manual-timeseries',
      metaVl: '덧붙인 서술',
      scope: 'video',
    });
    // 손대지 않은 기존 서술은 실리지 않는다(승격 방지 규칙은 그대로다).
    expect(sent.map((i) => i.metaKey)).not.toContain('vlm.description');
  });

  /**
   * ★그런데 <b>같은 열쇠를 두 자리에 두지는 않는다</b> — 창구는 같은 열쇠를 덮어쓰므로 한쪽에 쓴
   * 값이 다른 쪽 값에 조용히 지워진다. 표준 열쇠가 이미 응답에 있으면 그 행이 곧 편집 자리다.
   */
  it('표준_열쇠가_이미_응답에_있으면_빈_자리를_따로_두지_않는다', async () => {
    mock.onGet(`/portal/frames/${SRC_SN}/meta`).reply(
      200,
      metaPayload({
        items: [
          ...ITEMS,
          meta({ metaKey: 'manual-timeseries', scope: 'video', metaVl: '이미 쓴 서술', source: 'MANUAL' }),
        ],
      }),
    );
    await renderPanel();

    const rows = screen.getAllByTestId('portal-meta-row-video:manual-timeseries');
    expect(rows).toHaveLength(1);
    // 그리고 그 하나가 <b>기존 값을 담은</b> 편집 자리다(빈 자리가 값을 덮어쓰지 않는다).
    expect(within(rows[0]).getByRole('textbox')).toHaveValue('이미 쓴 서술');
  });

  it('프레임_설명_입력_폭은_1000자다', async () => {
    mock.onGet(`/portal/frames/${SRC_SN}/meta`).reply(200, metaPayload());
    await renderPanel();

    const row = screen.getByTestId(`portal-meta-row-frame:${PORTAL_META_KEYS.FRAME_DESCRIPTION}`);
    // 원본 폭이 오버레이 저장 폭보다 좁아 <b>좁은 쪽</b>이 기준이다.
    expect(within(row).getByRole('textbox')).toHaveAttribute(
      'maxlength',
      String(FRAME_DESCRIPTION_MAX_LENGTH),
    );
    expect(FRAME_DESCRIPTION_MAX_LENGTH).toBe(1000);
  });

  it('표시_전용_메타와_영상_기술_메타는_편집_수단을_두지_않는다', async () => {
    mock.onGet(`/portal/frames/${SRC_SN}/meta`).reply(200, metaPayload());
    const user = userEvent.setup();
    await renderPanel();

    // 두 섹션은 접혀 있다 — 펼쳐서 내용을 확인한다.
    await user.click(screen.getByRole('button', { name: '참고 정보(수정 불가)' }));
    await user.click(screen.getByRole('button', { name: '영상 기술 정보(수정 불가)' }));

    for (const testId of ['portal-meta-readonly', 'portal-meta-technical']) {
      const section = screen.getByTestId(testId);
      expect(within(section).queryByRole('textbox')).toBeNull();
      expect(within(section).queryByRole('combobox')).toBeNull();
      // 비활성 컨트롤을 두는 것도 아니다 — 렌더 자체를 하지 않는다.
      expect(section.querySelector('input,textarea,select,button')).toBeNull();
    }
    // 값은 그대로 보인다(버리지 않는다).
    expect(screen.getByTestId('portal-meta-readonly')).toHaveTextContent('서울');
    expect(screen.getByTestId('portal-meta-technical')).toHaveTextContent('30');
  });

  describe('거부 안내', () => {
    it.each([403, 404])('%d 거부의 안내가 실재 여부를 가르지 않는다', async (status) => {
      mock.onGet(`/portal/frames/${SRC_SN}/meta`).reply(status, {
        success: false,
        data: null,
        message: null,
        errorCode: null,
      });
      renderWithProviders(<PortalMetaPanel srcSn={SRC_SN} />);
      const notice = await screen.findByTestId('portal-meta-error');
      expect(notice).toHaveTextContent(PORTAL_WORK_ERROR_UNAVAILABLE);
    });

    it('비식별_누락_신고_구간은_사유를_알린다', async () => {
      mock.onGet(`/portal/frames/${SRC_SN}/meta`).reply(412, {
        success: false,
        data: null,
        message: null,
        errorCode: 'PRECONDITION_FAILED',
      });
      renderWithProviders(<PortalMetaPanel srcSn={SRC_SN} />);
      expect(await screen.findByTestId('portal-meta-error')).toHaveTextContent(
        /비식별 재처리 대기 중/,
      );
    });
  });

  it('무변경_저장이_내_작업물을_만들지_않는다는_사실을_감추지_않는다', async () => {
    mock.onGet(`/portal/frames/${SRC_SN}/meta`).reply(200, metaPayload());
    await renderPanel();
    expect(screen.getByTestId('portal-meta-panel')).toHaveTextContent(
      /원본과 같은 값은 저장해도 내 작업물로 남지 않습니다/,
    );
  });
});
