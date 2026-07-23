// event_annotation evidence 캔버스 선택 객체 자동연결 — 통합 검증(RED→GREEN).
//
// 캔버스 선택 라벨(useLabelStore) 1건을 evidence 행 obj_* 필드에 append 하는 동작.
// - obj_id(trackId/serverId/id) · obj_label(className) · obj_bbox(정수 x1,y1,x2,y2) · frame_id(currentSrcSn)
// - 기존 값 있으면 콤마(단일값)/개행(bbox) append
// - 선택 없으면 no-op(버튼 disabled)
// - MASK 는 obj_bbox 건너뜀
// - 기존 '현재 프레임' 버튼 회귀 없음

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import MockAdapter from 'axios-mock-adapter';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { apiClient } from '@/lib/api/client';
import { EventAnnotationPanel } from '@/features/label/components/EventAnnotationPanel';
import { useAuthStore } from '@/stores/useAuthStore';
import { useLabelStore } from '@/stores/useLabelStore';
import type { Label } from '@/features/label/types';
import { renderWithProviders } from '@/test/renderWithProviders';

function emptyGet(rawSn: number) {
  return {
    success: true,
    data: {
      rawSn,
      evntAnnoSn: null,
      reviewStatus: null,
      regId: null,
      mdfcnId: null,
      payload: { event_class: '' },
    },
    message: null,
    errorCode: null,
  };
}

function makeLabel(overrides: Partial<Label>): Label {
  return {
    id: 'lbl-1',
    frameNo: 0,
    classId: 1,
    className: 'person',
    source: 'MANUAL',
    shape: { type: 'BBOX', left: 10, top: 20, right: 30, bottom: 40 },
    ...overrides,
  };
}

function selectLabel(label: Label) {
  useLabelStore.setState({ labels: [label], selectedLabelId: label.id });
}

