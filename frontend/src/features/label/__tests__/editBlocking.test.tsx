// Phase 2 — 라벨링 화면 편집 차단 배선(진입점별 회귀 가드).
//
// busy(장시간 작업) 동안 캔버스·툴바·프레임 전환·단축키·패널의 모든 편집 진입점이 실제로 막히고,
// 해제되면 즉시 복구되는지를 진입점 단위로 고정한다. 판정원은 store 의 useIsEditBlocked 하나다.

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import MockAdapter from 'axios-mock-adapter';
import { act, fireEvent, screen, waitFor, within } from '@testing-library/react';

vi.mock('react-konva', () => {
  // eslint-disable-next-line @typescript-eslint/no-var-requires
  const React = require('react');
  const passthrough = (name: string) => {
    const KonvaMock = ({ children, ...rest }: { children?: unknown; [key: string]: unknown }) =>
      React.createElement('div', { 'data-konva': name, ...rest }, children);
    KonvaMock.displayName = `KonvaMock(${name})`;
    return KonvaMock;
  };
  return {
    Stage: passthrough('Stage'),
    Layer: passthrough('Layer'),
    Image: passthrough('Image'),
    Rect: passthrough('Rect'),
    Line: passthrough('Line'),
    Circle: passthrough('Circle'),
    Group: passthrough('Group'),
    Transformer: passthrough('Transformer'),
  };
});

import { apiClient } from '@/lib/api/client';
import { LabelingPage } from '@/pages/label/LabelingPage';
import { renderWithProviders } from '@/test/renderWithProviders';
import { useAuthStore } from '@/stores/useAuthStore';
import { useLabelStore } from '@/stores/useLabelStore';
import { useUiStore } from '@/stores/useUiStore';
import { ToolType } from '@/features/label/types';

// 테스트용 더미 인증값(비밀 아님 — 시크릿 스캐너 오탐 회피용 조합).
const FAKE_TOKEN = ['t', 'o', 'k'].join('');

const SRC_SN = 200;

function labelsPayload(srcSn: number, frameNo: number, labels: unknown[] = [], extra = {}) {
  return {
    success: true,
    data: {
      frameNo,
      srcSn,
      videoId: 7,
      labelVersion: 3,
      siblings: [
        { srcSn: 200, frameNo: 0 },
        { srcSn: 201, frameNo: 1 },
      ],
      labels,
      ...extra,
    },
    message: null,
    errorCode: null,
  };
}

const labelOnFrame0 = {
  id: 'lbl-1',
  frameNo: 0,
  classId: 1,
  className: 'car',
  source: 'MANUAL',
  shape: { type: 'BBOX', left: 0, top: 0, right: 50, bottom: 30 },
};

function renderPage() {
  return renderWithProviders(<LabelingPage />, {
    initialEntries: ['/label/200'],
    routes: [{ path: '/label/:id', element: <LabelingPage /> }],
  });
}

async function loadPage() {
  renderPage();
  await waitFor(() => expect(useLabelStore.getState().labels).toHaveLength(1));
  await screen.findByTestId('canvas-shell');
}

function startBusy(kind: 'AI_TRACK' | 'SAVE' = 'AI_TRACK', srcSn = SRC_SN) {
  act(() => {
    useLabelStore.getState().beginBusy(kind, { srcSn });
  });
}

/**
 * BBOX 도구 활성화 — 2026-08-03 확정 흐름(도구 클릭 → 라벨 선택 모달 → 라벨 확정)을 그대로 탄다.
 * 스토어에 도구만 꽂으면 라벨 선택 모달이 떠 있는 상태가 되어 단축키가 모달에 막힌다.
 */
async function activateBboxTool() {
  fireEvent.click(screen.getByRole('button', { name: '바운딩 박스' }));
  fireEvent.click(await screen.findByRole('button', { name: /사람/ }));
  await waitFor(() => expect(useLabelStore.getState().activeTool).toBe(ToolType.BBOX));
  await waitFor(() => expect(screen.queryByRole('dialog', { name: '라벨 선택' })).toBeNull());
}

