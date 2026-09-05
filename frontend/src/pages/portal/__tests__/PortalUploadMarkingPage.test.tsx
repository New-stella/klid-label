/**
 * 포털 업로드 영상 마킹 화면 회귀 가드. [@design SCREEN-045] [@design API-240] [@design NAV-002]
 *
 * 이 파일이 고정하는 계약:
 *  1. **확인 단계를 거치지 않으면 저장이 나가지 않는다** — 저장이 곧 추출의 시작이고 되돌릴 수 없다.
 *  2. 자동이 기본이고 간격 기본값은 300 프레임이며, 그 설정으로 뽑힐 지점이 저장 전에 보인다.
 *  3. 수동인데 지점이 0건이면 확인 단계로 넘어가지 않고 안내만 띄운다(버튼을 죽이지 않는다).
 *  4. 진입 차단 세 축(소유 · 업로드 완료 · 자산 종류)이 **각각 다른 문구**로 편집기를 대신한다.
 *     상세를 받는 중이면 어느 축도 차단하지 않는다.
 *  5. 이미 저장한 자산은 편집기를 그대로 그리되 **저장만** 막고, 잠긴 사유와 회복 경로를 알린다.
 *  6. 저장 응답이 절단을 알리면 그 사실이 화면에 남는다(상한을 미리 알 수 없으므로 이 자리가 필요하다).
 *  7. 이 화면에는 **이동 탭이 뜨지 않는다** — 목적지가 아니라 목록에서 들어가는 몰입 편집 화면이다.
 */
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import MockAdapter from 'axios-mock-adapter';

import { PortalContentTabs } from '@/components/layout/PortalContentTabs';
import { apiClient } from '@/lib/api/client';
import { renderWithProviders } from '@/test/renderWithProviders';
import { useUiStore } from '@/stores/useUiStore';

import { PortalUploadMarkingPage } from '../PortalUploadMarkingPage';

const ULD_SN = 501;
const MARKING_PATH = `/portal/uploads/${ULD_SN}/marking`;

function ok(data: unknown) {
  return { success: true, data, message: null, errorCode: null };
}

function fail(message: string, errorCode: string) {
  return { success: false, data: null, message, errorCode };
}

/** 길이 10초 · 30fps → 총 300프레임. 간격 300 이면 지점 1건(0프레임)이다. */
function detail(overrides: Record<string, unknown> = {}) {
  return {
    uldSn: ULD_SN,
    uldTypeCd: 'VIDEO',
    orgnlFileNm: 'my-clip.mp4',
    fileSz: 1024,
    mimeTypeNm: 'video/mp4',
    uldSttsCd: 'UPLOADED',
    frmeCnt: null,
    vdoLenSec: 10,
    fps: 30,
    regDt: '2026-09-02T00:00:00',
    mdfcnDt: null,
    expiresAt: null,
    frames: [],
    ...overrides,
  };
}

function renderPage() {
  return renderWithProviders(<div data-testid="no-match" />, {
    initialEntries: [MARKING_PATH],
    routes: [
      {
        path: '/portal/uploads/:uldSn/marking',
        element: (
          <>
            <PortalContentTabs />
            <PortalUploadMarkingPage />
          </>
        ),
      },
    ],
  });
}

