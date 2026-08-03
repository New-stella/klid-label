// 2026-08-03 사용자 확정 — 도형 도구 클릭 시 뜨는 라벨 선택 모달.
//
// - 목록 출처는 **라벨 마스터 전체**(GET /manage/labels). 프리셋(/presets)은 오토라벨링 전용이라 쓰지 않는다.
// - 활성(useYn='Y')만, sortNo asc → labelId asc.
// - 이름 검색으로 필터.
// - 1~9 키로 순번 선택(폐지된 좌측 패널의 단축키를 이 모달로 이전).
// - 표시명은 공용 함수(resolveLabelDisplayName) — 마스터 등록명 그대로(사전 치환 없음).

import { fireEvent, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import MockAdapter from 'axios-mock-adapter';

import { apiClient } from '@/lib/api/client';
import { useLabelStore } from '@/stores/useLabelStore';
import { renderWithProviders } from '@/test/renderWithProviders';

import { LabelPickerModal } from '../LabelPickerModal';

const mastersPayload = {
  success: true,
  data: [
    { labelId: 11, name: '사람', color: '#EF4444', type: 'BBOX', sortNo: 1, useYn: 'Y', dtctTypeCd: 'person' },
    { labelId: 22, name: 'bus', color: '#3B82F6', type: 'BBOX', sortNo: 2, useYn: 'Y', dtctTypeCd: 'bus' },
    { labelId: 33, name: 'water', color: '#10B981', type: 'POLYGON', sortNo: 3, useYn: 'Y', dtctTypeCd: null },
    { labelId: 44, name: '비활성', color: '#111111', type: 'BBOX', sortNo: 4, useYn: 'N', dtctTypeCd: null },
  ],
  message: null,
  errorCode: null,
};

function renderPicker(props: Partial<React.ComponentProps<typeof LabelPickerModal>> = {}) {
  const onSelect = props.onSelect ?? vi.fn();
  const onCancel = props.onCancel ?? vi.fn();
  const utils = renderWithProviders(
    <LabelPickerModal open onSelect={onSelect} onCancel={onCancel} toolName="바운딩 박스" />,
  );
  return { ...utils, onSelect, onCancel };
}

describe('LabelPickerModal', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
    useLabelStore.getState().reset();
  });

  afterEach(() => {
    mock.restore();
  });

  it('활성_라벨_마스터_전체를_마스터_등록명_그대로_표시한다', async () => {
    mock.onGet('/manage/labels').reply(200, mastersPayload);
    renderPicker();

    // 등록명이 한글이면 한글, 영문이면 영문 — COCO 사전('bus'→'버스') 치환은 하지 않는다.
    await waitFor(() => expect(screen.getByRole('button', { name: /사람/ })).toBeInTheDocument());
    expect(screen.getByRole('button', { name: /bus/ })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /버스/ })).toBeNull();
    expect(screen.getByRole('button', { name: /water/ })).toBeInTheDocument();
    // 비활성(useYn='N') 라벨은 미노출
    expect(screen.queryByRole('button', { name: /비활성/ })).toBeNull();
  });

  it('프리셋_API를_호출하지_않는다', async () => {
    mock.onGet('/manage/labels').reply(200, mastersPayload);
    renderPicker();

    await waitFor(() => expect(screen.getByRole('button', { name: /사람/ })).toBeInTheDocument());
    const calledUrls = mock.history.get.map((r) => r.url ?? '');
    expect(calledUrls.some((u) => u.includes('preset'))).toBe(false);
  });

  it('이름_검색으로_필터된다', async () => {
    mock.onGet('/manage/labels').reply(200, mastersPayload);
    renderPicker();
    await waitFor(() => expect(screen.getByRole('button', { name: /사람/ })).toBeInTheDocument());

    const search = screen.getByLabelText('라벨 이름 검색');
    fireEvent.change(search, { target: { value: 'bus' } });

    expect(screen.getByRole('button', { name: /bus/ })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /사람/ })).toBeNull();
  });

  it('클릭하면_onSelect에_labelId를_넘긴다', async () => {
    mock.onGet('/manage/labels').reply(200, mastersPayload);
    const { onSelect } = renderPicker();
    await waitFor(() => expect(screen.getByRole('button', { name: /bus/ })).toBeInTheDocument());

    fireEvent.click(screen.getByRole('button', { name: /bus/ }));
    expect(onSelect).toHaveBeenCalledWith(22);
  });

  it('현재_선택_라벨은_aria_pressed_true', async () => {
    mock.onGet('/manage/labels').reply(200, mastersPayload);
    useLabelStore.getState().setActiveLabelId(22);
    renderPicker();

    await waitFor(() => expect(screen.getByRole('button', { name: /bus/ })).toBeInTheDocument());
    expect(screen.getByRole('button', { name: /bus/ }).getAttribute('aria-pressed')).toBe('true');
    expect(screen.getByRole('button', { name: /사람/ }).getAttribute('aria-pressed')).toBe('false');
  });

  it('숫자키_1~9로_순번_라벨을_선택한다', async () => {
    mock.onGet('/manage/labels').reply(200, mastersPayload);
    const { onSelect } = renderPicker();
    await waitFor(() => expect(screen.getByRole('button', { name: /사람/ })).toBeInTheDocument());

    fireEvent.keyDown(window, { key: '2' });
    expect(onSelect).toHaveBeenCalledWith(22);
  });

  it('범위_밖_숫자키는_무시한다', async () => {
    mock.onGet('/manage/labels').reply(200, mastersPayload);
    const { onSelect } = renderPicker();
    await waitFor(() => expect(screen.getByRole('button', { name: /사람/ })).toBeInTheDocument());

    fireEvent.keyDown(window, { key: '9' });
    expect(onSelect).not.toHaveBeenCalled();
  });

  it('검색창_입력_중_숫자키는_선택으로_동작하지_않는다', async () => {
    mock.onGet('/manage/labels').reply(200, mastersPayload);
    const { onSelect } = renderPicker();
    await waitFor(() => expect(screen.getByRole('button', { name: /사람/ })).toBeInTheDocument());

    const search = screen.getByLabelText('라벨 이름 검색');
    fireEvent.keyDown(search, { key: '1' });
    expect(onSelect).not.toHaveBeenCalled();
  });

  it('검증되지_않은_색상은_인라인_스타일에_그대로_흘리지_않는다', async () => {
    mock.onGet('/manage/labels').reply(200, {
      ...mastersPayload,
      data: [
        {
          labelId: 99,
          name: '사람',
          color: 'red; background-image:url(javascript:alert(1))',
          type: 'BBOX',
          sortNo: 1,
          useYn: 'Y',
          dtctTypeCd: null,
        },
      ],
    });
    renderPicker();
    await waitFor(() => expect(screen.getByRole('button', { name: /사람/ })).toBeInTheDocument());

    const chip = screen.getByTestId('label-picker-color') as HTMLElement;
    // #RRGGBB 가 아니면 fallback 회색으로 정규화된다.
    expect(chip.style.backgroundColor).toBe('rgb(148, 163, 184)');
  });

  it('빈_목록이면_안내_문구를_보여준다', async () => {
    mock.onGet('/manage/labels').reply(200, { success: true, data: [], message: null, errorCode: null });
    renderPicker();
    await waitFor(() => expect(screen.getByText(/등록된 라벨이 없습니다/)).toBeInTheDocument());
  });

  it('조회_실패시_에러를_알린다', async () => {
    mock.onGet('/manage/labels').reply(500, {
      success: false,
      data: null,
      message: '서버 오류',
      errorCode: 'INTERNAL',
    });
    renderPicker();
    await waitFor(() => expect(screen.getByRole('alert')).toHaveTextContent(/라벨 조회 실패/));
  });
});