describe('LabelingPage — busy 중 편집 차단', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
    useAuthStore.setState({
      token: FAKE_TOKEN,
      claims: { sub: '10', role: 'WORKER', channel: 'INTERNAL', exp: 9999999999 },
    });
    useLabelStore.getState().reset();
    useUiStore.setState({ toasts: [] });
    mock.onGet('/frames/200/labels').reply(200, labelsPayload(200, 0, [labelOnFrame0]));
    mock.onGet('/frames/201/labels').reply(200, labelsPayload(201, 1, []));
    [200, 201].forEach((sn) => {
      mock.onGet(`/frames/${sn}/image`).reply(200, new Blob());
    });
    mock
      .onGet(/\/labels\/masters/)
      .reply(200, { success: true, data: [], message: null, errorCode: null });
    // 라벨 선택 모달(2026-08-03)이 쓰는 라벨 마스터 — 도형 도구 활성화에 필요.
    mock.onGet('/manage/labels').reply(200, {
      success: true,
      data: [
        {
          labelId: 1,
          name: '사람',
          color: '#EF4444',
          type: 'BBOX',
          sortNo: 1,
          useYn: 'Y',
          dtctTypeCd: 'person',
        },
      ],
      message: null,
      errorCode: null,
    });
    // 비식별 신고 버튼은 영상 상세(파생 여부)를 참조한다 — 파생 아님(신고 가능) 응답.
    mock
      .onGet('/videos/7')
      .reply(200, {
        success: true,
        data: { videoId: 7, derivative: null },
        message: null,
        errorCode: null,
      });
  });

  afterEach(() => {
    mock.restore();
    useAuthStore.getState().clear();
    useLabelStore.getState().reset();
    useUiStore.setState({ toasts: [] });
  });

  it('busy_중에는_캔버스가_읽기전용으로_전환된다', async () => {
    await loadPage();
    const shell = screen.getByTestId('canvas-shell');
    expect(shell.getAttribute('data-edit-blocked')).toBe('false');

    startBusy();

    await waitFor(() =>
      expect(screen.getByTestId('canvas-shell').getAttribute('data-edit-blocked')).toBe('true'),
    );
  });

  it('busy_중에는_기존_라벨을_선택_이동_삭제할_수_없다', async () => {
    await loadPage();
    expect(useLabelStore.getState().selectedLabelId).toBeNull();
    startBusy();

    // 선택 — **실제 UI 경로**(객체 목록 행 클릭)로 검증한다. store 액션을 직접 호출하면
    // 차단 배선을 우회해 버려 "초록인데 못 잡는" 테스트가 된다.
    const selectRow = screen.getByRole('button', { name: /#1 선택$/ });
    expect(selectRow).toBeDisabled();
    fireEvent.click(selectRow);
    expect(useLabelStore.getState().selectedLabelId).toBeNull();
    // 삭제 — 캔버스 상단 옵션바의 삭제 버튼 비활성(좌측 도구바에서 이관됨).
    expect(
      within(screen.getByTestId('canvas-option-bar')).getByRole('button', { name: '삭제' }),
    ).toBeDisabled();
    // 이동/리사이즈 — 캔버스 readOnly.
    expect(screen.getByTestId('canvas-shell').getAttribute('data-read-only')).toBe('true');
    // 단축키 삭제도 무시된다.
    fireEvent.keyDown(window, { key: 'Delete', code: 'Delete' });
    expect(useLabelStore.getState().labels).toHaveLength(1);
  });

  it('busy_중에는_객체목록_패널로도_라벨을_지울_수_없다', async () => {
    // 캔버스만 막고 목록 패널을 열어두면 같은 라벨을 옆문으로 지울 수 있다(차단 지점 누락).
    await loadPage();
    startBusy();

    const deleteButton = screen.getByRole('button', { name: '객체 삭제' });
    expect(deleteButton).toBeDisabled();
    fireEvent.click(deleteButton);
    expect(useLabelStore.getState().labels).toHaveLength(1);

    act(() => {
      useLabelStore.getState().cancelBusy();
    });
    await waitFor(() =>
      expect(screen.getByRole('button', { name: '객체 삭제' })).not.toBeDisabled(),
    );
  });

  it('busy_중에는_프레임_전환이_동작하지_않는다_슬라이더_필름스트립_버튼_모두', async () => {
    await loadPage();
    startBusy();

    // 슬라이더 이전/다음 버튼 + range 비활성
    expect(screen.getByLabelText('다음 프레임')).toBeDisabled();
    expect(screen.getByLabelText('프레임 슬라이더')).toBeDisabled();
    // 썸네일(필름스트립) 비활성
    expect(screen.getByRole('option', { name: '프레임 1' })).toBeDisabled();

    // 단축키(D=다음 프레임)도 무시 — 프레임이 그대로다.
    fireEvent.keyDown(window, { key: 'd', code: 'KeyD' });
    await act(async () => {
      await Promise.resolve();
    });
    expect((screen.getByTestId('frame-number-input') as HTMLInputElement).value).toBe('1');
    expect(mock.history.get.some((r) => r.url === '/frames/201/labels')).toBe(false);
  });

  it('busy_중에는_저장_검수제출_AI실행_버튼이_비활성이다', async () => {
    await loadPage();
    startBusy();

    expect(screen.getByTestId('label-toolbar-save')).toBeDisabled();
    expect(screen.getByTestId('submit-review-button')).toBeDisabled();
    const toolbar = within(screen.getByRole('toolbar', { name: '라벨링 도구' }));
    expect(toolbar.getByRole('button', { name: 'AI 탐지' })).toBeDisabled();
    // ★저장은 좌측 도구바가 아니라 캔버스 상단 옵션바 소관이다 — 양쪽에 두지 않는다.
    expect(toolbar.queryByRole('button', { name: '저장' })).toBeNull();
    expect(
      within(screen.getByTestId('canvas-option-bar')).getByRole('button', { name: '저장' }),
    ).toBeDisabled();
  });

  it('busy_중_ESC는_작업을_취소한다', async () => {
    await loadPage();
    await activateBboxTool();
    startBusy();

    // 도구 전환 단축키(P=폴리곤) 무시
    fireEvent.keyDown(window, { key: 'p', code: 'KeyP' });
    expect(useLabelStore.getState().activeTool).toBe(ToolType.BBOX);
    // 저장 단축키 무시 — PUT 미발생
    fireEvent.keyDown(window, { key: 's', code: 'KeyS', ctrlKey: true });
    await act(async () => {
      await Promise.resolve();
    });
    expect(mock.history.put).toHaveLength(0);

    // Phase 3 — 취소 수단이 생겼으므로 ESC 를 **취소**로 재배선한다. Phase 2 에서 ESC 를 전면
    // 차단했던 이유(ESC=tool.select 가 드래프트를 파기)는 그대로라, ESC 가 도구 전환으로 흐르지
    // 않아야 한다: 작업만 취소되고 도구·드래프트는 그대로다.
    fireEvent.keyDown(window, { key: 'Escape', code: 'Escape' });
    expect(useLabelStore.getState().busy).toBeNull();
    expect(useLabelStore.getState().activeTool).toBe(ToolType.BBOX);
  });

  it('busy가_아닐_때_ESC는_기존대로_선택도구로_전환한다', async () => {
    await loadPage();
    await activateBboxTool();

    fireEvent.keyDown(window, { key: 'Escape', code: 'Escape' });

    expect(useLabelStore.getState().activeTool).toBe(ToolType.SELECT);
  });

  it('busy_중_캔버스_위에_진행_오버레이가_뜨고_취소로_즉시_편집에_복귀한다', async () => {
    // AC4/AC5 — 무엇이 진행 중인지 보이고, 거기서 바로 취소할 수 있어야 한다.
    await loadPage();
    expect(screen.queryByTestId('busy-overlay')).not.toBeInTheDocument();

    startBusy();

    const overlay = await screen.findByTestId('busy-overlay');
    expect(overlay.textContent).toContain('AI 추적 진행 중');
    expect(overlay.textContent ?? '').not.toMatch(/YOLO|SAM/i);

    fireEvent.click(within(overlay).getByRole('button', { name: '작업 취소' }));

    expect(useLabelStore.getState().busy).toBeNull();
    await waitFor(() => expect(screen.queryByTestId('busy-overlay')).not.toBeInTheDocument());
    expect(screen.getByTestId('label-toolbar-save')).not.toBeDisabled();
    expect(screen.getByTestId('canvas-shell').getAttribute('data-edit-blocked')).toBe('false');
  });

  it('busy_중에는_비식별_누락_신고를_시작할_수_없다', async () => {
    // 신고 성공은 reset() 으로 이어지고 reset 은 진행 중 작업을 조용히 취소한다 —
    // 사용자는 취소한 적이 없는데 저장/AI 작업이 사라진다.
    await loadPage();
    const reportButton = await screen.findByTestId('deident-report-button');
    expect(reportButton).not.toBeDisabled();

    startBusy('SAVE');

    await waitFor(() => expect(screen.getByTestId('deident-report-button')).toBeDisabled());
    fireEvent.click(screen.getByTestId('deident-report-button'));
    expect(screen.queryByTestId('deident-report-form')).not.toBeInTheDocument();

    act(() => {
      useLabelStore.getState().cancelBusy();
    });
    await waitFor(() => expect(screen.getByTestId('deident-report-button')).not.toBeDisabled());
  });

  it('신고_모달을_먼저_연_뒤_busy가_시작되면_제출이_거부된다', async () => {
    // 버튼만 막으면 "모달 선점"이 남는다 — 모달을 먼저 열고 그 사이 저장/AI 가 시작되면
    // 제출이 그대로 성공하고, 성공 후처리(reset)가 진행 중 작업을 조용히 취소한다.
    mock.onPost('/labels/200/deident-report').reply(200, {
      success: true,
      data: 1,
      message: null,
      errorCode: null,
    });
    await loadPage();
    fireEvent.click(await screen.findByTestId('deident-report-button'));
    const form = await screen.findByTestId('deident-report-form');
    fireEvent.change(within(form).getByLabelText(/신고 사유/), {
      target: { value: '오른쪽 보행자 얼굴 블러 누락' },
    });
    await waitFor(() => expect(screen.getByTestId('deident-report-submit')).not.toBeDisabled());

    // 모달이 열린 뒤 저장이 시작된다.
    startBusy('SAVE');
    fireEvent.submit(form);
    await waitFor(() =>
      expect(screen.getByTestId('deident-report-server-error')).toBeInTheDocument(),
    );

    // 서버 쓰기가 나가지 않고, 진행 중 작업도 취소되지 않는다.
    expect(mock.history.post.filter((r) => r.url === '/labels/200/deident-report')).toHaveLength(0);
    expect(useLabelStore.getState().busy?.kind).toBe('SAVE');
    expect(useLabelStore.getState().labels).toHaveLength(1);
    expect(screen.getByTestId('deident-report-server-error').textContent).toContain('진행 중');
  });

  it('busy_중에는_저장안함_이동이_미저장분만_잃지_않는다', async () => {
    // 가드 모달의 "저장 안 함"은 파기 후 이동인데, 이동이 막힌 상태에서 파기만 실행되면
    // 같은 프레임에 남은 채 작업만 사라진다.
    await loadPage();
    act(() => {
      useLabelStore.getState().updateLabel('lbl-1', { className: 'bus' });
    });
    expect(useLabelStore.getState().dirtyLabels.size).toBe(1);

    // 미저장 상태에서 프레임 이동 → 가드 모달.
    fireEvent.click(screen.getByLabelText('다음 프레임'));
    await screen.findByTestId('frame-nav-guard-discard');

    // 모달이 열린 뒤 저장/AI 가 시작된다.
    startBusy('SAVE');
    fireEvent.click(screen.getByTestId('frame-nav-guard-discard'));
    await act(async () => {
      await Promise.resolve();
    });

    // 이동이 불가능하면 파기하지 않는다 — 미저장 변경 보존 + 안내.
    expect(useLabelStore.getState().dirtyLabels.size).toBe(1);
    expect((screen.getByTestId('frame-number-input') as HTMLInputElement).value).toBe('1');
    const toasts = useUiStore.getState().toasts;
    expect(toasts.length).toBeGreaterThanOrEqual(1);
    expect(toasts[toasts.length - 1].message).toContain('진행 중');
  });

  it('busy_해제되면_모든_조작이_즉시_복구된다', async () => {
    await loadPage();
    startBusy();
    expect(screen.getByTestId('label-toolbar-save')).toBeDisabled();

    act(() => {
      useLabelStore.getState().cancelBusy();
    });

    await waitFor(() => expect(screen.getByTestId('label-toolbar-save')).not.toBeDisabled());
    expect(screen.getByLabelText('다음 프레임')).not.toBeDisabled();
    expect(
      within(screen.getByTestId('canvas-option-bar')).getByRole('button', { name: '삭제' }),
    ).not.toBeDisabled();
    expect(screen.getByTestId('canvas-shell').getAttribute('data-edit-blocked')).toBe('false');
    // 단축키도 복구
    fireEvent.keyDown(window, { key: 'p', code: 'KeyP' });
    expect(useLabelStore.getState().activeTool).toBe(ToolType.POLYGON);
  });

  it('비식별_잠금과_busy_차단은_서로_독립적으로_성립한다', async () => {
    // given: 비식별 재처리 잠금 영상(busy 아님)
    mock
      .onGet('/frames/200/labels')
      .reply(200, labelsPayload(200, 0, [labelOnFrame0], { lockSttsCd: 'LOCKED_FOR_REDEIDENT' }));
    await loadPage();

    // 잠금만으로도 저장은 막히고, 캔버스는 읽기전용이다. 단 busy 는 아니다.
    expect(screen.getByTestId('label-toolbar-save')).toBeDisabled();
    expect(screen.getByTestId('canvas-shell').getAttribute('data-read-only')).toBe('true');
    expect(screen.getByTestId('canvas-shell').getAttribute('data-edit-blocked')).toBe('false');
    // 잠금은 프레임 전환을 막지 않는다(기존 동작 무회귀).
    expect(screen.getByLabelText('다음 프레임')).not.toBeDisabled();

    // when: 여기에 busy 가 겹치면 프레임 전환까지 막힌다.
    startBusy();
    await waitFor(() => expect(screen.getByLabelText('다음 프레임')).toBeDisabled());

    // then: busy 만 풀리면 잠금 차단은 그대로 남는다(두 차단은 독립).
    act(() => {
      useLabelStore.getState().cancelBusy();
    });
    await waitFor(() => expect(screen.getByLabelText('다음 프레임')).not.toBeDisabled());
    expect(screen.getByTestId('label-toolbar-save')).toBeDisabled();
    expect(screen.getByTestId('canvas-shell').getAttribute('data-read-only')).toBe('true');
  });
});