describe('EventAnnotationPanel — 선택 객체 자동연결', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
    useAuthStore.setState({ token: null, claims: null });
    useLabelStore.setState({ labels: [], selectedLabelId: null });
  });

  afterEach(() => {
    mock.restore();
    vi.restoreAllMocks();
    useAuthStore.setState({ token: null, claims: null });
    useLabelStore.setState({ labels: [], selectedLabelId: null });
  });

  it('선택객체추가_클릭시_objId_objLabel_objBbox_frameId_자동채움', async () => {
    const user = userEvent.setup();
    mock.onGet('/videos/5/event-annotation').reply(200, emptyGet(5));
    selectLabel(
      makeLabel({
        id: 'lbl-1',
        serverId: 77,
        trackId: '9',
        className: 'car',
        shape: { type: 'BBOX', left: 10, top: 20, right: 30, bottom: 40 },
      }),
    );

    renderWithProviders(<EventAnnotationPanel rawSn={5} currentSrcSn={321} />);
    await waitFor(() => expect(screen.getByTestId('ea-event-class')).toBeInTheDocument());
    await user.click(screen.getByTestId('ea-add-evidence'));
    await user.click(screen.getByTestId('ea-evidence-add-selected-c1'));

    // trackId 우선
    expect(screen.getByTestId('ea-evidence-objid-c1')).toHaveValue('9');
    expect(screen.getByTestId('ea-evidence-objlabel-c1')).toHaveValue('car');
    expect(screen.getByTestId('ea-evidence-objbbox-c1')).toHaveValue('10,20,30,40');
    expect(screen.getByTestId('ea-evidence-frameid-c1')).toHaveValue('321');
  });

  it('trackId_없으면_serverId_사용', async () => {
    const user = userEvent.setup();
    mock.onGet('/videos/5/event-annotation').reply(200, emptyGet(5));
    selectLabel(makeLabel({ id: 'lbl-x', serverId: 42, trackId: null, className: 'dog' }));

    renderWithProviders(<EventAnnotationPanel rawSn={5} currentSrcSn={100} />);
    await waitFor(() => expect(screen.getByTestId('ea-event-class')).toBeInTheDocument());
    await user.click(screen.getByTestId('ea-add-evidence'));
    await user.click(screen.getByTestId('ea-evidence-add-selected-c1'));

    expect(screen.getByTestId('ea-evidence-objid-c1')).toHaveValue('42');
  });

  it('기존값_있으면_콤마_또는_개행_append', async () => {
    const user = userEvent.setup();
    mock.onGet('/videos/5/event-annotation').reply(200, emptyGet(5));
    selectLabel(
      makeLabel({
        id: 'a',
        trackId: '3',
        className: 'person',
        shape: { type: 'BBOX', left: 1, top: 2, right: 3, bottom: 4 },
      }),
    );

    renderWithProviders(<EventAnnotationPanel rawSn={5} currentSrcSn={9} />);
    await waitFor(() => expect(screen.getByTestId('ea-event-class')).toBeInTheDocument());
    await user.click(screen.getByTestId('ea-add-evidence'));

    // 기존 수동 입력값 존재
    await user.type(screen.getByTestId('ea-evidence-objid-c1'), 'old');
    await user.type(screen.getByTestId('ea-evidence-objlabel-c1'), 'oldlbl');
    await user.type(screen.getByTestId('ea-evidence-objbbox-c1'), '5,6,7,8');
    await user.click(screen.getByTestId('ea-evidence-add-selected-c1'));

    expect(screen.getByTestId('ea-evidence-objid-c1')).toHaveValue('old, 3');
    expect(screen.getByTestId('ea-evidence-objlabel-c1')).toHaveValue('oldlbl, person');
    expect(screen.getByTestId('ea-evidence-objbbox-c1')).toHaveValue('5,6,7,8\n1,2,3,4');
  });

  it('선택객체_없으면_추가_동작안함', async () => {
    const user = userEvent.setup();
    mock.onGet('/videos/5/event-annotation').reply(200, emptyGet(5));
    // 선택 없음
    useLabelStore.setState({ labels: [], selectedLabelId: null });

    renderWithProviders(<EventAnnotationPanel rawSn={5} currentSrcSn={9} />);
    await waitFor(() => expect(screen.getByTestId('ea-event-class')).toBeInTheDocument());
    await user.click(screen.getByTestId('ea-add-evidence'));

    const btn = screen.getByTestId('ea-evidence-add-selected-c1');
    expect(btn).toBeDisabled();
    await user.click(btn); // disabled → no-op
    expect(screen.getByTestId('ea-evidence-objid-c1')).toHaveValue('');
  });

  it('MASK_선택시_objBbox는_건드리지않고_objId_objLabel만', async () => {
    const user = userEvent.setup();
    mock.onGet('/videos/5/event-annotation').reply(200, emptyGet(5));
    selectLabel(
      makeLabel({
        id: 'm1',
        trackId: '7',
        className: 'blob',
        shape: { type: 'MASK', rle: 'xxx' },
      }),
    );

    renderWithProviders(<EventAnnotationPanel rawSn={5} currentSrcSn={55} />);
    await waitFor(() => expect(screen.getByTestId('ea-event-class')).toBeInTheDocument());
    await user.click(screen.getByTestId('ea-add-evidence'));
    await user.click(screen.getByTestId('ea-evidence-add-selected-c1'));

    expect(screen.getByTestId('ea-evidence-objid-c1')).toHaveValue('7');
    expect(screen.getByTestId('ea-evidence-objlabel-c1')).toHaveValue('blob');
    expect(screen.getByTestId('ea-evidence-objbbox-c1')).toHaveValue('');
  });

  it('실수좌표_선택시_정수로_반올림되어_채워짐', async () => {
    const user = userEvent.setup();
    mock.onGet('/videos/5/event-annotation').reply(200, emptyGet(5));
    selectLabel(
      makeLabel({
        id: 'r1',
        trackId: '1',
        className: 'x',
        shape: { type: 'BBOX', left: 10.4, top: 20.6, right: 30.5, bottom: 40.49 },
      }),
    );

    renderWithProviders(<EventAnnotationPanel rawSn={5} currentSrcSn={1} />);
    await waitFor(() => expect(screen.getByTestId('ea-event-class')).toBeInTheDocument());
    await user.click(screen.getByTestId('ea-add-evidence'));
    await user.click(screen.getByTestId('ea-evidence-add-selected-c1'));

    // Math.round: 10.4→10, 20.6→21, 30.5→31, 40.49→40
    expect(screen.getByTestId('ea-evidence-objbbox-c1')).toHaveValue('10,21,31,40');
  });

  it('현재프레임_버튼_기존동작_회귀없음', async () => {
    const user = userEvent.setup();
    mock.onGet('/videos/5/event-annotation').reply(200, emptyGet(5));

    renderWithProviders(<EventAnnotationPanel rawSn={5} currentSrcSn={200} />);
    await waitFor(() => expect(screen.getByTestId('ea-event-class')).toBeInTheDocument());
    await user.click(screen.getByTestId('ea-add-evidence'));

    // 기존 '현재 프레임' 버튼 — frame_id 에 currentSrcSn append
    await user.click(screen.getByTestId('ea-evidence-frameid-current-c1'));
    expect(screen.getByTestId('ea-evidence-frameid-c1')).toHaveValue('200');
  });

  // --- 중복 방지(dedup) — 버그 C ---

  it('같은객체_2회추가시_objId_objLabel_objBbox_중복없음', async () => {
    const user = userEvent.setup();
    mock.onGet('/videos/5/event-annotation').reply(200, emptyGet(5));
    selectLabel(
      makeLabel({
        id: 'lbl-1',
        trackId: '5',
        className: 'car',
        shape: { type: 'BBOX', left: 10, top: 20, right: 30, bottom: 40 },
      }),
    );

    renderWithProviders(<EventAnnotationPanel rawSn={5} currentSrcSn={100} />);
    await waitFor(() => expect(screen.getByTestId('ea-event-class')).toBeInTheDocument());
    await user.click(screen.getByTestId('ea-add-evidence'));

    // 같은 객체를 2회 추가 → 한 세트만 유지
    await user.click(screen.getByTestId('ea-evidence-add-selected-c1'));
    await user.click(screen.getByTestId('ea-evidence-add-selected-c1'));

    expect(screen.getByTestId('ea-evidence-objid-c1')).toHaveValue('5');
    expect(screen.getByTestId('ea-evidence-objlabel-c1')).toHaveValue('car');
    expect(screen.getByTestId('ea-evidence-objbbox-c1')).toHaveValue('10,20,30,40');
    expect(screen.getByTestId('ea-evidence-frameid-c1')).toHaveValue('100');
  });

  it('동일프레임_2객체_추가시_objId_2개_frameId_1개', async () => {
    const user = userEvent.setup();
    mock.onGet('/videos/5/event-annotation').reply(200, emptyGet(5));

    // 첫 객체 선택
    selectLabel(makeLabel({ id: 'a', trackId: '5', className: 'car' }));

    renderWithProviders(<EventAnnotationPanel rawSn={5} currentSrcSn={100} />);
    await waitFor(() => expect(screen.getByTestId('ea-event-class')).toBeInTheDocument());
    await user.click(screen.getByTestId('ea-add-evidence'));
    await user.click(screen.getByTestId('ea-evidence-add-selected-c1'));

    // 서로 다른 객체(같은 프레임)로 교체 후 추가
    selectLabel(makeLabel({ id: 'b', trackId: '8', className: 'person' }));
    await user.click(screen.getByTestId('ea-evidence-add-selected-c1'));

    // obj_id 는 2개 누적, frame_id(동일 프레임)는 1개만
    expect(screen.getByTestId('ea-evidence-objid-c1')).toHaveValue('5, 8');
    expect(screen.getByTestId('ea-evidence-objlabel-c1')).toHaveValue('car, person');
    expect(screen.getByTestId('ea-evidence-frameid-c1')).toHaveValue('100');
  });

  it('다른객체_추가는_정상_누적', async () => {
    const user = userEvent.setup();
    mock.onGet('/videos/5/event-annotation').reply(200, emptyGet(5));
    selectLabel(
      makeLabel({
        id: 'a',
        trackId: '5',
        className: 'car',
        shape: { type: 'BBOX', left: 1, top: 2, right: 3, bottom: 4 },
      }),
    );

    renderWithProviders(<EventAnnotationPanel rawSn={5} currentSrcSn={100} />);
    await waitFor(() => expect(screen.getByTestId('ea-event-class')).toBeInTheDocument());
    await user.click(screen.getByTestId('ea-add-evidence'));
    await user.click(screen.getByTestId('ea-evidence-add-selected-c1'));

    // 다른 객체(다른 프레임)로 교체 후 추가 → 전부 누적
    selectLabel(
      makeLabel({
        id: 'b',
        trackId: '8',
        className: 'person',
        shape: { type: 'BBOX', left: 5, top: 6, right: 7, bottom: 8 },
      }),
    );
    await user.click(screen.getByTestId('ea-evidence-add-selected-c1'));

    expect(screen.getByTestId('ea-evidence-objid-c1')).toHaveValue('5, 8');
    expect(screen.getByTestId('ea-evidence-objlabel-c1')).toHaveValue('car, person');
    expect(screen.getByTestId('ea-evidence-objbbox-c1')).toHaveValue('1,2,3,4\n5,6,7,8');
  });

  it('현재프레임_버튼_2회클릭시_frameId_중복없음', async () => {
    const user = userEvent.setup();
    mock.onGet('/videos/5/event-annotation').reply(200, emptyGet(5));

    renderWithProviders(<EventAnnotationPanel rawSn={5} currentSrcSn={200} />);
    await waitFor(() => expect(screen.getByTestId('ea-event-class')).toBeInTheDocument());
    await user.click(screen.getByTestId('ea-add-evidence'));

    await user.click(screen.getByTestId('ea-evidence-frameid-current-c1'));
    await user.click(screen.getByTestId('ea-evidence-frameid-current-c1'));
    expect(screen.getByTestId('ea-evidence-frameid-c1')).toHaveValue('200');
  });
});
