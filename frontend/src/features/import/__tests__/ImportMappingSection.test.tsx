import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import MockAdapter from 'axios-mock-adapter';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { ConfirmedMappingSection } from '@/features/import/components/ConfirmedMappingSection';
import type { ImportMapping } from '@/features/import/types';
import { apiClient } from '@/lib/api/client';
import { renderWithProviders } from '@/test/renderWithProviders';
import { useAuthStore } from '@/stores/useAuthStore';

function listBody(items: ImportMapping[]) {
  return {
    success: true,
    data: { items, page: 0, size: 20, totalElements: items.length, totalPages: 1 },
    message: null,
    errorCode: null,
  };
}

/** 라벨 축 — 외부 분류 코드(영문)와 표시 이름(한글)이 서로 다르다. */
const LABEL_ROW: ImportMapping = {
  mpngSn: 11,
  kind: 'LABEL',
  externalCode: 'person',
  externalName: '사람',
  labelId: 3,
  labelName: '보행자',
  evntTypeCd: null,
  useYn: 'Y',
};

/** 이벤트 축 — 코드가 없어 이름이 곧 코드 자리에 들어간다(두 열이 같은 값). */
const EVENT_ROW: ImportMapping = {
  mpngSn: 12,
  kind: 'EVNT_TYPE',
  externalCode: '화재',
  externalName: '화재',
  labelId: null,
  labelName: null,
  evntTypeCd: 'EV02000101',
  useYn: 'Y',
};

describe('확정된 대응 — 해제는 확인 단계를 거친다', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
    useAuthStore.setState({
      token: 'tok',
      claims: { sub: '1', role: 'REVIEWER', channel: 'INTERNAL', exp: 9999999999 },
    });
  });

  afterEach(() => {
    mock.restore();
    useAuthStore.getState().clear();
  });

  /**
   * ★해제는 파괴적 조작에 준한다 — 되돌리면 그 분류가 다시 처음 보는 분류가 되어 다음 산출물을
   * 가져올 때 적재가 막힌다. 그래서 버튼을 누르는 것만으로 성립하지 않는다.
   *
   * ⚠ mutation 확인 절차: `onClick={() => setPendingDisable(m)}` 를 `disable(m.mpngSn)` 로
   *   바꾸면 이 케이스가 실패해야 한다(실제로 확인함).
   */
  it('★해제_버튼만_눌러서는_요청이_나가지_않고_확인_문구가_먼저_뜬다', async () => {
    mock.onGet('/import-mappings').reply(200, listBody([LABEL_ROW]));
    const user = userEvent.setup();
    renderWithProviders(<ConfirmedMappingSection />);

    await user.click(await screen.findByTestId('import-mapping-disable-11'));

    // 요청이 아직 나가지 않았다
    expect(mock.history.delete).toHaveLength(0);
    // 확인 문구는 무엇이 되돌려지는지와 그 결과를 함께 말한다
    const dialog = await screen.findByRole('dialog');
    expect(dialog).toHaveTextContent('처음 보는 분류가 됩니다');
    expect(dialog).toHaveTextContent('적재가 막힙니다');
  });

  it('확인을_눌러야_해제_요청이_나간다', async () => {
    mock.onGet('/import-mappings').reply(200, listBody([LABEL_ROW]));
    mock.onDelete('/import-mappings/11').reply(204);
    const user = userEvent.setup();
    renderWithProviders(<ConfirmedMappingSection />);

    await user.click(await screen.findByTestId('import-mapping-disable-11'));
    // 확인 단계의 확정 버튼 — 표 안의 같은 이름 버튼과 구분해 다이얼로그 안에서 찾는다.
    const dialog = await screen.findByRole('dialog');
    await user.click(within(dialog).getByRole('button', { name: '해제' }));

    await waitFor(() => expect(mock.history.delete).toHaveLength(1));
    expect(mock.history.delete[0].url).toBe('/import-mappings/11');
  });

  it('취소하면_요청이_나가지_않는다', async () => {
    mock.onGet('/import-mappings').reply(200, listBody([LABEL_ROW]));
    const user = userEvent.setup();
    renderWithProviders(<ConfirmedMappingSection />);

    await user.click(await screen.findByTestId('import-mapping-disable-11'));
    await user.click(screen.getByRole('button', { name: '취소' }));

    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    expect(mock.history.delete).toHaveLength(0);
  });

  it('쓰지_않는_대응에는_해제_버튼이_없다', async () => {
    mock.onGet('/import-mappings').reply(200, listBody([{ ...LABEL_ROW, useYn: 'N' }]));
    renderWithProviders(<ConfirmedMappingSection />);

    await screen.findByTestId('import-mapping-row-11');
    expect(screen.queryByTestId('import-mapping-disable-11')).not.toBeInTheDocument();
  });
});

describe('확정된 대응 — 외부 분류 코드와 표시 이름은 두 열이다', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
    useAuthStore.setState({
      token: 'tok',
      claims: { sub: '1', role: 'REVIEWER', channel: 'INTERNAL', exp: 9999999999 },
    });
  });

  afterEach(() => {
    mock.restore();
    useAuthStore.getState().clear();
  });

  /**
   * 라벨 축은 두 열이 실제로 다른 값을 담는다 — 열을 하나로 합치면 그쪽이 손해를 본다.
   * 이벤트 축에서 두 열이 같아 보이는 것은 그 축의 사실이며 결함이 아니다.
   */
  it('★두_열이_유지되고_라벨_축에서는_서로_다른_값을_담는다', async () => {
    mock.onGet('/import-mappings').reply(200, listBody([LABEL_ROW, EVENT_ROW]));
    renderWithProviders(<ConfirmedMappingSection />);

    expect(await screen.findByRole('columnheader', { name: '외부 분류 코드' })).toBeInTheDocument();
    expect(screen.getByRole('columnheader', { name: '외부 표시 이름' })).toBeInTheDocument();

    // 라벨 축 — 코드(person)와 이름(사람)이 각각 보인다
    const labelRow = screen.getByTestId('import-mapping-row-11');
    expect(labelRow).toHaveTextContent('person');
    expect(labelRow).toHaveTextContent('사람');
    expect(labelRow).toHaveTextContent('보행자');

    // 이벤트 축 — 두 열이 같은 값이지만 그대로 보여준다
    const eventRow = screen.getByTestId('import-mapping-row-12');
    expect(eventRow).toHaveTextContent('화재');
    expect(eventRow).toHaveTextContent('EV02000101');
  });

  it('연결이_비어_있어도_감추지_않고_미연결로_보여준다', async () => {
    mock
      .onGet('/import-mappings')
      .reply(200, listBody([{ ...LABEL_ROW, labelId: 3, labelName: null }]));
    renderWithProviders(<ConfirmedMappingSection />);

    expect(await screen.findByTestId('import-mapping-row-11')).toHaveTextContent('미연결');
  });

  it('기본은_쓰는_대응만_조회한다', async () => {
    mock.onGet('/import-mappings').reply(200, listBody([LABEL_ROW]));
    renderWithProviders(<ConfirmedMappingSection />);

    await screen.findByTestId('import-mapping-row-11');
    expect(mock.history.get[0].params).toMatchObject({ includeUnused: false, page: 0, size: 20 });
  });
});
