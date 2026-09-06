// 포털 이벤트 어노테이션 패널 — 구조체 왕복·거부 안내·미제공 축.
//
// @design SCREEN-029 @design API-236 @design API-237
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import MockAdapter from 'axios-mock-adapter';

import { apiClient } from '@/lib/api/client';
import { renderWithProviders } from '@/test/renderWithProviders';

import { PortalEventAnnotationPanel } from '../components/PortalEventAnnotationPanel';
import { buildPortalAnnotationPayload, stableStringify } from '../annotationForm';
import { PORTAL_WORK_ERROR_UNAVAILABLE } from '../workError';

const RAW_SN = 5;

/** 저장이 나가야 하는 <b>정확한 자리</b>. 같은 값을 고치는 내부 창구는 `/videos/{rawSn}/event-annotation` 다. */
const PORTAL_ANNOTATION_URL = `/portal/videos/${RAW_SN}/event-annotation`;

function ok(data: unknown) {
  return { success: true, data, message: null, errorCode: null };
}

const ANNOTATION = {
  event_class: '화재',
  question: '불이 났는가?',
  answer: '예',
  caption: { c1: { caption_text: '연기가 보인다', cot: ['1', '2', '3'] } },
  evidence: { c1: { evidence_text: '근거', frame_id: [3] } },
};

describe('PortalEventAnnotationPanel', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
  });

  afterEach(() => mock.restore());

  it('불러온_구조체를_그대로_그리고_고친_결과를_구조체로_되돌려_보낸다', async () => {
    mock.onGet(`/portal/videos/${RAW_SN}/event-annotation`).reply(
      200,
      ok({ rawSn: RAW_SN, annotation: ANNOTATION, overridden: false }),
    );
    mock.onPut(`/portal/videos/${RAW_SN}/event-annotation`).reply(
      200,
      ok({ rawSn: RAW_SN, annotation: ANNOTATION, overridden: true }),
    );
    const user = userEvent.setup();
    renderWithProviders(<PortalEventAnnotationPanel rawSn={RAW_SN} />);

    // ⚠ 입력 칸은 첫 렌더부터 서 있으므로 «존재»만 기다리면 프리필 전 상태를 단언하게 된다.
    const answer = await screen.findByLabelText('답변');
    await waitFor(() => expect(answer).toHaveValue('예'));

    await user.clear(answer);
    await user.type(answer, '아니오');
    await user.click(screen.getByRole('button', { name: '이벤트 어노테이션 저장' }));

    await waitFor(() => expect(mock.history.put).toHaveLength(1));
    // ★본문만 보면 <b>주소를 내부 창구로 바꾸는 변이가 살아남는다</b> — 미매치 요청도 history 에는
    //   남아 `history.put[0].data` 가 그대로 읽히기 때문이다(실제로 그 변이가 전건을 통과했다).
    //   내부 창구를 부르면 원장 쓰기·재검토 표시·관제 통지·동결본 재동결이 함께 일어나 단방향
    //   불변이 한 번에 깨진다. mock 미등록 404 와 구분되도록 <b>값(주소)으로</b> 판정한다.
    expect(mock.history.put[0].url).toBe(PORTAL_ANNOTATION_URL);
    const sent = JSON.parse(mock.history.put[0].data) as { annotation: Record<string, unknown> };
    // 키별로 펴지 않고 구조체 한 벌을 그대로 보낸다.
    expect(sent.annotation.answer).toBe('아니오');
    expect(sent.annotation.event_class).toBe('화재');
    expect(sent.annotation.caption).toEqual(ANNOTATION.caption);
  });

  it('무변경_상태에서는_저장_버튼이_눌리지_않는다', async () => {
    mock.onGet(`/portal/videos/${RAW_SN}/event-annotation`).reply(
      200,
      ok({ rawSn: RAW_SN, annotation: ANNOTATION, overridden: false }),
    );
    renderWithProviders(<PortalEventAnnotationPanel rawSn={RAW_SN} />);
    // 프리필이 끝난 뒤에 본다 — 로딩 중에는 이벤트 분류가 비어 어차피 비활성이라 무의미하다.
    await waitFor(() => expect(screen.getByLabelText('답변')).toHaveValue('예'));
    expect(screen.getByRole('button', { name: '이벤트 어노테이션 저장' })).toBeDisabled();
  });

  it('무변경_저장이_내_작업물을_만들지_않는다는_사실을_감추지_않는다', async () => {
    mock.onGet(`/portal/videos/${RAW_SN}/event-annotation`).reply(
      200,
      ok({ rawSn: RAW_SN, annotation: ANNOTATION, overridden: false }),
    );
    renderWithProviders(<PortalEventAnnotationPanel rawSn={RAW_SN} />);
    expect(await screen.findByTestId('portal-annotation-panel')).toHaveTextContent(
      /원본과 같은 내용으로 저장하면 내 작업물로 남지 않습니다/,
    );
  });

  it('캔버스와_이어지지_않는_보조_버튼은_비활성이_아니라_렌더되지_않는다', async () => {
    mock.onGet(`/portal/videos/${RAW_SN}/event-annotation`).reply(
      200,
      ok({ rawSn: RAW_SN, annotation: ANNOTATION, overridden: false }),
    );
    renderWithProviders(<PortalEventAnnotationPanel rawSn={RAW_SN} />);
    await waitFor(() => expect(screen.getByLabelText('답변')).toHaveValue('예'));
    // 눌리는 모양인데 영원히 반응이 없으면 사용자가 고장으로 읽는다.
    expect(screen.queryByTestId('ea-evidence-add-selected-c1')).toBeNull();
    expect(screen.queryByTestId('ea-evidence-frameid-current-c1')).toBeNull();
  });

  describe('거부 안내', () => {
    it.each([403, 404])('%d 거부의 안내가 실재 여부를 가르지 않는다', async (status) => {
      mock
        .onGet(`/portal/videos/${RAW_SN}/event-annotation`)
        .reply(status, { success: false, data: null, message: null, errorCode: null });
      renderWithProviders(<PortalEventAnnotationPanel rawSn={RAW_SN} />);
      expect(await screen.findByTestId('portal-annotation-error')).toHaveTextContent(
        PORTAL_WORK_ERROR_UNAVAILABLE,
      );
    });
  });
});

describe('buildPortalAnnotationPayload', () => {
  it('화면이_모르는_키를_저장하면서_잃지_않는다', () => {
    // 저장은 구조체 <b>한 벌을 통째로 덮어쓰는</b> 창구라, 조립에서 흘리면 그 값이 사라진다.
    const base = { ...ANNOTATION, future_field: { deep: [1, 2] } } as never;
    const built = buildPortalAnnotationPayload(base, {
      eventClass: '화재',
      question: '불이 났는가?',
      answer: '예',
      captions: [{ key: 'c1', captionText: '연기가 보인다', cot: ['1', '2', '3'] }],
      evidences: [
        { key: 'c1', evidenceText: '근거', frameId: '3', objId: '', objBbox: '', objLabel: '' },
      ],
    });
    expect((built as unknown as Record<string, unknown>).future_field).toEqual({ deep: [1, 2] });
  });

  it('무변경_판정은_키_순서에_흔들리지_않는다', () => {
    expect(stableStringify({ a: 1, b: 2 })).toBe(stableStringify({ b: 2, a: 1 }));
    expect(stableStringify({ a: 1 })).not.toBe(stableStringify({ a: 2 }));
  });
});
