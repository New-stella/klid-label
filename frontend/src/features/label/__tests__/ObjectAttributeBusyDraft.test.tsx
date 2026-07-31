// Phase 3 §이월 P-2 — 차단 구간의 속성값 커밋이 **입력 중이던 텍스트를 폐기하면 안 된다**.
//
// Phase 2 시점엔 도달 불가였다(입력 disabled + 포커스 시 단축키 무시 + 마우스 blur 커밋이 busy 보다
// 선행). Phase 3 의 취소 버튼은 **포커스를 뺏지 않는 busy 트리거**라 이 경로가 실제로 열린다:
// 사용자가 속성값을 타이핑하는 도중 다른 작업(저장/AI)이 시작되면 blur 커밋이 차단되는데,
// 그때 draft 를 서버값으로 되돌리면 사용자가 적던 값이 사라진다(R9/AC10 위반).
//
// 정답은 "되돌리기"가 아니라 **draft 보존 + 미저장 표식**이다.

import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { act, fireEvent, screen, waitFor } from '@testing-library/react';
import MockAdapter from 'axios-mock-adapter';

import { apiClient } from '@/lib/api/client';
import { renderWithProviders } from '@/test/renderWithProviders';
import { useLabelStore } from '@/stores/useLabelStore';
import { useUiStore } from '@/stores/useUiStore';

import { ObjectAttributeSection } from '../components/ObjectAttributeSection';

const ok = (data: unknown) => ({ success: true, data, message: null, errorCode: null });

const CLASS_ID = 3;
const LBL_SN = 7001;
const SRC_SN = 900;

const textAttr = {
  attrId: 12,
  labelId: CLASS_ID,
  name: '메모',
  inputType: 'TEXT',
  valuesJson: null,
  defaultVal: null,
  mutable: 'Y',
  sortNo: 1,
  useYn: 'Y',
};

describe('ObjectAttributeSection — busy 중 속성 draft 보존(P-2)', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
    mock.onGet(`/manage/labels/${CLASS_ID}/attrs`).reply(200, ok([textAttr]));
    mock.onGet(`/labels/${LBL_SN}/attrs`).reply(200, ok([]));
    mock.onPut(`/labels/${LBL_SN}/attrs`).reply(200, ok(null));
    useLabelStore.getState().reset();
    useUiStore.setState({ toasts: [] });
  });

  afterEach(() => {
    mock.restore();
    useLabelStore.getState().reset();
    useUiStore.setState({ toasts: [] });
  });

  it('busy_중_속성값_입력_draft는_폐기되지_않고_보존된다', async () => {
    // given: 사용자가 속성값을 타이핑 중(아직 커밋 전). 렌더 값은 아직 차단이 아니다.
    renderWithProviders(<ObjectAttributeSection classId={CLASS_ID} serverId={LBL_SN} />);
    const memo = (await screen.findByLabelText('메모')) as HTMLInputElement;
    fireEvent.change(memo, { target: { value: '보행자 그룹' } });
    expect(memo.value).toBe('보행자 그룹');

    // when: 타이핑 도중 다른 작업이 시작되고(포커스는 그대로) blur 커밋이 발생한다.
    act(() => {
      useLabelStore.getState().beginBusy('SAVE', { srcSn: SRC_SN });
    });
    fireEvent.blur(memo, { target: { value: '보행자 그룹' } });

    // then: 서버 쓰기는 나가지 않지만 **사용자가 적던 값은 그대로 남는다**.
    expect(mock.history.put.filter((r) => r.url === `/labels/${LBL_SN}/attrs`)).toHaveLength(0);
    expect((screen.getByLabelText('메모') as HTMLInputElement).value).toBe('보행자 그룹');
    // 저장되지 않았다는 사실을 화면에 남긴다(서버값으로 오인 방지).
    expect(screen.getByTestId(`attr-unsaved-${textAttr.attrId}`)).toBeInTheDocument();
    const toasts = useUiStore.getState().toasts;
    expect(toasts).toHaveLength(1);
    expect(toasts[0].message).toContain('진행 중');
  });

  it('작업이_끝나면_보존된_draft를_그대로_다시_저장할_수_있다', async () => {
    renderWithProviders(<ObjectAttributeSection classId={CLASS_ID} serverId={LBL_SN} />);
    const memo = (await screen.findByLabelText('메모')) as HTMLInputElement;
    fireEvent.change(memo, { target: { value: '보행자 그룹' } });
    act(() => {
      useLabelStore.getState().beginBusy('SAVE', { srcSn: SRC_SN });
    });
    fireEvent.blur(memo, { target: { value: '보행자 그룹' } });

    act(() => {
      useLabelStore.getState().cancelBusy();
    });
    fireEvent.blur(screen.getByLabelText('메모'), { target: { value: '보행자 그룹' } });

    await waitFor(() => {
      const puts = mock.history.put.filter((r) => r.url === `/labels/${LBL_SN}/attrs`);
      expect(puts).toHaveLength(1);
      const body = JSON.parse(puts[0].data) as { values: { attrId: number; value: string }[] };
      expect(body.values).toEqual([{ attrId: textAttr.attrId, value: '보행자 그룹' }]);
    });
    // 저장됐으므로 미저장 표식은 사라진다.
    await waitFor(() =>
      expect(screen.queryByTestId(`attr-unsaved-${textAttr.attrId}`)).not.toBeInTheDocument(),
    );
  });
});
