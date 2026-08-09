// R1 — ReviewPage 가 issueThreads(미해소 문의)·프레임 라벨로 srcSn 집합을 산출해
// FrameTimeline 에 주입하고, 프레임 상태색(빨강/연두/현재)이 반영되는지 검증.
// v2 반려는 영상 단위(REJECTION.srcSn=null)라 프레임색(주황) 미대상 → 테스트 미포함.
// INQUIRY 산출 필터가 srcSn!=null && issueSttsCd!=='RESOLVED' 를 실제로 타는지 assert.

import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import MockAdapter from 'axios-mock-adapter';
import { screen, waitFor } from '@testing-library/react';

vi.mock('react-konva', async () => (await import('@/test/konvaMock')).createKonvaMock());

import { apiClient } from '@/lib/api/client';
import { renderWithProviders } from '@/test/renderWithProviders';
import { ReviewPage } from '@/pages/ReviewPage';
import { useReviewSelectionStore } from '@/features/review/store/useReviewSelectionStore';

const baseReview = {
  id: 10,
  videoId: 1,
  cctvName: 'CCTV-1',
  workerId: 7,
  workerName: '홍길동',
  submittedAt: '2026-05-07T10:00:00Z',
  labelCount: 12,
  status: 'REVIEWING',
};

const label = {
  id: 1,
  lblTypeCd: 'BBOX',
  label: '사람',
  points: [[0, 0], [10, 10]],
  autoLblYn: 'N',
  confScore: null,
};

beforeAll(() => {
  Element.prototype.scrollIntoView = vi.fn();
});

describe('ReviewPage R1 프레임 상태색 주입', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
    useReviewSelectionStore.getState().clear();
    // 현재 프레임 = idx3(반려/이슈 없는 프레임) — 다른 프레임의 색을 가리지 않게.
    useReviewSelectionStore.getState().setCurrentFrameIdx(3);

    mock.onGet('/reviews/10').reply(200, {
      success: true,
      data: baseReview,
      message: null,
      errorCode: null,
    });
    mock.onGet('/reviews/10/issues').reply(200, {
      success: true,
      data: [],
      message: null,
      errorCode: null,
    });
    // 프레임 4개 — idx2(srcSn 102) 에만 라벨 존재(saved).
    mock.onGet('/reviews/1/frames').reply(200, {
      success: true,
      data: {
        videoId: 1,
        totalFrames: 4,
        frames: [
          { srcSn: 100, frameNo: 0, imageUrl: '/f/100.jpg', labels: [] },
          { srcSn: 101, frameNo: 1, imageUrl: '/f/101.jpg', labels: [] },
          { srcSn: 102, frameNo: 2, imageUrl: '/f/102.jpg', labels: [label] },
          { srcSn: 103, frameNo: 3, imageUrl: '/f/103.jpg', labels: [] },
        ],
      },
      message: null,
      errorCode: null,
    });
    // 이슈 스레드 — v2 실데이터 경로:
    //  · 영상 단위 반려(REJECTION.srcSn=null) → 프레임색 미대상(주황 없음).
    //  · srcSn=null 문의 → 프레임 매핑 불가로 무색(null 필터 검증).
    //  · 미해소 문의(srcSn101) → 빨강.
    //  · 해소(RESOLVED) 문의(srcSn100, 비-현재 프레임) → 제외(stts 필터 검증).
    //    현재 프레임이 아닌 곳에 둬야 CURRENT 우선순위에 가려지지 않고 "RESOLVED면 무색"이 실검증됨.
    mock.onGet('/videos/1/issues').reply(200, {
      success: true,
      data: [
        {
          issueSn: 1,
          issueTypeCd: 'REJECTION',
          issueSttsCd: 'OPEN',
          srcSn: null,
          reason: '재작업 필요(영상 단위 반려)',
          reportedUserNo: null,
          regDt: '2026-05-07T10:00:00Z',
          comments: [],
        },
        {
          issueSn: 2,
          issueTypeCd: 'INQUIRY',
          issueSttsCd: 'OPEN',
          srcSn: null,
          reason: '영상 전반 문의(프레임 미지정)',
          reportedUserNo: null,
          regDt: '2026-05-07T10:00:30Z',
          comments: [],
        },
        {
          issueSn: 3,
          issueTypeCd: 'INQUIRY',
          issueSttsCd: 'OPEN',
          srcSn: 101,
          reason: '이 객체 맞나요',
          reportedUserNo: null,
          regDt: '2026-05-07T10:01:00Z',
          comments: [],
        },
        {
          issueSn: 4,
          issueTypeCd: 'INQUIRY',
          issueSttsCd: 'RESOLVED',
          srcSn: 100,
          reason: '해소된 문의',
          reportedUserNo: null,
          regDt: '2026-05-07T10:02:00Z',
          comments: [],
        },
      ],
      message: null,
      errorCode: null,
    });
  });

  afterEach(() => {
    mock.restore();
  });

  it('ReviewPage가_미해소문의_srcSn집합을_산출해_FrameTimeline에_전달', async () => {
    renderWithProviders(<ReviewPage />, {
      initialEntries: ['/review/10'],
      routes: [{ path: '/review/:id', element: <ReviewPage /> }],
    });

    // 프레임 타임라인 렌더 대기.
    const thumb1 = await screen.findByTestId('frame-timeline-thumb-1');

    await waitFor(() => {
      // 미해소 문의(srcSn101) → 빨강.
      expect(thumb1.className).toContain('border-red-500');
    });
    // idx0(srcSn100): srcSn=null 문의/영상단위 반려 + RESOLVED 문의(srcSn100) 모두 미착색 → 기본.
    // RESOLVED 문의를 비-현재 프레임에 배치했으므로, stts 필터가 깨지면 여기서 빨강이 떠 실패한다.
    const thumb0 = screen.getByTestId('frame-timeline-thumb-0').className;
    expect(thumb0).toContain('border-transparent');
    expect(thumb0).not.toContain('border-red-500');
    expect(thumb0).not.toContain('border-orange-500');
    // 라벨 저장(srcSn102) → 연두.
    expect(screen.getByTestId('frame-timeline-thumb-2').className).toContain(
      'border-green-400',
    );
    // 현재 프레임(idx3, 해소 문의는 미표시) → 현재 강조.
    expect(screen.getByTestId('frame-timeline-thumb-3').className).toContain(
      'border-primary-500',
    );
  });
});
