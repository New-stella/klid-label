// 사양 SCREEN-035 — 라벨 관리 목록의 **AI 탐지 매핑 표시** 회귀 가드.
//
// 확정 사양: 목록에서 AI 탐지 매핑 상태를 바로 보인다(매핑된 검출 클래스명 또는 미매핑 표시).
// 표시 형태는 **별도 컬럼이 아니라 `라벨명` 셀 안의 칩**이다 — 컬럼 목록
// (라벨명/형태/색상/정렬순/관리)은 그대로 둔다.
//
// ⚠ jsdom 사각: CSS 로 숨긴 요소를 계산하지 못해 `sr-only` 텍스트도 getByText·toBeVisible 을
//   통과한다. "눈에 보이는 표기"를 단언할 때는 그 노드가 sr-only 안에 없음까지 확인한다.

import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import MockAdapter from 'axios-mock-adapter';
import { screen, within } from '@testing-library/react';

import { apiClient } from '@/lib/api/client';
import { LabelMasterManagePage } from '@/pages/manage/LabelMasterManagePage';
import { renderWithProviders } from '@/test/renderWithProviders';
import { useUiStore } from '@/stores/useUiStore';

const ok = (data: unknown) => ({ success: true, data, message: null, errorCode: null });

// 매핑된 라벨(사람 → person) 과 미매핑 라벨(차량) 을 함께 둔다.
const LABELS = [
  {
    labelId: 1,
    name: '사람',
    color: '#EF4444',
    type: 'BBOX',
    sortNo: 1,
    useYn: 'Y',
    dtctTypeCd: 'person',
  },
  {
    labelId: 2,
    name: '차량',
    color: '#22C55E',
    type: 'BBOX',
    sortNo: 3,
    useYn: 'Y',
    dtctTypeCd: null,
  },
];

/** 이 노드가 시각적으로 숨겨진 영역(sr-only) 안에 있지 않은지 — jsdom 사각 보완. */
function isVisuallyPresent(el: HTMLElement): boolean {
  return el.closest('.sr-only') === null;
}

describe('LabelMasterManagePage — AI 탐지 매핑 표시 (사양 SCREEN-035)', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
    mock.onGet('/manage/labels').reply(200, ok(LABELS));
  });

  afterEach(() => {
    mock.restore();
    useUiStore.setState({ toasts: [] });
  });

  it('매핑된_라벨은_검출_클래스명이_라벨명_셀에_표시된다', async () => {
    // given / when
    renderWithProviders(<LabelMasterManagePage />);
    await screen.findByText('사람');

    // then: 라벨명 셀(첫 번째 td) 안에 검출 클래스명이 눈에 보이게 표시된다.
    const row = screen.getByTestId('label-master-row-1');
    const nameCell = row.querySelectorAll('td')[0] as HTMLElement;
    const chip = within(nameCell).getByTestId('label-detect-mapping-1');
    expect(chip).toBeInTheDocument();

    const className = within(chip).getByText('person');
    expect(isVisuallyPresent(className)).toBe(true);
  });

  it('미매핑_라벨은_미매핑임을_알리는_표기가_보인다', async () => {
    // given / when
    renderWithProviders(<LabelMasterManagePage />);
    await screen.findByText('차량');

    // then: 미매핑 라벨은 AI 탐지에서 선택할 수 없으므로 그 사실이 텍스트로 읽혀야 한다
    //       (색만으로 정보를 전달하지 않는다).
    const row = screen.getByTestId('label-master-row-2');
    const chip = within(row).getByTestId('label-detect-mapping-2');
    const marker = within(chip).getByText('미매핑');
    expect(isVisuallyPresent(marker)).toBe(true);
  });

  it('매핑_표시는_스크린리더에서_AI_탐지_축임이_읽힌다', async () => {
    // given / when
    renderWithProviders(<LabelMasterManagePage />);
    await screen.findByText('사람');

    // then: 칩만 낭독되면 'person' 이 무엇인지 알 수 없다 — 축 이름이 함께 읽혀야 한다.
    const mapped = screen.getByTestId('label-detect-mapping-1');
    const unmapped = screen.getByTestId('label-detect-mapping-2');
    expect(mapped).toHaveTextContent(/AI 탐지/);
    expect(unmapped).toHaveTextContent(/AI 탐지/);

    // 그 축 이름은 시각 표기가 아니라 sr-only 로만 존재한다(칩이 장황해지지 않게).
    const srOnly = mapped.querySelector('.sr-only');
    expect(srOnly).not.toBeNull();
    expect(srOnly?.textContent).toMatch(/AI 탐지/);
  });

  it('매핑_표시는_별도_컬럼이_아니라_라벨명_셀_안에_있다', async () => {
    // given / when
    renderWithProviders(<LabelMasterManagePage />);
    await screen.findByText('사람');

    // then: 컬럼 구성은 사양대로 5개 그대로다(별도 컬럼 신설 금지 — 사양 위반 회귀 가드).
    const headers = screen.getAllByRole('columnheader');
    expect(headers.map((h) => h.textContent?.trim())).toEqual([
      '라벨명',
      '형태',
      '색상',
      '정렬순',
      '관리',
    ]);

    // 행의 셀 수도 늘지 않는다.
    const row = screen.getByTestId('label-master-row-1');
    expect(row.querySelectorAll('td')).toHaveLength(5);
  });
});
