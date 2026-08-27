import MockAdapter from 'axios-mock-adapter';
import { screen, waitFor, within } from '@testing-library/react';
import { Route, Routes } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';

import { apiClient } from '@/lib/api/client';
import { VideoDetailPage } from '@/pages/VideoDetailPage';
import { renderWithProviders } from '@/test/renderWithProviders';

/**
 * R14 — 영상 상세 「기본 정보」 탭의 비식별 이력.
 *
 * 이력 원천은 BE `VideoDetailResponse.deidentHistory`(= `LS_DEIDENT_PROC_LOG` 회차 행)이며,
 * 검출 집계는 외부 비식별 솔루션의 처리 결과 리포트에서 온다.
 */
describe('영상 상세 — 비식별 이력', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
  });

  afterEach(() => {
    mock.restore();
  });

  const baseDetail = {
    id: 42,
    cctvName: '강남대로 CCTV',
    vmsClipId: 'VMS-42',
    eventName: '낙상',
    eventTypeCd: 'FALL',
    localGov: '강남구',
    frameCount: 900,
    status: 'COMPLETED',
    capturedAt: '2026-05-01T12:00:00Z',
    duration: 30,
    fileSizeMb: 10,
    resolution: '1920x1080',
    framePreviews: [],
  };

  function replyWith(deidentHistory: unknown) {
    mock.onGet('/videos/42').reply(200, {
      success: true,
      data: { ...baseDetail, deidentHistory },
      message: null,
      errorCode: null,
    });
  }

  /**
   * 이력 항목 조회는 **패널 안으로 좁힌다**. [@design SCREEN-009]
   *
   * 이 화면에는 비식별 이력 말고도 `<li>` 를 쓰는 영역이 있어(배치 조치·프레임 목록 등) 페이지
   * 전역에서 `getByRole('listitem')` 을 찾으면 다중 매칭으로 깨진다. 그때 개수 단언을 느슨하게
   * 바꾸면 "몇 회차가 뜨는가" 라는 이 파일의 검증 축이 사라지므로, **찾는 범위만** 좁힌다.
   */
  function historyPanel(): HTMLElement {
    return screen.getByTestId('deident-history-panel');
  }

  function historyItems(): HTMLElement[] {
    return within(historyPanel()).getAllByRole('listitem');
  }

  function historyItem(): HTMLElement {
    return within(historyPanel()).getByRole('listitem');
  }

  function renderPage() {
    renderWithProviders(
      <Routes>
        <Route path="/video/:id" element={<VideoDetailPage />} />
      </Routes>,
      { initialEntries: ['/video/42'] },
    );
  }

  it('회차별_검출집계와_처리시각을_보여준다', async () => {
    replyWith([
      {
        procLogSn: 2,
        procSttsCd: 'SUCCEEDED',
        reqKndCd: 'REDEIDENT',
        reqDt: '2026-08-11T10:00:00',
        resDt: '2026-08-11T10:06:00',
        faceDtctCnt: 12,
        noPltDtctCnt: 3,
        frmeCnt: 5400,
        prcsBgngDt: '2026-08-11T10:01:00',
        prcsEndDt: '2026-08-11T10:05:30',
      },
    ]);

    renderPage();

    await waitFor(() => expect(screen.getByText('비식별 이력')).toBeInTheDocument());
    const item = historyItem();
    expect(within(item).getByText('재비식별')).toBeInTheDocument();
    expect(within(item).getByText('완료')).toBeInTheDocument();
    expect(within(item).getByText('얼굴 검출')).toBeInTheDocument();
    expect(within(item).getByText('12')).toBeInTheDocument();
    expect(within(item).getByText('3')).toBeInTheDocument();
    expect(within(item).getByText('5,400')).toBeInTheDocument();
    expect(within(item).getByText(/2026-08-11 10:01.*2026-08-11 10:05/)).toBeInTheDocument();
  });

  it('여러_회차를_받은_순서대로_모두_보여준다', async () => {
    // BE 가 최신순으로 내려준다 — FE 는 재정렬하지 않는다(정렬 규칙 이중화 금지).
    replyWith([
      { procLogSn: 3, procSttsCd: 'SUCCEEDED', reqKndCd: 'REDEIDENT', reqDt: '2026-08-11T10:00:00' },
      { procLogSn: 1, procSttsCd: 'FAILED', reqKndCd: null, reqDt: '2026-08-10T09:00:00' },
    ]);

    renderPage();

    await waitFor(() => expect(screen.getByText('비식별 이력')).toBeInTheDocument());
    const items = historyItems();
    expect(items).toHaveLength(2);
    expect(within(items[0]).getByText('재비식별')).toBeInTheDocument();
    expect(within(items[1]).getByText('비식별')).toBeInTheDocument();
    expect(within(items[1]).getByText('실패')).toBeInTheDocument();
  });

  it('검출집계가_없는_회차는_집계를_0으로_지어내지_않고_감춘다', async () => {
    // 리포트 조회 실패/구 데이터 — 0 으로 채우면 "0건 검출" 과 구분되지 않는다.
    replyWith([
      {
        procLogSn: 1,
        procSttsCd: 'SUCCEEDED',
        reqKndCd: null,
        reqDt: '2026-08-10T09:00:00',
        faceDtctCnt: null,
        noPltDtctCnt: null,
        frmeCnt: null,
        prcsBgngDt: null,
        prcsEndDt: null,
      },
    ]);

    renderPage();

    await waitFor(() => expect(screen.getByText('비식별 이력')).toBeInTheDocument());
    expect(screen.queryByText('얼굴 검출')).not.toBeInTheDocument();
    expect(screen.queryByText('총 프레임')).not.toBeInTheDocument();
  });

  it('★진행_중_회차는_주황이_아니라_정보색_배지다_SD_004', async () => {
    // [@design SD-004] '진행 중'은 info 계열(`--i-0` 배경 / `--i-7` 글자)이다. 구 구현은
    // warning(주황)이라, 아무 문제도 일어나지 않은 진행 상태를 사용자가 "조치가 필요한
    // 상태"로 읽었다. 완료=success · 실패=danger 는 이 변경 대상이 아니다.
    replyWith([
      { procLogSn: 5, procSttsCd: 'REQUESTED', reqKndCd: null, reqDt: '2026-08-12T09:00:00' },
    ]);

    renderPage();

    await waitFor(() => expect(screen.getByText('비식별 이력')).toBeInTheDocument());
    const badge = within(historyItem()).getByText('진행 중');
    expect(badge.className).toContain('bg-info/10');
    // 글자는 DEFAULT 가 아니라 700 단이어야 한다 — bg-info/10 위 대비(AA) 때문이며
    // src/test/contrastGuard.test.ts 가 tailwind 토큰 실값으로 그 사실을 계산해 고정한다.
    expect(badge.className).toContain('text-info-700');
    // 되돌림 차단 — warning 계열이면 실패한다.
    expect(badge.className).not.toContain('warning');
  });

  it('알_수_없는_상태코드도_진행_중_정보색으로_폴백한다', async () => {
    // statusClass 의 폴백이 REQUESTED 를 가리키므로 색 축이 함께 움직여야 한다.
    replyWith([
      { procLogSn: 6, procSttsCd: 'WHO_KNOWS', reqKndCd: null, reqDt: '2026-08-12T09:00:00' },
    ]);

    renderPage();

    await waitFor(() => expect(screen.getByText('비식별 이력')).toBeInTheDocument());
    const badge = within(historyItem()).getByText('진행 중');
    expect(badge.className).toContain('bg-info/10');
    expect(badge.className).not.toContain('warning');
  });

  it('★세_상태_배지가_한_규칙이다_틴트배경_플러스_700단_글자', async () => {
    // 한 파일 안에서 700단과 DEFAULT 가 섞이지 않게 한다 — 각 색의 DEFAULT 단은 자기 `/10`
    // 틴트 위에서 AA(4.5) 미달이다(success 4.03 · danger 3.95 · info 4.05).
    // 어느 하나라도 DEFAULT 로 되돌리면 이 케이스가 잡는다.
    replyWith([
      { procLogSn: 8, procSttsCd: 'SUCCEEDED', reqKndCd: null, reqDt: '2026-08-12T10:00:00' },
      { procLogSn: 7, procSttsCd: 'FAILED', reqKndCd: null, reqDt: '2026-08-12T09:00:00' },
      { procLogSn: 6, procSttsCd: 'REQUESTED', reqKndCd: null, reqDt: '2026-08-12T08:00:00' },
    ]);

    renderPage();

    await waitFor(() => expect(screen.getByText('비식별 이력')).toBeInTheDocument());
    const items = historyItems();
    const badges = [
      { el: within(items[0]).getByText('완료'), color: 'success' },
      { el: within(items[1]).getByText('실패'), color: 'danger' },
      { el: within(items[2]).getByText('진행 중'), color: 'info' },
    ];

    for (const { el, color } of badges) {
      expect(el.className, `${color} 배지 배경`).toContain(`bg-${color}/10`);
      expect(el.className, `${color} 배지 글자`).toContain(`text-${color}-700`);
      // DEFAULT 단으로 되돌리면(`text-success` 등) 위 700단 단언이 실패한다. 아래는 그 되돌림이
      // 뒤에 숫자 없는 형태로 남는 것까지 못 박는다.
      expect(el.className).not.toMatch(new RegExp(`text-${color}(?![-\\d])`));
    }
  });

  it('★세_상태가_서로_다른_색_계열로_구분된다_한_계열로_뭉개지면_실패', async () => {
    // 700 단으로 올리면서 세 상태가 같은 색이 되어 버리면 상태 구분이 사라진다.
    replyWith([
      { procLogSn: 8, procSttsCd: 'SUCCEEDED', reqKndCd: null, reqDt: '2026-08-12T10:00:00' },
      { procLogSn: 7, procSttsCd: 'FAILED', reqKndCd: null, reqDt: '2026-08-12T09:00:00' },
      { procLogSn: 6, procSttsCd: 'REQUESTED', reqKndCd: null, reqDt: '2026-08-12T08:00:00' },
    ]);

    renderPage();

    await waitFor(() => expect(screen.getByText('비식별 이력')).toBeInTheDocument());
    const items = historyItems();
    const classNames = [
      within(items[0]).getByText('완료').className,
      within(items[1]).getByText('실패').className,
      within(items[2]).getByText('진행 중').className,
    ];
    expect(new Set(classNames).size).toBe(3);
    // 셋 중 어느 것도 다른 상태의 계열을 쓰지 않는다.
    expect(classNames[0]).not.toMatch(/danger|info|warning/);
    expect(classNames[1]).not.toMatch(/success|info|warning/);
    expect(classNames[2]).not.toMatch(/success|danger|warning/);
  });

  it('이력이_없으면_안내문구를_보여준다', async () => {
    replyWith([]);

    renderPage();

    await waitFor(() => expect(screen.getByText('비식별 이력')).toBeInTheDocument());
    expect(screen.getByText('비식별 이력이 없습니다.')).toBeInTheDocument();
  });

  it('구_응답처럼_이력_필드가_없어도_화면이_깨지지_않는다', async () => {
    // 하위호환 — BE 배포 전 응답(필드 없음)에서도 상세 화면이 정상 렌더돼야 한다.
    mock.onGet('/videos/42').reply(200, {
      success: true,
      data: baseDetail,
      message: null,
      errorCode: null,
    });

    renderPage();

    await waitFor(() => expect(screen.getByText('강남대로 CCTV')).toBeInTheDocument());
    expect(screen.getByText('비식별 이력이 없습니다.')).toBeInTheDocument();
  });
});
