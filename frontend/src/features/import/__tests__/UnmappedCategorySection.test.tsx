import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import MockAdapter from 'axios-mock-adapter';
import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { UnmappedCategorySection } from '@/features/import/components/UnmappedCategorySection';
import type { UnmappedCategory } from '@/features/import/types';
import { apiClient } from '@/lib/api/client';
import { renderWithProviders } from '@/test/renderWithProviders';
import { selectRadixOption } from '@/test/selectTestUtils';
import { useAuthStore } from '@/stores/useAuthStore';

const LABEL_MASTERS = [
  { labelId: 3, name: '보행자', color: '#EF4444', type: 'BBOX', sortNo: 1, useYn: 'Y', dtctTypeCd: 'person' },
  { labelId: 4, name: '차량', color: '#3B82F6', type: 'BBOX', sortNo: 2, useYn: 'Y', dtctTypeCd: 'car' },
];

const WITH_SUGGESTION: UnmappedCategory = {
  kind: 'LABEL',
  externalCode: 'person',
  externalName: '사람',
  suggestions: [{ targetId: '3', targetName: '보행자' }],
};

const WITHOUT_SUGGESTION: UnmappedCategory = {
  kind: 'LABEL',
  externalCode: 'bicycle',
  externalName: '자전거',
  suggestions: [],
};

describe('분류 대응 확정 — 추천은 후보 제시까지다', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
    mock
      .onGet('/manage/labels')
      .reply(200, { success: true, data: LABEL_MASTERS, message: null, errorCode: null });
    mock
      .onGet('/manage/event-types')
      .reply(200, { success: true, data: [], message: null, errorCode: null });
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
   * ★서버가 이름이 비슷한 후보를 하나 골라 주면 미리 선택해 두지만, 그것만으로는 확정되지 않는다.
   * 짐작으로 연결하면 다른 분류로 저장되고 나중에 구분할 수 없기 때문이다.
   *
   * ⚠ mutation 확인 절차: `confirmed[rowKeyOf(c)]` 조건을 `readyItems` 필터에서 빼면 이
   *   케이스가 실패해야 한다(실제로 확인함).
   */
  it('★추천이_미리_골라져_있어도_사람이_확인하기_전에는_확정할_수_없다', async () => {
    const onConfirm = vi.fn();
    const user = userEvent.setup();
    renderWithProviders(
      <UnmappedCategorySection categories={[WITH_SUGGESTION]} saving={false} onConfirm={onConfirm} />,
    );

    const button = await screen.findByTestId('import-mapping-confirm-button');
    expect(button).toBeDisabled();
    await user.click(button);
    expect(onConfirm).not.toHaveBeenCalled();
  });

  it('확인하면_추천값을_실어_확정_요청을_만든다', async () => {
    const onConfirm = vi.fn();
    const user = userEvent.setup();
    renderWithProviders(
      <UnmappedCategorySection categories={[WITH_SUGGESTION]} saving={false} onConfirm={onConfirm} />,
    );

    await user.click(await screen.findByTestId('import-unmapped-confirm-LABEL:person'));
    await user.click(screen.getByTestId('import-mapping-confirm-button'));

    expect(onConfirm).toHaveBeenCalledWith([
      { kind: 'LABEL', externalCode: 'person', externalName: '사람', labelId: 3 },
    ]);
  });

  /** 후보가 비어 있는 것은 오류가 아니다 — 사람이 목록에서 직접 고른다. */
  it('★후보가_비어_있어도_오류로_그리지_않고_사람이_직접_고른다', async () => {
    const onConfirm = vi.fn();
    const user = userEvent.setup();
    renderWithProviders(
      <UnmappedCategorySection
        categories={[WITHOUT_SUGGESTION]}
        saving={false}
        onConfirm={onConfirm}
      />,
    );

    const row = await screen.findByTestId('import-unmapped-row-LABEL:bicycle');
    expect(row).toBeInTheDocument();

    // 확인만 켜도 대상이 없으면 확정 대상이 되지 않는다
    await user.click(screen.getByTestId('import-unmapped-confirm-LABEL:bicycle'));
    expect(screen.getByTestId('import-mapping-confirm-button')).toBeDisabled();

    // 사람이 고르면 확정할 수 있다
    await selectRadixOption(
      user,
      screen.getByRole('combobox', { name: 'bicycle 연결할 대상' }),
      '차량',
    );
    await user.click(screen.getByTestId('import-mapping-confirm-button'));

    expect(onConfirm).toHaveBeenCalledWith([
      { kind: 'LABEL', externalCode: 'bicycle', externalName: '자전거', labelId: 4 },
    ]);
  });

  it('두_열이_유지된다_외부_분류_코드와_외부_표시_이름', async () => {
    renderWithProviders(
      <UnmappedCategorySection categories={[WITH_SUGGESTION]} saving={false} onConfirm={vi.fn()} />,
    );

    expect(await screen.findByRole('columnheader', { name: '외부 분류 코드' })).toBeInTheDocument();
    expect(screen.getByRole('columnheader', { name: '외부 표시 이름' })).toBeInTheDocument();
  });

  it('대응할_분류가_없으면_구획_자체가_없다', () => {
    renderWithProviders(
      <UnmappedCategorySection categories={[]} saving={false} onConfirm={vi.fn()} />,
    );
    expect(screen.queryByTestId('import-unmapped-section')).not.toBeInTheDocument();
  });
});
