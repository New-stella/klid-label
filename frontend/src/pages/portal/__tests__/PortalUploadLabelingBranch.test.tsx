/**
 * 포털 라벨링 화면 — **업로드 자산 갈래**. @design SCREEN-029
 *
 * <h3>이 파일은 승계본이다</h3>
 * 예전에는 업로드 갈래만 별도의 미니멀 화면(`PortalUploadLabelingView`)이 그렸고 그 화면의
 * 시험이 여기 있는 단정들을 지키고 있었다. 그 화면이 폐기되고 관제용 라벨링 도구
 * (`LabelingPage` + `portalMode`)로 흡수됐으므로, **그 시험이 지키던 단정을 이 파일이 그대로
 * 이어받는다** — 지우고 끝내면 진행 오버레이·취소·편집 차단·프레임 0건 안내·미저장 편집 보호·
 * 자산 상태 게이트의 회귀 가드가 함께 사라진다.
 *
 * ★진입은 **통합 화면의 주소**로 한다(`/portal/label/{uldSn}?source=upload`). 갈래를 고르는 것은
 *  화면이 아니라 주소의 출처 표기이고, 그 판정은 `labelingEntry` 한 곳이 갖는다.
 */

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, fireEvent, screen, waitFor, within } from '@testing-library/react';
import MockAdapter from 'axios-mock-adapter';

vi.mock('react-konva', async () => (await import('@/test/konvaMock')).createKonvaMock());

import { apiClient } from '@/lib/api/client';
import { PORTAL_KEYS } from '@/lib/queryKeys';
import type { Label } from '@/features/label/types';
import { renderWithProviders } from '@/test/renderWithProviders';
import { useAuthStore } from '@/stores/useAuthStore';
import { useLabelStore } from '@/stores/useLabelStore';

import { PortalLabelingPage } from '../PortalLabelingPage';

// 테스트용 더미 인증값(비밀 아님 — 시크릿 스캐너 오탐 회피용 조합).
const FAKE_TOKEN = ['t', 'o', 'k'].join('');

function ok(data: unknown) {
  return { success: true, data, message: null, errorCode: null };
}

function frameRow(uldFrmeSn: number, frmeNo: number) {
  return { uldFrmeSn, uldSn: 1, frmeNo, regDt: '2026-07-17T00:00:00' };
}

function detail(over: Record<string, unknown> = {}) {
  return {
    uldSn: 1,
    // 신규 접수는 영상뿐이다 — 프레임 1건짜리 영상이 기본 픽스처다.
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
    expiresAt: null,
    frames: [frameRow(100, 0)],
    ...over,
  };
}

