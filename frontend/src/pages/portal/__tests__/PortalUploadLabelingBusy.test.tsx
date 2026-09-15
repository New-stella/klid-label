// 포털 라벨링 화면 — **업로드 자산 갈래**의 진행 표시·취소. @design SCREEN-029
//
// ★이 파일은 승계본이다. 예전에는 업로드 갈래만 별도의 미니멀 화면이 그렸고 그 화면의 시험이
//   여기 있는 단정(진행 오버레이·취소·편집 차단·busy 종류가 SAVE 뿐)을 지켰다. 그 화면이
//   관제용 라벨링 도구로 흡수됐으므로 **같은 단정을 통합 화면 주소에서 그대로 이어받는다.**
//
// ⚠ 포털에는 AI 보조가 없다 — busy 종류는 SAVE 뿐이다. 그 전제를 여기서 고정한다
//   (AI 도구가 새어 들어오면 이 파일이 깨진다).

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

import { PortalLabelingPage } from '../PortalLabelingPage';

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
  return renderWithProviders(<PortalLabelingPage />, {
    initialEntries: ['/portal/label/1?source=upload'],
    routes: [{ path: '/portal/label/:id', element: <PortalLabelingPage /> }],
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

describe('포털 라벨링 화면 — 업로드 자산 갈래 진행 오버레이·취소', () => {
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
    // 관심 축 밖의 조회(시스템 설정 등)는 조용히 흘려보낸다 — **가장 마지막**에 등록해야 한다.
    mock.onAny().reply(200, ok(null));
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

  /*
   * ★승계 — 폐기 시험 `포털업로드_저장중에는_캔버스_편집이_차단된다` 의 **핵심 단언**이다.
   *
   * ⚠ 이 화면의 저장은 현재 프레임 **전체교체 PUT** 이라, 저장 스냅샷을 뜬 뒤에 그린 라벨은
   *   저장에도 담기지 않고 저장 성공 후 재조회에 덮여 사라진다. 차단이 그 창 자체를 없앤다.
   * ⚠ **「차단이 걸린다」와 「차단이 풀린다」는 다른 축이다** — 아래 취소 케이스가 «풀린다» 만
   *   보고 있어, 이 케이스가 없으면 **차단을 통째로 걷어내도 전건 초록**이 된다(걸린 적이 없는
   *   것과 풀린 것이 구분되지 않는다).
   */
  it('★포털업로드_저장중에는_캔버스_도구_저장이_모두_차단된다', async () => {
    // given / when: 저장 PUT 이 아직 응답하지 않은 in-flight 상태
    await startSave();

    // then: 캔버스가 잠긴다(편집 차단 + 읽기 전용).
    const shell = screen.getByTestId('canvas-shell');
    expect(shell.getAttribute('data-edit-blocked')).toBe('true');
    expect(shell.getAttribute('data-read-only')).toBe('true');
    // then: 그리기 도구와 저장이 함께 잠긴다 — 버튼만 남으면 차단이 그대로 우회된다.
    expect(screen.getByRole('button', { name: '바운딩 박스' })).toBeDisabled();
    expect(screen.getByTestId('label-toolbar-save')).toBeDisabled();
  });

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

  it('포털업로드_저장은_AI_busy를_일으키지_않는다', async () => {
    // 저장이 오르는 busy 축은 SAVE 뿐이다 — 저장 흐름이 AI 작업 종류를 끌어오지 않는다.
    // ★반전(2026-09-15 · SCREEN-029) — 구 케이스 「포털업로드에는_AI_작업이_없어_AI_busy가_발생하지_않는다」
    //   는 AI 진입점이 없다는 전제였다. 이제 업로드 자산에도 AI 보조 세 기능이 서므로 진입점 부재
    //   단언만 뒤집고(진행 중에는 비활성), 저장이 SAVE 만 올린다는 단언은 그대로 둔다.
    const seen: BusyKind[] = [];
    const unsubscribe = useLabelStore.subscribe((s) => {
      const kind = s.busy?.kind;
      if (kind !== undefined && seen[seen.length - 1] !== kind) seen.push(kind);
    });
    try {
      await startSave();
      act(() => ageBusyPastOverlayDelay());
      const overlay = await screen.findByTestId('busy-overlay');

      // AI 보조 진입점은 서지만, 다른 작업(저장)이 진행 중이라 새 AI 작업을 시작할 수 없다.
      expect(screen.getByRole('button', { name: 'AI 탐지' })).toBeDisabled();
      expect(screen.getByRole('button', { name: 'AI 분할' })).toBeDisabled();
      expect(screen.getByRole('button', { name: 'AI 자동 추적 패널로 이동' })).toBeDisabled();
      // 선택 객체 AI 추적·키포인트 진입점은 계속 없다.
      expect(screen.queryByRole('button', { name: /^AI 추적$|키포인트|스켈레톤/ })).not.toBeInTheDocument();
      // 오버레이 문구도 AI 작업이 아니다.
      expect(overlay).toHaveTextContent('저장 중');
      expect(seen).toEqual(['SAVE']);
    } finally {
      unsubscribe();
    }
  });
});
