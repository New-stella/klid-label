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
    const item = screen.getByRole('listitem');
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
    const items = screen.getAllByRole('listitem');
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
