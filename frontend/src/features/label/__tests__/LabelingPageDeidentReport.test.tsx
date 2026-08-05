// LabelingPage — 비식별 누락 신고 버튼 통합 + 잠금 영상 UI 검증.
//
// 시나리오:
//  - WORKER + DEID 프레임: 헤더에 [비식별 누락 신고] 버튼 노출
//  - lockSttsCd=LOCKED_FOR_REDEIDENT: 상단 배너 표시 + 저장 버튼 비활성 + 신고 버튼 비활성
//  - frameImageType=RAW (REVIEWER 가 원본 보기): 신고 버튼 비활성
//  - PORTAL 모드: 신고 버튼 미노출

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import MockAdapter from 'axios-mock-adapter';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

vi.mock('react-konva', () => {
  // eslint-disable-next-line @typescript-eslint/no-var-requires
  const React = require('react');
  const passthrough = (name: string) => {
    return ({ children, ...rest }: any) =>
      // eslint-disable-next-line react/no-children-prop
      React.createElement('div', { 'data-konva': name, ...rest }, children);
  };
  return {
    Stage: passthrough('Stage'),
    Layer: passthrough('Layer'),
    Image: passthrough('Image'),
    Rect: passthrough('Rect'),
    Line: passthrough('Line'),
    Circle: passthrough('Circle'),
    Group: passthrough('Group'),
  };
});

import { apiClient } from '@/lib/api/client';
import { LabelingPage } from '@/pages/label/LabelingPage';
import { renderWithProviders } from '@/test/renderWithProviders';
import { useAuthStore } from '@/stores/useAuthStore';
import { useLabelStore } from '@/stores/useLabelStore';

function labelItem(srcSn: number, n: number) {
  return {
    id: `srv-${srcSn}-${n}`,
    serverId: n,
    frameNo: 0,
    classId: 1,
    className: 'PERSON',
    source: 'MANUAL',
    shape: { type: 'BBOX', left: 10, top: 10, right: 50, bottom: 80 },
  };
}

function labelsPayload(
  srcSn: number,
  opts: { frameImageType?: string; lockSttsCd?: string | null; labelCount?: number } = {},
) {
  const labelCount = opts.labelCount ?? 0;
  return {
    success: true,
    data: {
      frameNo: 0,
      srcSn,
      videoId: 7,
      frameImageType: opts.frameImageType ?? 'DEID',
      lockSttsCd: opts.lockSttsCd ?? null,
      siblings: [{ srcSn, frameNo: 0 }],
      labels: Array.from({ length: labelCount }, (_, i) => labelItem(srcSn, i + 1)),
    },
    message: null,
    errorCode: null,
  };
}

/**
 * 신고 게이트 412 응답 본문 — BE 실제 계약을 그대로 옮긴 것이다(지어낸 값 아님).
 *  - errorCode : `ErrorCode.PRECONDITION_FAILED`(HttpStatus.PRECONDITION_FAILED)
 *                → `ApiResponse.error(code, msg)` 가 `code.name()` 을 싣는다.
 *  - message   : `LabelAccessGuard.requireNotUnderDeidentReport` 의 <행위 중립> 문구.
 *                조회와 쓰기(개인정보 선언 PUT · 라벨 저장)를 함께 막으므로 "조회할 수 없습니다"가 아니다.
 * 출처: backend `label/service/LabelAccessGuard.java:154-155` · `common/exception/ErrorCode.java:27`
 *      · `common/response/ApiResponse.java`(success/data/message/errorCode)
 */
const DEIDENT_GATE_ERROR_BODY = {
  success: false,
  data: null,
  message: '비식별 재처리 대기 중인 영상입니다. 재비식별 완료 후 다시 시도해 주세요.',
  errorCode: 'PRECONDITION_FAILED',
} as const;

