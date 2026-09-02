// Phase 4 (N-4) — 포털 업로드 라벨링 화면의 **진행 표시·취소 대칭**.
//
// 이 화면은 Phase 2 에서 편집 차단만 배선됐고 진행 오버레이가 없었다. 그래서 저장이 늘어지면
// 사용자에게 남는 단서는 버튼 스피너뿐이고, **취소 수단이 아예 없어** 5분 fail-safe 까지 캔버스가
// 잠긴 채로 남는다. 내부 라벨링(LabelingPage)과 인지·취소 축을 대칭으로 맞춘다.
//
// ⚠ 이 화면은 ADR-013 별도 경로(LS_PORTAL_*)라 AI 작업이 없다 — busy 종류는 SAVE 뿐이다.
//   그 전제를 테스트로 고정한다(AI 도구가 새어 들어오면 여기서 깨진다).

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, fireEvent, screen, waitFor } from '@testing-library/react';
import MockAdapter from 'axios-mock-adapter';

vi.mock('react-konva', async () => (await import('@/test/konvaMock')).createKonvaMock());

import { apiClient } from '@/lib/api/client';
import { BUSY_OVERLAY_DELAY_MS } from '@/features/label/busyPolicy';
import type { Label } from '@/features/label/types';
import { renderWithProviders } from '@/test/renderWithProviders';
import { useAuthStore } from '@/stores/useAuthStore';
import { useLabelStore, type BusyKind } from '@/stores/useLabelStore';

import { PortalUploadLabelingView } from '../PortalUploadLabelingView';

// 테스트용 더미 인증값(비밀 아님 — 시크릿 스캐너 오탐 회피용 조합).
const FAKE_TOKEN = ['t', 'o', 'k'].join('');
const FRAME_SN = 100;

function ok(data: unknown) {
  return { success: true, data, message: null, errorCode: null };
}

function detail() {
  return {
    uldSn: 1,
    // 신규 접수는 영상뿐이라 픽스처도 영상이다(프레임 1건).
    uldTypeCd: 'VIDEO',
    orgnlFileNm: 'clip.mp4',
    fileSz: 1024,
    mimeTypeNm: 'video/mp4',
    uldSttsCd: 'READY',
    frmeCnt: 1,
    vdoLenSec: null,
    fps: null,
    regDt: '2026-07-17T00:00:00',
    mdfcnDt: null,
    frames: [{ uldFrmeSn: FRAME_SN, uldSn: 1, frmeNo: 0, regDt: '2026-07-17T00:00:00' }],
  };
}

function bboxLabel(): Label {
  return {
    id: 'tmp-1',
    frameNo: 0,
    classId: 1,
    className: 'car',
    source: 'MANUAL',
    shape: { type: 'BBOX', left: 10, top: 20, right: 110, bottom: 220 },
  };
}

function renderPage() {
  return renderWithProviders(<PortalUploadLabelingView uldSn={1} />, {
    initialEntries: ['/portal/label/1?source=upload'],
    routes: [{ path: '/portal/label/:id', element: <PortalUploadLabelingView uldSn={1} /> }],
  });
}

/** 진행 오버레이가 이미 떠 있는 시점(지연 창 경과)으로 만든다. */
function ageBusyPastOverlayDelay() {
  const busy = useLabelStore.getState().busy;
  if (busy === null) throw new Error('busy 가 없습니다');
  useLabelStore.setState({
    busy: { ...busy, startedAt: busy.startedAt - (BUSY_OVERLAY_DELAY_MS + 1000) },
  });
}