function bboxRaw(label: string) {
  return {
    uldLblSn: 1,
    uldFrmeSn: 100,
    lblTypeCd: 'BBOX',
    label,
    points: [
      [10, 20],
      [110, 220],
    ],
    regDt: '2026-07-17T00:00:00',
    mdfcnDt: null,
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

function renderPage(entry = '/portal/label/1?source=upload') {
  return renderWithProviders(<PortalLabelingPage />, {
    initialEntries: [entry],
    routes: [{ path: '/portal/label/:id', element: <PortalLabelingPage /> }],
  });
}

/** 캔버스가 뜰 때까지 기다린다 — CanvasShell 은 lazy 라 첫 렌더에는 없다. */
async function waitForCanvas() {
  await waitFor(() => expect(screen.getByTestId('canvas-shell')).toBeInTheDocument());
}

describe('포털 라벨링 화면 — 업로드 자산 갈래', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
    useAuthStore.setState({
      token: FAKE_TOKEN,
      claims: { sub: '10', role: 'PORTAL_USER', channel: 'PORTAL', exp: 9999999999 },
    });
    useLabelStore.getState().reset();
    // 라벨 마스터(사이드바·속성 패널·OverlayLayer) — 빈 목록.
    mock.onGet('/manage/labels').reply(200, ok([]));
    // 프레임 이미지 — Blob (jsdom 은 createObjectURL 미지원이라 스텁을 세운다).
    mock.onGet(/\/portal\/uploads\/frames\/\d+\/image/).reply(200, new Blob());
    vi.stubGlobal('URL', {
      createObjectURL: vi.fn(() => 'blob:x'),
      revokeObjectURL: vi.fn(),
    });
  });

  afterEach(() => {
    mock.restore();
    useAuthStore.getState().clear();
    useLabelStore.getState().reset();
    vi.restoreAllMocks();
  });

  /** 관심 축 밖의 조회(시스템 설정 등)를 조용히 흘려보낸다. **가장 마지막**에 등록해야 한다. */
  function catchAllRest() {
    mock.onAny().reply(200, ok(null));
  }

  // ── 흡수 자체 — 관제용 라벨링 도구의 구성이 그대로 선다 ──────────────────
  it('★관제_라벨링_도구의_화면_구성이_그대로_뜬다_미니멀_화면이_아니다', async () => {
    mock.onGet('/portal/uploads/1').reply(200, ok(detail()));
    mock.onGet('/portal/uploads/frames/100/labels').reply(200, ok([]));
    catchAllRest();

    renderPage();
    await waitForCanvas();

    // 헤더 · 좌측 도구바 · 캔버스 상단 옵션바 · 우측 객체 패널이 모두 선다.
    expect(screen.getByRole('button', { name: '뒤로가기' })).toBeInTheDocument();
    expect(screen.getByRole('toolbar', { name: '라벨링 도구' })).toBeInTheDocument();
    expect(screen.getByTestId('canvas-option-bar')).toBeInTheDocument();
    expect(screen.getByTestId('labeling-right-panel')).toBeInTheDocument();
    expect(screen.getByTestId('object-count-badge')).toBeInTheDocument();
    // 캔버스 상단 옵션바의 조작 — 실행취소·다시실행·확대/축소·라벨 표시.
    //
    // ★<b>삭제는 이 목록에서 뺐다 — 표식을 잃은 것이지 취지를 잃은 것이 아니다.</b> 포털 채널은
    //   옵션바의 삭제를 두지 않는다(우측 객체 패널의 행별 「객체 삭제」가 그 자리를 갖는다 —
    //   무엇을 지우는지가 행으로 드러나고, 옵션바 쪽은 선택이 없어도 눌리면서 아무 일도 하지
    //   않았다). 이 케이스가 지키려는 것은 「미니멀 화면이 아니라 관제 도구가 뜬다」이고, 그것은
    //   아래 남은 표식들(도구바·옵션바·우측 패널·객체 수·프레임 이동·메타 탭)이 여전히 증명한다.
    //   ⚠ 삭제 수단이 사라진 것이 아님은 <b>아래 별도 케이스</b>가 따로 고정한다.
    expect(screen.getByTestId('label-option-zoom-in')).toBeInTheDocument();
    expect(screen.getByTestId('label-option-zoom-out')).toBeInTheDocument();
    expect(screen.getByTestId('label-option-visibility')).toBeInTheDocument();
    // 프레임 위치 표시·이동 컨트롤(구 미니멀 화면의 자체 «N / M» 표기를 승계한다).
    expect(screen.getByRole('group', { name: '프레임 이동' })).toBeInTheDocument();
    expect(screen.getByTestId('frame-total-count')).toHaveTextContent('1');
    // 메타 탭이 열려 있다(다섯 축 + 이벤트 어노테이션의 자리).
    expect(screen.getByTestId('right-tab-meta')).toBeInTheDocument();
  });

  /**
   * ★<b>삭제 수단이 사라지지 않았다</b> — 옵션바에서 뺀 자리를 우측 객체 패널이 갖는다.
   *
   * 이 케이스가 없으면 위에서 표식 하나를 지운 것이 「삭제를 통째로 없앴다」와 구분되지 않는다.
   * 옵션바의 삭제가 <b>없다</b>는 것과 패널의 삭제가 <b>있다</b>는 것을 함께 단언해야 그 구분이
   * 기계로 남는다.
   */
  it('★포털은_옵션바_삭제를_두지_않고_객체_패널의_행별_삭제가_그_자리를_갖는다', async () => {
    mock.onGet('/portal/uploads/1').reply(200, ok(detail()));
    mock.onGet('/portal/uploads/frames/100/labels').reply(200, ok([bboxRaw('car')]));
    catchAllRest();

    renderPage();
    await waitForCanvas();

    // 옵션바에는 없다.
    expect(screen.queryByTestId('label-option-delete')).toBeNull();
    // 그러나 객체마다 삭제가 있다 — 무엇을 지우는지가 행으로 드러나는 자리다.
    expect((await screen.findAllByRole('button', { name: '객체 삭제' })).length).toBeGreaterThan(0);
  });

  /*
   * 구 미니멀 화면의 이탈 경로는 헤더의 «뒤로가기» 링크(`/portal/uploads`) 하나였다.
   * 통합 화면은 사양대로 헤더의 × 닫기(dirty 시 저장확인 모달, 아니면 뒤로)가 그 자리를 갖는다.
   */
  it('헤더에_이탈_경로가_있고_미저장_변경이_있으면_확인을_먼저_받는다', async () => {
    mock.onGet('/portal/uploads/1').reply(200, ok(detail()));
    mock.onGet('/portal/uploads/frames/100/labels').reply(200, ok([]));
    catchAllRest();

    renderPage();
    await waitForCanvas();

    act(() => {
      useLabelStore.getState().addLabel(bboxLabel());
    });
    fireEvent.click(screen.getByRole('button', { name: '뒤로가기' }));

    expect(await screen.findByText('저장 안 한 변경사항이 있습니다')).toBeInTheDocument();
  });

  // ── ★창구 — 자산 축으로 나간다(썸네일 포함) ─────────────────────────────
  it('★조회_이미지_저장이_모두_자산_축_창구로_나간다_데이터마트_창구는_0건', async () => {
    mock
      .onGet('/portal/uploads/1')
      .reply(200, ok(detail({ frmeCnt: 2, frames: [frameRow(100, 0), frameRow(101, 1)] })));
    mock.onGet('/portal/uploads/frames/100/labels').reply(200, ok([]));
    mock.onGet('/portal/uploads/frames/101/labels').reply(200, ok([]));
    mock.onPut('/portal/uploads/frames/100/labels').reply(200, ok([]));
    catchAllRest();

    renderPage();
    await waitForCanvas();
    act(() => {
      useLabelStore.getState().addLabel(bboxLabel());
    });
    fireEvent.click(screen.getByRole('button', { name: '저장' }));
    await waitFor(() => expect(mock.history.put).toHaveLength(1));

    const gets = mock.history.get.map((r) => r.url ?? '');
    // 라벨 조회 · 자산 상세
    expect(gets).toContain('/portal/uploads/frames/100/labels');
    expect(gets).toContain('/portal/uploads/1');
    // 메인 캔버스 이미지
    expect(gets).toContain('/portal/uploads/frames/100/image');
    /*
     * ★썸네일도 자산 축이다 — 가장 놓치기 쉬운 지점이다. 메인 캔버스만 고치면 화면은 뜨는데
     *   필름스트립이 통째로 404/403 이 된다. 현재 프레임이 아닌 형제 프레임의 이미지 요청이
     *   자산 축으로 나가는지를 본다(그 요청은 썸네일만 낸다).
     */
    expect(gets).toContain('/portal/uploads/frames/101/image');
    // 저장도 자산 축.
    expect(mock.history.put[0]?.url).toBe('/portal/uploads/frames/100/labels');
    /*
     * 데이터마트 축의 **라벨·이미지** 창구는 한 번도 부르지 않는다.
     * ⚠ 판정 범위를 `/portal/frames/` 전체로 넓히지 말 것 — 메타 창구가 그 접두를 공유하는데
     *   그건 자산 출처를 가리지 않는 **포털 작업 축**이라(서버가 출처로 판정한다) 여기서 금지
     *   대상이 아니다. 넓히면 메타 탭을 여는 순간 정상 구현이 빨간불이 된다.
     */
    expect(gets.filter((u) => /^\/portal\/frames\/\d+\/(labels|image)$/.test(u))).toEqual([]);
    expect(gets.filter((u) => /^\/frames\//.test(u))).toEqual([]);
    expect(mock.history.post.filter((r) => r.url === '/portal/user-labels')).toEqual([]);
  });

  it('저장은_현재_프레임_라벨_전체교체_PUT_1회_최상위_raw배열', async () => {
    mock.onGet('/portal/uploads/1').reply(200, ok(detail()));
    mock.onGet('/portal/uploads/frames/100/labels').reply(200, ok([]));
    let rawData: string | undefined;
    mock.onPut('/portal/uploads/frames/100/labels').reply((config) => {
      rawData = config.data as string;
      return [200, ok([])];
    });
    catchAllRest();

    renderPage();
    await waitForCanvas();
    act(() => {
      useLabelStore.getState().addLabel(bboxLabel());
    });
    fireEvent.click(screen.getByRole('button', { name: '저장' }));

    await waitFor(() => expect(mock.history.put).toHaveLength(1));
    const parsed = JSON.parse(rawData as string);
    // 최상위 raw 배열 + points 는 배열(문자열 직렬화 아님 — 데이터마트 축과 다르다).
    expect(Array.isArray(parsed)).toBe(true);
    expect(parsed[0]).toMatchObject({ lblTypeCd: 'BBOX', label: 'car' });
    expect(Array.isArray(parsed[0].points)).toBe(true);
    expect(typeof parsed[0].points).not.toBe('string');
  });

  it('라벨_전체삭제_후_저장은_빈_배열_PUT', async () => {
    mock.onGet('/portal/uploads/1').reply(200, ok(detail()));
    mock.onGet('/portal/uploads/frames/100/labels').reply(200, ok([bboxRaw('car')]));
    let putBody: unknown = null;
    mock.onPut('/portal/uploads/frames/100/labels').reply((config) => {
      putBody = JSON.parse(config.data as string);
      return [200, ok([])];
    });
    catchAllRest();

    renderPage();
    await waitFor(() => expect(useLabelStore.getState().labels).toHaveLength(1));

    act(() => {
      useLabelStore.getState().setLabels([]);
    });
    fireEvent.click(screen.getByRole('button', { name: '저장' }));

    await waitFor(() => expect(mock.history.put).toHaveLength(1));
    // 전체교체 PUT body 는 빈 배열(멱등 전체 삭제).
    expect(putBody).toEqual([]);
  });

  // ── ★미저장 편집 보호 (전체교체 PUT 이라 유실이 곧 작업 통째 유실) ────────
  /*
   * 구 미니멀 화면은 「dirty>0 이면 조회 응답으로 덮지 않고 첫 로드에 한해 병합」으로 이 축을
   * 지켰다. 통합 화면은 **창 자체를 없애는 쪽**으로 지킨다 — 라벨이 도착하기 전에는 캔버스를
   * 그리지 않으므로 그리는 사이에 응답이 도착하는 경합이 성립하지 않는다.
   * 두 축을 함께 세운다: ①로딩 중에는 편집 수단이 없다 ②이미 편집한 뒤의 재조회는 덮지 않는다.
   */
  it('★라벨이_도착하기_전에는_캔버스를_그리지_않는다_편집_창_자체가_없다', async () => {
    mock.onGet('/portal/uploads/1').reply(200, ok(detail()));
    const held = { release: () => {} };
    mock.onGet('/portal/uploads/frames/100/labels').reply(
      () =>
        new Promise((resolve) => {
          held.release = () => resolve([200, ok([bboxRaw('server-label')])]);
        }),
    );
    catchAllRest();

    renderPage();

    // 자산 상세는 왔는데 라벨이 아직이다 — 캔버스도 도구바도 없다.
    await waitFor(() =>
      expect(mock.history.get.some((r) => r.url === '/portal/uploads/1')).toBe(true),
    );
    expect(screen.queryByTestId('canvas-shell')).toBeNull();
    expect(screen.queryByRole('toolbar', { name: '라벨링 도구' })).toBeNull();

    // 라벨이 도착하면 비로소 그린다.
    await act(async () => {
      held.release();
      await Promise.resolve();
    });
    await waitForCanvas();
    await waitFor(() =>
      expect(useLabelStore.getState().labels.map((l) => l.className)).toEqual(['server-label']),
    );
  });

  it('★미저장_편집이_있으면_재조회_응답이_작업본을_덮지_않는다', async () => {
    mock.onGet('/portal/uploads/1').reply(200, ok(detail()));
    mock
      .onGet('/portal/uploads/frames/100/labels')
      .replyOnce(200, ok([]))
      .onGet('/portal/uploads/frames/100/labels')
      .reply(200, ok([bboxRaw('server-label')]));
    catchAllRest();

    const { queryClient } = renderPage();
    await waitForCanvas();

    // 사용자가 그린다(dirty>0).
    act(() => {
      useLabelStore.getState().addLabel(bboxLabel());
    });
    expect(useLabelStore.getState().dirtyLabels.size).toBeGreaterThan(0);

    // 백그라운드 재조회.
    await act(async () => {
      await queryClient.refetchQueries();
    });
    /*
     * ★「일어나지 않는다」를 단언하기 전에 **일어날 조건이 실제로 갖춰졌는지** 먼저 확정한다.
     *   ①서버 응답이 캐시까지 도착했고 ②화면이 그것을 반영할 시간이 지났다. 이 둘을 세우지 않으면
     *   «아직 안 왔을 뿐» 인 상태를 보호로 오독해, 덮어쓰는 구현에서도 초록이 된다(실측으로 확인).
     */
    await waitFor(() =>
      expect(queryClient.getQueryData(PORTAL_KEYS.uploadFrameLabels(100))).toHaveLength(1),
    );
    await act(async () => {
      await new Promise((resolve) => setTimeout(resolve, 50));
    });

    // then: 미저장 작업본이 그대로다.
    const names = useLabelStore.getState().labels.map((l) => l.className);
    expect(names).toContain('car');
    expect(names).not.toContain('server-label');
    expect(useLabelStore.getState().dirtyLabels.size).toBeGreaterThan(0);
  });

  it('저장_성공후_재조회분이_작업본에_반영된다', async () => {
    // 저장 뒤에도 dirty 가 남으면 위 보호 가드가 계속 걸려 그 프레임이 서버와 영영
    // 재동기화되지 않는다 — 저장 성공 시 미저장 표시를 반드시 내린다.
    mock.onGet('/portal/uploads/1').reply(200, ok(detail()));
    mock
      .onGet('/portal/uploads/frames/100/labels')
      .replyOnce(200, ok([]))
      .onGet('/portal/uploads/frames/100/labels')
      .reply(200, ok([bboxRaw('server-canon')]));
    mock.onPut('/portal/uploads/frames/100/labels').reply(200, ok([]));
    catchAllRest();

    renderPage();
    await waitForCanvas();
    act(() => {
      useLabelStore.getState().addLabel(bboxLabel());
    });

    fireEvent.click(screen.getByRole('button', { name: '저장' }));
    await waitFor(() => expect(mock.history.put).toHaveLength(1));

    await waitFor(() => expect(useLabelStore.getState().dirtyLabels.size).toBe(0));
    await waitFor(() =>
      expect(useLabelStore.getState().labels.map((l) => l.className)).toEqual(['server-canon']),
    );
  });

  // ── AI 보조·검수·버전관리 진입점 부재 ──────────────────────────────────
  it('★AI보조_세_진입점은_서고_선택객체추적_키포인트_진입점은_없다', async () => {
    mock.onGet('/portal/uploads/1').reply(200, ok(detail()));
    mock.onGet('/portal/uploads/frames/100/labels').reply(200, ok([]));
    catchAllRest();

    renderPage();
    await waitForCanvas();

    const toolbar = screen.getByRole('toolbar', { name: '라벨링 도구' });
    // 노출 도구: 선택 / 바운딩 박스 / 폴리곤(사양 §좌측 도구바 「그리기」).
    expect(within(toolbar).getByRole('radio', { name: /바운딩 박스/ })).toBeInTheDocument();
    expect(within(toolbar).getByRole('radio', { name: /폴리곤/ })).toBeInTheDocument();
    // ★반전(2026-09-15 · SCREEN-029) — 업로드 자산에도 AI 보조 세 기능이 선다(자산 출처 무관).
    //   구 단언(AI 분할·AI 탐지·자동 추적 미노출)만 뒤집고 나머지 미노출 단언은 그대로 둔다.
    expect(within(toolbar).getByRole('radio', { name: 'AI 분할' })).toBeInTheDocument();
    expect(within(toolbar).getByRole('button', { name: 'AI 탐지' })).toBeInTheDocument();
    expect(
      within(toolbar).getByRole('button', { name: 'AI 자동 추적 패널로 이동' }),
    ).toBeInTheDocument();
    expect(screen.getByTestId('auto-track-panel')).toBeInTheDocument();
    // 미노출: 선택 객체 AI 추적, 키포인트(스켈레톤), 모델명 표기.
    expect(screen.queryByRole('button', { name: /SAM/i })).toBeNull();
    expect(screen.queryByRole('button', { name: /^AI 추적$/ })).toBeNull();
    expect(screen.queryByRole('button', { name: /키포인트|스켈레톤/ })).toBeNull();
    expect(screen.queryByRole('button', { name: /오토라벨/ })).toBeNull();
  });

  it('★검수제출_버전관리_비식별신고_프레임폐기_진입점이_없다', async () => {
    mock.onGet('/portal/uploads/1').reply(200, ok(detail()));
    mock.onGet('/portal/uploads/frames/100/labels').reply(200, ok([]));
    catchAllRest();

    renderPage();
    await waitForCanvas();

    expect(screen.queryByTestId('submit-review-button')).toBeNull();
    expect(screen.queryByTestId('cancel-submit-review-button')).toBeNull();
    expect(screen.queryByTestId('start-version-open')).toBeNull();
    expect(screen.queryByTestId('frame-discard-toggle')).toBeNull();
    expect(screen.queryByRole('button', { name: /비식별.*신고/ })).toBeNull();
    // 이슈 탭도 포털에는 없다.
    expect(screen.queryByTestId('right-tab-issues')).toBeNull();
  });

  it('내려받기_조작은_라벨링_화면에_두지_않는다_목록_화면이_갖는다', async () => {
    // 자산 단위 조작이라 자산 목록 화면의 행 액션이 갖는다(확정 사양).
    mock.onGet('/portal/uploads/1').reply(200, ok(detail()));
    mock.onGet('/portal/uploads/frames/100/labels').reply(200, ok([]));
    catchAllRest();

    renderPage();
    await waitForCanvas();

    expect(screen.queryByRole('button', { name: '내보내기(JSON)' })).toBeNull();
    expect(screen.queryByRole('button', { name: '원본 다운로드' })).toBeNull();
    expect(screen.queryByRole('button', { name: '원본 다운로드 취소' })).toBeNull();
  });

  // ── 프레임 이동 ────────────────────────────────────────────────────────
  it('프레임을_옮기면_그_프레임의_라벨을_자산_축으로_다시_조회한다', async () => {
    mock
      .onGet('/portal/uploads/1')
      .reply(200, ok(detail({ frmeCnt: 2, frames: [frameRow(100, 0), frameRow(101, 1)] })));
    mock.onGet('/portal/uploads/frames/100/labels').reply(200, ok([bboxRaw('frame0')]));
    mock.onGet('/portal/uploads/frames/101/labels').reply(200, ok([bboxRaw('frame1')]));
    catchAllRest();

    renderPage();
    await waitFor(() => expect(useLabelStore.getState().labels[0]?.className).toBe('frame0'));

    fireEvent.click(screen.getByRole('button', { name: '다음 프레임' }));

    await waitFor(() => expect(useLabelStore.getState().labels[0]?.className).toBe('frame1'));
  });

  it('주소가_가리키는_프레임으로_바로_열린다', async () => {
    mock
      .onGet('/portal/uploads/1')
      .reply(200, ok(detail({ frmeCnt: 2, frames: [frameRow(100, 0), frameRow(101, 1)] })));
    mock.onGet('/portal/uploads/frames/100/labels').reply(200, ok([bboxRaw('frame0')]));
    mock.onGet('/portal/uploads/frames/101/labels').reply(200, ok([bboxRaw('frame1')]));
    catchAllRest();

    renderPage('/portal/label/1?source=upload&frame=101');

    await waitFor(() => expect(useLabelStore.getState().labels[0]?.className).toBe('frame1'));
    // 첫 프레임 라벨은 조회하지 않는다(주소가 가리키는 프레임을 바로 연다).
    expect(mock.history.get.some((r) => r.url === '/portal/uploads/frames/100/labels')).toBe(false);
  });

  it('목록에_없는_프레임_표기는_첫_프레임으로_읽는다_예외를_던지지_않는다', async () => {
    mock
      .onGet('/portal/uploads/1')
      .reply(200, ok(detail({ frmeCnt: 2, frames: [frameRow(100, 0), frameRow(101, 1)] })));
    mock.onGet('/portal/uploads/frames/100/labels').reply(200, ok([bboxRaw('frame0')]));
    mock.onGet('/portal/uploads/frames/101/labels').reply(200, ok([bboxRaw('frame1')]));
    catchAllRest();

    renderPage('/portal/label/1?source=upload&frame=99999');

    await waitFor(() => expect(useLabelStore.getState().labels[0]?.className).toBe('frame0'));
  });

  // ── 상태 안내 ──────────────────────────────────────────────────────────
  it('프레임이_0건이어도_실패로_그리지_않는다_상태_안내다', async () => {
    // 업로드 자산의 프레임은 마킹으로 뽑을 위치를 정한 뒤에 생긴다 — 없는 것이 정상인 구간이 있다.
    mock.onGet('/portal/uploads/1').reply(200, ok(detail({ frames: [], frmeCnt: 0 })));
    catchAllRest();

    renderPage();

    const notice = await screen.findByTestId('portal-upload-notice');
    expect(notice).toHaveAttribute('role', 'status');
    expect(notice).toHaveAttribute('data-notice-kind', 'no-frame');
    expect(notice).toHaveTextContent('표시할 프레임이 없습니다.');
    // 실패로 단정하지 않는다 — 오류 역할도, 실패 문구도 없다.
    expect(screen.queryByRole('alert')).toBeNull();
    expect(screen.queryByText(/실패|불러올 수 없|오류/)).toBeNull();
  });

  it('처리에_실패한_자산과_준비_중인_자산은_다른_문구로_가른다', async () => {
    // 한쪽은 기다려도 되지 않고 다른 쪽은 기다리면 된다 — 같은 문구로 뭉치면 사용자가 헛기다린다.
    mock.onGet('/portal/uploads/1').reply(200, ok(detail({ uldSttsCd: 'FAILED' })));
    catchAllRest();

    const { unmount } = renderPage();
    const failed = await screen.findByTestId('portal-upload-notice');
    expect(failed).toHaveAttribute('data-notice-kind', 'not-ready');
    const failedText = failed.textContent ?? '';
    expect(failedText).toMatch(/처리에 실패/);
    expect(screen.queryByTestId('canvas-shell')).toBeNull();
    unmount();

    mock.resetHistory();
    mock.onGet('/portal/uploads/1').reply(200, ok(detail({ uldSttsCd: 'PROCESSING' })));
    renderPage();
    const pending = await screen.findByTestId('portal-upload-notice');
    expect(pending).toHaveAttribute('data-notice-kind', 'not-ready');
    expect(pending.textContent ?? '').toMatch(/준비 중/);
    // ★두 문구가 실제로 다르다 — 같은 상수를 두 상태에 물리면 이 축이 죽는다.
    expect(pending.textContent).not.toBe(failedText);
  });

  it('자산을_불러올_수_없으면_안내만_하고_캔버스를_그리지_않는다', async () => {
    // 「없다」와 「남의 것이다」를 가르지 않는다 — 가르면 그 구분이 남의 저작물 존재를 알아내는
    // 수단이 된다. 403 과 404 가 같은 안내로 수렴하는지 함께 본다.
    mock.onGet('/portal/uploads/1').reply(404, ok(null));
    catchAllRest();

    const { unmount } = renderPage();
    const notFound = await screen.findByTestId('portal-upload-notice');
    expect(notFound).toHaveAttribute('data-notice-kind', 'detail-error');
    const notFoundText = notFound.textContent;
    expect(screen.queryByTestId('canvas-shell')).toBeNull();
    unmount();

    mock.resetHistory();
    mock.onGet('/portal/uploads/1').reply(403, ok(null));
    renderPage();
    const forbidden = await screen.findByTestId('portal-upload-notice');
    expect(forbidden.textContent).toBe(notFoundText);
  });

  // ── 메타 다섯 축 · 이벤트 어노테이션 ───────────────────────────────────
  /*
   * ★이 다섯 축은 **자산 출처를 가리지 않는다** — 본인이 올린 영상에도 촬영환경과 프레임 설명을
   *   붙일 수 있어야 한다(2026-09-02 확정). 저장처는 화면이 가르지 않고 서버가 자산 출처로
   *   판정하므로, 화면은 두 출처에서 **같은 창구**를 부른다.
   * ⚠ 그래서 이 창구의 접두(`/portal/frames/`)가 데이터마트 라벨 창구와 겹친다 — 위 창구 가드가
   *   그 접두 전체를 금지하지 않는 이유다.
   */
  it('★메타_탭이_업로드_자산에서도_열리고_그_자산의_식별자로_조회한다', async () => {
    mock.onGet('/portal/uploads/1').reply(200, ok(detail()));
    mock.onGet('/portal/uploads/frames/100/labels').reply(200, ok([]));
    mock.onGet('/portal/frames/100/meta').reply(200, ok({ items: [], readOnlyMeta: [], technicalMeta: [] }));
    mock.onGet('/portal/videos/1/event-annotation').reply(200, ok({ annotation: null }));
    catchAllRest();

    renderPage();
    await waitForCanvas();

    fireEvent.click(screen.getByTestId('right-tab-meta'));

    expect(await screen.findByTestId('portal-work-meta-tab')).toBeInTheDocument();
    // 프레임 축은 **그 자산의 프레임 PK**, 영상 축은 **자산 식별자**로 나간다(ADR-058).
    await waitFor(() =>
      expect(mock.history.get.some((r) => r.url === '/portal/frames/100/meta')).toBe(true),
    );
    await waitFor(() =>
      expect(
        mock.history.get.some((r) => r.url === '/portal/videos/1/event-annotation'),
      ).toBe(true),
    );
    // 내부 채널 메타 창구는 부르지 않는다 — 부르면 원장 쓰기·관제 통지가 일어나 단방향이 깨진다.
    const gets = mock.history.get.map((r) => r.url ?? '');
    expect(gets.filter((u) => /^\/frames\/\d+\/meta/.test(u))).toEqual([]);
    expect(gets.filter((u) => /^\/videos\/\d+\/event-annotation/.test(u))).toEqual([]);
  });

  it('잘못된_자산_주소는_안내로_막고_조회를_보내지_않는다', async () => {
    catchAllRest();

    renderPage('/portal/label/abc?source=upload');

    const notice = await screen.findByTestId('portal-upload-notice');
    expect(notice).toHaveAttribute('data-notice-kind', 'invalid');
    expect(notice).toHaveTextContent('잘못된 자산 주소입니다.');
    // 자산 축 창구를 아예 부르지 않는다(있지도 않은 자산을 조회하지 않는다).
    expect(mock.history.get.some((r) => (r.url ?? '').startsWith('/portal/uploads/'))).toBe(false);
  });
});
