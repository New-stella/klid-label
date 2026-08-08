// LabelingPage — 이슈 탭 노출 가드 검증 (code-reviewer #1 회귀 테스트).
//
// 버그: issueRawSn = data?.videoId ?? data?.srcSn 폴백 → videoId 부재 시
//       프레임 PK(srcSn)가 영상 ID 자리로 들어가 잘못된 영상의 이슈 조회/404.
// 수정: videoId 만 사용. videoId 부재 시 이슈 탭 미노출.
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import MockAdapter from 'axios-mock-adapter';
import { screen, waitFor } from '@testing-library/react';
import { createElement, type ReactNode } from 'react';

vi.mock('react-konva', () => {
  const passthrough = (name: string) => {
    const KonvaMock = ({
      children,
      ...rest
    }: {
      children?: ReactNode;
      [key: string]: unknown;
    }) => createElement('div', { 'data-konva': name, ...rest }, children);
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
  };
});

import { apiClient } from '@/lib/api/client';
import { LabelingPage } from '@/pages/label/LabelingPage';
import { renderWithProviders } from '@/test/renderWithProviders';
import { useAuthStore } from '@/stores/useAuthStore';

function labelsPayload(opts: { srcSn: number; videoId?: number }) {
  return {
    success: true,
    data: {
      frameNo: 0,
      srcSn: opts.srcSn,
      ...(opts.videoId !== undefined ? { videoId: opts.videoId } : {}),
      siblings: [{ srcSn: opts.srcSn, frameNo: 0 }],
      labels: [],
    },
    message: null,
    errorCode: null,
  };
}

describe('LabelingPage 이슈 탭 노출 가드', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
    useAuthStore.setState({
      token: 'tok',
      claims: { sub: '10', role: 'WORKER', channel: 'INTERNAL', exp: 9999999999 },
    });
    mock.onGet('/frames/300/image').reply(200, new Blob());
  });

  afterEach(() => {
    mock.restore();
    useAuthStore.getState().clear();
    vi.restoreAllMocks();
  });

  // ⚠ 기대결과 정정(B6#12): 구 테스트는 `videoId 없으면 이슈 탭 미노출`을 정답으로 박제했는데,
  //   사양은 "영상 정보가 없으면 **탭은 노출하되 이용 불가 안내**" 다. 탭을 감추면 사용자가 왜
  //   못 쓰는지 알 방법이 아예 없다. 원래 이 테스트가 지키던 것(잘못된 srcSn 폴백으로 이슈를
  //   조회하지 않는다)은 그대로 유지한다 — 그 가드가 이 케이스의 본질이다.
  it('videoId_없으면_이슈_탭은_노출되되_이용불가_안내가_뜨고_srcSn으로_조회하지_않는다', async () => {
    // given — videoId 없는 라벨 응답 (srcSn=300 만 존재)
    mock.onGet('/frames/300/labels').reply(200, labelsPayload({ srcSn: 300 }));
    // 잘못된 폴백이 srcSn(300) 을 영상 ID 로 써서 호출하면 추적되도록 스파이.
    const issueGetSpy = vi.fn(() => [200, { success: true, data: [], message: null, errorCode: null }]);
    mock.onGet('/videos/300/issues').reply(issueGetSpy);

    renderWithProviders(<LabelingPage />, {
      initialEntries: ['/label/300'],
      routes: [{ path: '/label/:id', element: <LabelingPage /> }],
    });

    await waitFor(() => expect(screen.getByTestId('labeling-page')).toBeInTheDocument());
    // 데이터 로딩 완료 대기 — 프레임 strip(siblings 기반)이 렌더되면 data 적용됨.
    await screen.findByRole('option', { name: '프레임 0' });

    // then — 탭은 노출된다(안내를 볼 통로가 있어야 한다).
    expect(screen.getByTestId('right-tab-issues')).toBeInTheDocument();
    // 탭을 열면 조회 대신 이용 불가 사유가 뜬다.
    screen.getByTestId('right-tab-issues').click();
    await waitFor(() => {
      expect(screen.getByTestId('label-issue-unavailable')).toBeInTheDocument();
    });
    // 원래 가드 유지 — 잘못된 영상 ID(srcSn) 로 이슈를 조회하지 않는다.
    expect(issueGetSpy).not.toHaveBeenCalled();
  });

  it('videoId_있으면_이슈_탭_노출되고_videoId로_조회', async () => {
    // given — videoId=7 인 라벨 응답
    mock.onGet('/frames/301/image').reply(200, new Blob());
    mock.onGet('/frames/301/labels').reply(200, labelsPayload({ srcSn: 301, videoId: 7 }));
    let issuedVideoId: string | null = null;
    mock.onGet('/videos/7/issues').reply((config) => {
      issuedVideoId = config.url ?? null;
      return [200, { success: true, data: [], message: null, errorCode: null }];
    });

    renderWithProviders(<LabelingPage />, {
      initialEntries: ['/label/301'],
      routes: [{ path: '/label/:id', element: <LabelingPage /> }],
    });

    await waitFor(() => expect(screen.getByTestId('labeling-page')).toBeInTheDocument());

    // then — 이슈 탭 노출 + videoId(7) 로 조회.
    await waitFor(() => {
      expect(screen.getByTestId('right-tab-issues')).toBeInTheDocument();
    });
    await waitFor(() => {
      expect(issuedVideoId).toBe('/videos/7/issues');
    });
  });
});