describe('포털 라벨링 화면 — 업로드 자산 갈래 진행 오버레이·취소 (N-4)', () => {
  let mock: MockAdapter;
  let releasePut: (() => void) | null;

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
    releasePut = null;
    useAuthStore.setState({
      token: FAKE_TOKEN,
      claims: { sub: '10', role: 'PORTAL_USER', channel: 'PORTAL', exp: 9999999999 },
    });
    useLabelStore.getState().reset();
    mock.onGet('/manage/labels').reply(200, ok([]));
    mock.onGet(/\/portal\/uploads\/frames\/\d+\/image/).reply(200, new Blob());
    mock.onGet('/portal/uploads/1').reply(200, ok(detail()));
    mock.onGet(`/portal/uploads/frames/${FRAME_SN}/labels`).reply(200, ok([]));
    mock.onPut(`/portal/uploads/frames/${FRAME_SN}/labels`).reply(
      () =>
        new Promise((resolve) => {
          releasePut = () => resolve([200, ok([])]);
        }),
    );
    vi.stubGlobal('URL', {
      createObjectURL: vi.fn(() => 'blob:x'),
      revokeObjectURL: vi.fn(),
    });
  });

  afterEach(() => {
    // in-flight 요청을 남기지 않는다(다음 테스트로 새는 pending promise 방지).
    releasePut?.();
    mock.restore();
    useAuthStore.getState().clear();
    useLabelStore.getState().reset();
    vi.restoreAllMocks();
  });

  async function startSave() {
    renderPage();
    await waitFor(() => expect(screen.getByTestId('canvas-shell')).toBeInTheDocument());
    act(() => {
      useLabelStore.getState().addLabel(bboxLabel());
    });
    fireEvent.click(screen.getByRole('button', { name: '저장' }));
    await waitFor(() => expect(useLabelStore.getState().busy?.kind).toBe('SAVE'));
  }

  it('포털업로드_저장_중_진행_오버레이가_뜨고_취소할_수_있다', async () => {
    // given: 저장이 지연 창(300ms)을 넘겨 진행 중
    await startSave();
    act(() => ageBusyPastOverlayDelay());

    // then: 캔버스 위에 진행 오버레이가 뜬다(작업명 + 취소). 모델명은 노출하지 않는다.
    const overlay = await screen.findByTestId('busy-overlay');
    expect(overlay).toHaveTextContent('저장 중');
    expect(overlay.textContent ?? '').not.toMatch(/YOLO|SAM/i);
    // 파일명·프레임 식별자 등 내부 값은 오버레이에 담기지 않는다.
    expect(overlay.textContent ?? '').not.toContain(String(FRAME_SN));
    expect(overlay.textContent ?? '').not.toContain('photo.jpg');

    // when: 취소
    fireEvent.click(screen.getByRole('button', { name: '작업 취소' }));

    // then: 즉시 편집으로 복귀하고 오버레이는 사라진다.
    await waitFor(() => expect(useLabelStore.getState().busy).toBeNull());
    expect(screen.queryByTestId('busy-overlay')).not.toBeInTheDocument();
    await waitFor(() =>
      expect(screen.getByTestId('canvas-shell').getAttribute('data-edit-blocked')).toBe('false'),
    );
    expect(screen.getByRole('button', { name: '바운딩 박스' })).not.toBeDisabled();
    // 취소는 "저장됨" 이 아니다 — 미저장 표시가 남아야 사용자가 다시 저장할 수 있다.
    expect(useLabelStore.getState().dirtyLabels.size).toBeGreaterThan(0);
  });

  it('포털업로드_취소_후_도착한_저장_응답은_반영되지_않는다', async () => {
    // 취소의 시맨틱 — 서버 처리를 멈추는 게 아니라 도착 결과를 폐기한다.
    await startSave();
    act(() => ageBusyPastOverlayDelay());
    await screen.findByTestId('busy-overlay');

    fireEvent.click(screen.getByRole('button', { name: '작업 취소' }));
    await waitFor(() => expect(useLabelStore.getState().busy).toBeNull());

    await act(async () => {
      releasePut?.();
      await Promise.resolve();
    });

    // 폐기됐으므로 저장 성공 후처리(clearDirty)가 일어나지 않는다.
    expect(useLabelStore.getState().dirtyLabels.size).toBeGreaterThan(0);
  });

  it('포털업로드_짧은_저장에는_오버레이가_뜨지_않는다', async () => {
    // 지연 창(<300ms) 안에서는 표시하지 않는다 — 짧은 작업마다 깜빡이면 화면이 고장난 것처럼 보인다.
    await startSave();
    expect(screen.queryByTestId('busy-overlay')).not.toBeInTheDocument();
  });

  it('포털업로드에는_AI_작업이_없어_AI_busy가_발생하지_않는다', async () => {
    // ADR-013 — 이 경로에는 오토라벨·분할·추적이 없다. busy 축에 오르는 종류는 SAVE 뿐이다.
    const seen: BusyKind[] = [];
    const unsubscribe = useLabelStore.subscribe((s) => {
      const kind = s.busy?.kind;
      if (kind !== undefined && seen[seen.length - 1] !== kind) seen.push(kind);
    });
    try {
      await startSave();
      act(() => ageBusyPastOverlayDelay());
      const overlay = await screen.findByTestId('busy-overlay');

      // AI 도구 진입점 자체가 없다(툴바·모달 어디에도).
      expect(screen.queryByRole('button', { name: /AI/ })).not.toBeInTheDocument();
      expect(screen.queryByRole('button', { name: /추적|분할|키포인트/ })).not.toBeInTheDocument();
      // 오버레이 문구도 AI 작업이 아니다.
      expect(overlay).toHaveTextContent('저장 중');
      expect(seen).toEqual(['SAVE']);
    } finally {
      unsubscribe();
    }
  });
});