describe('LabelingPage 비식별 누락 신고 통합', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
    useAuthStore.setState({
      token: 'tok',
      claims: { sub: '10', role: 'WORKER', channel: 'INTERNAL', exp: 9999999999 },
    });
    mock.onGet('/frames/300/image').reply(200, new Blob());
    mock.onGet('/frames/301/image').reply(200, new Blob());
    mock.onGet('/frames/302/image').reply(200, new Blob());
  });

  afterEach(() => {
    mock.restore();
    useAuthStore.getState().clear();
    useLabelStore.getState().reset();
  });

  it('WORKER_+_DEID_프레임에서_비식별_누락_신고_버튼_노출', async () => {
    mock.onGet('/frames/300/labels').reply(200, labelsPayload(300, { frameImageType: 'DEID' }));

    renderWithProviders(<LabelingPage />, {
      initialEntries: ['/label/300'],
      routes: [{ path: '/label/:id', element: <LabelingPage /> }],
    });

    await waitFor(() => expect(screen.getByTestId('labeling-page')).toBeInTheDocument());
    await waitFor(() => {
      expect(
        screen.getByRole('button', { name: /비식별 누락 신고/ }),
      ).toBeInTheDocument();
    });
  });

  it('lockSttsCd_LOCKED_FOR_REDEIDENT_시_배너_표시_+_저장_버튼_비활성', async () => {
    mock
      .onGet('/frames/301/labels')
      .reply(200, labelsPayload(301, { lockSttsCd: 'LOCKED_FOR_REDEIDENT' }));

    renderWithProviders(<LabelingPage />, {
      initialEntries: ['/label/301'],
      routes: [{ path: '/label/:id', element: <LabelingPage /> }],
    });

    // 배너 — '비식별 재처리 중' 안내 (라벨 응답 도착 후 렌더)
    await waitFor(() => {
      expect(screen.getByTestId('deident-locked-banner')).toBeInTheDocument();
    });
    expect(screen.getByTestId('deident-locked-banner')).toHaveTextContent(
      /비식별 재처리 중/,
    );

    // 헤더 저장 버튼 비활성 (DarkToolbar 의 저장 버튼과 구분 — testid 사용)
    const saveBtn = screen.getByTestId('label-header-save');
    expect(saveBtn).toBeDisabled();

    // 신고 버튼 비활성 (이미 잠금)
    const reportBtn = screen.getByRole('button', { name: /비식별 누락 신고/ });
    expect(reportBtn).toBeDisabled();
  });

  it('lockSttsCd_정상상태_시_배너_미표시_+_저장_버튼_활성', async () => {
    mock.onGet('/frames/302/labels').reply(200, labelsPayload(302, { lockSttsCd: null }));

    renderWithProviders(<LabelingPage />, {
      initialEntries: ['/label/302'],
      routes: [{ path: '/label/:id', element: <LabelingPage /> }],
    });

    // 헤더 렌더 대기 — 저장 버튼이 나타나야 데이터 로딩 완료
    const saveBtn = await screen.findByTestId('label-header-save');
    expect(screen.queryByTestId('deident-locked-banner')).not.toBeInTheDocument();
    expect(saveBtn).not.toBeDisabled();
  });

  it('PORTAL_모드_시_비식별_누락_신고_버튼_미노출', async () => {
    useAuthStore.setState({
      token: 'tok',
      claims: { sub: '10', role: 'PORTAL_USER', channel: 'PORTAL', exp: 9999999999 },
    });
    mock.onGet('/frames/300/labels').reply(200, labelsPayload(300));

    renderWithProviders(<LabelingPage />, {
      initialEntries: ['/label/300'],
      routes: [{ path: '/label/:id', element: <LabelingPage /> }],
    });

    await waitFor(() => expect(screen.getByTestId('labeling-page')).toBeInTheDocument());
    expect(screen.queryByRole('button', { name: /비식별 누락 신고/ })).toBeNull();
  });

  it('비식별_신고_성공_시_객체목록_즉시_0건_+_재조회는_412로_라벨조회_실패안내', async () => {
    const user = userEvent.setup();

    // 1차 조회: 라벨 3건 존재.
    // 신고 후 재조회(invalidate)는 **412** — BE 는 라벨을 삭제하지 않고 보존하며(2026-07-27 정책
    // 반전) 신고 구간 동안 신고 게이트(LabelAccessGuard.requireNotUnderDeidentReport)가
    // GET /v1/frames/{srcSn}/labels 를 막는다. 즉 "200 + 0건"이 아니라 "412 + 행위 중립 안내"다.
    // 2차 응답은 게이트(secondFetchGate)로 붙잡아 ①즉시 반영 국면과 ②412 국면을 분리 관측한다
    // (안 그러면 에러 화면이 곧바로 페이지를 대체해 ① 단언이 레이스가 된다).
    let releaseSecondFetch: () => void = () => {};
    const secondFetchGate = new Promise<void>((resolve) => {
      releaseSecondFetch = resolve;
    });
    let labelsCallCount = 0;
    mock.onGet('/frames/300/labels').reply(async () => {
      labelsCallCount += 1;
      if (labelsCallCount === 1) {
        return [200, labelsPayload(300, { frameImageType: 'DEID', labelCount: 3 })];
      }
      await secondFetchGate;
      return [412, DEIDENT_GATE_ERROR_BODY];
    });
    mock.onPost('/labels/300/deident-report').reply(201, {
      success: true,
      data: { rprtSn: 9001, rawSn: 7, srcSn: 300, status: 'OPEN' },
      message: null,
      errorCode: null,
    });

    renderWithProviders(<LabelingPage />, {
      initialEntries: ['/label/300'],
      routes: [{ path: '/label/:id', element: <LabelingPage /> }],
    });

    // 초기: 객체 3건 렌더 (헤더 "3개 객체" + 객체 트리에 그룹 표시)
    await waitFor(() => {
      expect(screen.getByLabelText('객체 수')).toHaveTextContent('3개 객체');
    });
    expect(screen.queryByText('이 프레임에 객체가 없습니다')).not.toBeInTheDocument();

    // 신고 모달 열고 제출
    await user.click(screen.getByRole('button', { name: /비식별 누락 신고/ }));
    await user.type(
      screen.getByLabelText(/신고 사유/),
      '오른쪽 보행자 얼굴 블러 누락',
    );
    await user.click(screen.getByTestId('deident-report-submit'));

    // 신고 POST 호출됨 확인
    await waitFor(() => {
      expect(
        mock.history.post.filter((r) => r.url === '/labels/300/deident-report').length,
      ).toBe(1);
    });

    // ── 국면 ① 신고 성공 즉시(재조회 응답 도착 전) ──────────────────────────────
    // 캔버스/객체 목록이 즉시 0건 — 새로고침 없이 스테일 라벨이 사라진다.
    // ※ 서버가 라벨을 지워서가 아니라, reportedLock + store reset 으로 잠긴 영상의 라벨을
    //   화면에 띄워둔 채 편집·저장하는 경로를 막기 위함이다(라벨은 서버에 보존돼 있다).
    await waitFor(() => {
      expect(screen.getByText('이 프레임에 객체가 없습니다')).toBeInTheDocument();
    });
    expect(screen.getByLabelText('객체 수')).toHaveTextContent('0개 객체');

    // 라벨 스토어가 비워짐 (캔버스 렌더 소스)
    expect(useLabelStore.getState().labels).toHaveLength(0);

    // 잠금 배너 표시 (reportedLock 즉시 반영)
    expect(screen.getByTestId('deident-locked-banner')).toBeInTheDocument();

    // 라벨 쿼리 invalidate → 재조회 발생 (GET /frames/300/labels 2회 이상)
    await waitFor(() => {
      expect(labelsCallCount).toBeGreaterThanOrEqual(2);
    });

    // ── 국면 ② 재조회가 412 로 떨어진 뒤 ────────────────────────────────────────
    // 빈 라벨 화면이 아니라 <라벨 조회 실패> 안내 + 서버가 준 행위 중립 문구가 뜬다.
    releaseSecondFetch();
    await waitFor(() => {
      expect(screen.getByText('라벨 조회 실패')).toBeInTheDocument();
    });
    expect(screen.getByText(DEIDENT_GATE_ERROR_BODY.message)).toBeInTheDocument();
    // 빈 라벨 화면(객체 0건 캔버스)으로 남지 않는다 — 두 화면은 상호배타다.
    expect(screen.queryByText('이 프레임에 객체가 없습니다')).not.toBeInTheDocument();
  });
});