describe('포털 업로드 영상 마킹 화면', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
    useUiStore.setState({ toasts: [] });
    mock.onGet(`/portal/uploads/${ULD_SN}`).reply(200, ok(detail()));
    mock
      .onGet(`/portal/uploads/${ULD_SN}/stream-url`)
      .reply(200, ok({ url: `/api/v1/portal/uploads/${ULD_SN}/stream?sig=x`, expiresAt: 1, ttlSeconds: 120 }));
    mock.onGet(`/portal/uploads/${ULD_SN}/markings`).reply(200, ok({ uldSn: ULD_SN, markings: [] }));
  });
  afterEach(() => mock.restore());

  const toastMessages = () => useUiStore.getState().toasts.map((t) => t.message);

  // ── 1. 확인 단계 ─────────────────────────────────────────────────────────
  it('★확인_단계를_거치지_않으면_저장이_나가지_않는다', async () => {
    const user = userEvent.setup();
    let posted = 0;
    mock.onPost(`/portal/uploads/${ULD_SN}/markings`).reply(() => {
      posted += 1;
      return [201, ok(saveResult())];
    });
    renderPage();
    await screen.findByText('my-clip.mp4');

    await user.click(screen.getByTestId('marking-complete-button'));

    // 완료를 눌렀을 뿐인데 저장이 나가면 되돌릴 수 없는 조작이 확인 없이 확정된다.
    expect(posted).toBe(0);
    expect(screen.getByRole('dialog')).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: '완료하고 추출 시작' }));

    await waitFor(() => expect(posted).toBe(1));
  });

  it('★확인_창에서_취소하면_아무것도_저장하지_않고_마킹을_이어_할_수_있다', async () => {
    const user = userEvent.setup();
    let posted = 0;
    mock.onPost(`/portal/uploads/${ULD_SN}/markings`).reply(() => {
      posted += 1;
      return [201, ok(saveResult())];
    });
    renderPage();
    await screen.findByText('my-clip.mp4');

    await user.click(screen.getByTestId('marking-complete-button'));
    await user.click(screen.getByRole('button', { name: '취소' }));

    expect(posted).toBe(0);
    expect(screen.queryByRole('dialog')).toBeNull();
    // 편집기가 그대로 살아 있다.
    expect(screen.getByTestId('marking-complete-button')).toBeEnabled();
  });

  it('저장은_요청_본문을_방식에_맞게_싣는다', async () => {
    const user = userEvent.setup();
    let sent: unknown = null;
    mock.onPost(`/portal/uploads/${ULD_SN}/markings`).reply((config) => {
      sent = JSON.parse(config.data as string);
      return [201, ok(saveResult())];
    });
    renderPage();
    await screen.findByText('my-clip.mp4');

    await user.click(screen.getByTestId('marking-complete-button'));
    await user.click(screen.getByRole('button', { name: '완료하고 추출 시작' }));

    await waitFor(() => expect(sent).toEqual({ mode: 'AUTO', interval: 300 }));
  });

  // ── 2. 자동이 기본 ────────────────────────────────────────────────────────
  it('자동이_기본이고_간격_기본값은_300프레임이며_뽑힐_지점이_저장_전에_보인다', async () => {
    renderPage();
    await screen.findByText('my-clip.mp4');

    expect(screen.getByRole('button', { name: '자동' })).toHaveAttribute('aria-pressed', 'true');
    expect(screen.getByLabelText('간격(프레임)')).toHaveValue(300);
    // 10초 · 30fps → 총 300프레임 · 간격 300 → 0프레임 한 지점.
    expect(screen.getByTestId('marking-plan-summary')).toHaveTextContent('뽑힐 프레임 1장');
    expect(screen.getByTestId('marking-plan-summary')).toHaveTextContent('약 10.0초');
    expect(screen.getByText(/F0/)).toBeInTheDocument();
  });

  it('간격을_바꾸면_뽑힐_지점이_곧바로_반영된다', async () => {
    renderPage();
    await screen.findByText('my-clip.mp4');

    // 한 글자씩 치면 중간값(1 → 11 → 110 …)이 그대로 설정에 반영되므로 값을 한 번에 바꾼다.
    fireEvent.change(screen.getByLabelText('간격(프레임)'), { target: { value: '100' } });

    // 총 300프레임 · 간격 100 → 0 / 100 / 200 세 지점.
    await waitFor(() =>
      expect(screen.getByTestId('marking-plan-summary')).toHaveTextContent('뽑힐 프레임 3장'),
    );
  });

  // ── 3. 수동 0건 ───────────────────────────────────────────────────────────
  it('★수동인데_지점이_0건이면_확인_창을_열지_않고_안내만_띄운다', async () => {
    const user = userEvent.setup();
    renderPage();
    await screen.findByText('my-clip.mp4');

    await user.click(screen.getByRole('button', { name: '수동' }));
    await user.click(screen.getByTestId('marking-complete-button'));

    expect(screen.queryByRole('dialog')).toBeNull();
    // 조용히 아무 일도 없으면 «왜 눌리지 않는가» 를 화면이 말해 주지 못한다.
    expect(toastMessages().join(' ')).toContain('지점을 1건 이상');
  });

  // ── 4. 진입 차단 세 축 ────────────────────────────────────────────────────
  it('★상세를_받는_중에는_어느_축도_차단하지_않는다', async () => {
    // 상세 응답을 붙잡아 둔다 — 확정되지 않은 값으로 차단하면 깜빡임이 정상 마킹을 막는다.
    const held = { release: () => {} };
    mock.onGet(`/portal/uploads/${ULD_SN}`).reply(
      () =>
        new Promise((resolve) => {
          held.release = () => resolve([200, ok(detail())]);
        }),
    );
    renderPage();

    await waitFor(() => expect(screen.queryByTestId('marking-blocked-forbidden')).toBeNull());
    expect(screen.queryByTestId('marking-blocked-not-video')).toBeNull();
    expect(screen.queryByTestId('marking-blocked-not-uploaded')).toBeNull();

    held.release();
    await screen.findByText('my-clip.mp4');
  });

  it('★소유_축 — 남의_자산과_없는_자산을_가르지_않고_같은_안내를_보여_준다', async () => {
    mock
      .onGet(`/portal/uploads/${ULD_SN}`)
      .reply(403, fail('본인 자산이 아니거나 존재하지 않습니다.', 'FORBIDDEN'));
    renderPage();

    const notice = await screen.findByTestId('marking-blocked-forbidden');
    expect(notice).toHaveTextContent('자산을 불러올 수 없습니다.');
    // 편집기를 그리지 않는다.
    expect(screen.queryByTestId('marking-complete-button')).toBeNull();
  });

  it('★업로드_축 — 재생할_파일이_아직_없으면_업로드가_끝나야_한다고_알린다', async () => {
    mock
      .onGet(`/portal/uploads/${ULD_SN}/stream-url`)
      .reply(404, fail('재생할 파일이 아직 없습니다.', 'NOT_FOUND'));
    renderPage();

    const notice = await screen.findByTestId('marking-blocked-not-uploaded');
    expect(notice).toHaveTextContent('아직 다 올라오지 않은 영상입니다.');
    expect(screen.queryByTestId('marking-complete-button')).toBeNull();
  });

  it('★자산_종류_축 — 영상이_아니면_다른_문구로_차단한다', async () => {
    mock.onGet(`/portal/uploads/${ULD_SN}`).reply(200, ok(detail({ uldTypeCd: 'IMAGE', vdoLenSec: null, fps: null })));
    renderPage();

    const notice = await screen.findByTestId('marking-blocked-not-video');
    expect(notice).toHaveTextContent('영상 자산만 이벤트 구간을 마킹할 수 있습니다.');
    // 세 축은 결과가 같아도 사유가 달라 문구를 하나로 합치지 않는다.
    expect(screen.queryByTestId('marking-blocked-forbidden')).toBeNull();
    expect(screen.queryByTestId('marking-blocked-not-uploaded')).toBeNull();
  });

  // ── 5. 이미 저장한 자산 ───────────────────────────────────────────────────
  it('★이미_저장한_자산은_편집기를_그리되_저장만_막고_저장된_지점을_보여_준다', async () => {
    mock.onGet(`/portal/uploads/${ULD_SN}`).reply(200, ok(detail({ uldSttsCd: 'PROCESSING' })));
    mock.onGet(`/portal/uploads/${ULD_SN}/markings`).reply(
      200,
      ok({
        uldSn: ULD_SN,
        markings: [
          {
            markingSn: 7001,
            mode: 'MANUAL',
            interval: null,
            marks: [{ frameIndex: 30, timestamp: '00:01' }],
            markCount: 1,
            regDt: '2026-09-02T10:00:00',
          },
        ],
      }),
    );
    renderPage();

    // 편집기는 그대로 그린다 — 저장된 지점을 확인할 수 있어야 한다.
    await screen.findByTestId('marking-locked-notice');
    const complete = screen.getByTestId('marking-complete-button');
    expect(complete).toBeDisabled();
    expect(complete).toHaveTextContent('다시 저장할 수 없음');
    expect(await screen.findByText(/F30/)).toBeInTheDocument();
    // 저장된 지점은 지울 수 없다.
    expect(screen.queryByRole('button', { name: /마킹 삭제/ })).toBeNull();
  });

  it('★추출_중과_추출_완료의_회복_안내가_다르다', async () => {
    mock.onGet(`/portal/uploads/${ULD_SN}`).reply(200, ok(detail({ uldSttsCd: 'PROCESSING' })));
    const first = renderPage();
    let notice = await screen.findByTestId('marking-locked-notice');
    // 추출 중에는 지울 수도 없다 — 기다리라고 알린다.
    expect(notice).toHaveTextContent('추출이 진행 중인 동안에는 이 자산을 지울 수 없으니');
    expect(within(notice).queryByRole('link', { name: '내 업로드' })).toBeNull();
    first.unmount();

    mock.onGet(`/portal/uploads/${ULD_SN}`).reply(200, ok(detail({ uldSttsCd: 'READY' })));
    renderPage();
    notice = await screen.findByTestId('marking-locked-notice');
    // 추출이 끝났으면 지우고 다시 올리면 된다고 알리며 업로드 화면으로 이끈다.
    expect(notice).toHaveTextContent('지우고 다시 올리면 새로 마킹할 수 있습니다.');
    expect(within(notice).getByRole('link', { name: '내 업로드' })).toHaveAttribute(
      'href',
      '/portal/uploads',
    );
  });

  // ── 6. 절단 사실 ──────────────────────────────────────────────────────────
  it('★저장_응답이_절단을_알리면_그_사실이_화면에_남는다', async () => {
    const user = userEvent.setup();
    mock
      .onPost(`/portal/uploads/${ULD_SN}/markings`)
      .reply(201, ok(saveResult({ truncated: true, requestedMarkCount: 2500, markCount: 2000 })));
    renderPage();
    await screen.findByText('my-clip.mp4');

    await user.click(screen.getByTestId('marking-complete-button'));
    await user.click(screen.getByRole('button', { name: '완료하고 추출 시작' }));

    const notice = await screen.findByTestId('marking-truncated-result');
    // 토스트는 사라지므로 화면에 남는 자리가 따로 있어야 한다.
    expect(notice).toHaveTextContent('요청한 2500건 가운데 2000건으로 프레임을 뽑습니다.');
  });

  it('절단이_아니면_그_안내를_띄우지_않는다', async () => {
    const user = userEvent.setup();
    mock.onPost(`/portal/uploads/${ULD_SN}/markings`).reply(201, ok(saveResult()));
    renderPage();
    await screen.findByText('my-clip.mp4');

    await user.click(screen.getByTestId('marking-complete-button'));
    await user.click(screen.getByRole('button', { name: '완료하고 추출 시작' }));

    await waitFor(() => expect(toastMessages().join(' ')).toContain('프레임 1장을 뽑기 시작합니다'));
    expect(screen.queryByTestId('marking-truncated-result')).toBeNull();
  });

  it('저장이_거절되면_사유를_그대로_알리고_확인_창을_닫는다', async () => {
    const user = userEvent.setup();
    mock
      .onPost(`/portal/uploads/${ULD_SN}/markings`)
      .reply(409, fail('이미 마킹을 저장한 영상입니다. 다시 마킹하려면 이 자산을 지우고 다시 올려 주세요.', 'CONFLICT'));
    renderPage();
    await screen.findByText('my-clip.mp4');

    await user.click(screen.getByTestId('marking-complete-button'));
    await user.click(screen.getByRole('button', { name: '완료하고 추출 시작' }));

    await waitFor(() =>
      expect(toastMessages().join(' ')).toContain('지우고 다시 올려 주세요'),
    );
    expect(screen.queryByRole('dialog')).toBeNull();
  });

  // ── 7. 이동 탭 ────────────────────────────────────────────────────────────
  it('★이_화면에는_이동_탭이_뜨지_않는다', async () => {
    renderPage();
    await screen.findByText('my-clip.mp4');

    // 목적지가 아니라 목록에서 들어가는 몰입 편집 화면이다 — 탭 노출은 허용 목록이 판정한다.
    expect(screen.queryByRole('navigation', { name: '포털 이동 탭' })).toBeNull();
  });
});

function saveResult(overrides: Record<string, unknown> = {}) {
  return {
    markingSn: 7001,
    uldSn: ULD_SN,
    mode: 'AUTO',
    interval: 300,
    marks: [{ frameIndex: 0, timestamp: '00:00' }],
    markCount: 1,
    requestedMarkCount: 1,
    truncated: false,
    uldSttsCd: 'PROCESSING',
    regDt: '2026-09-02T10:00:00',
    ...overrides,
  };
}
