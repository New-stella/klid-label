// LabelingPage — 프레임 이미지 로드 실패 시 "조용한 백지" 대신 안내가 보여야 한다.
//
// 배경(실측 결함): 해상도·증강 파생영상은 BE 가 프레임 이미지를 404 로 떨궜는데, 페이지가
// useImageBlob 의 error 를 구조분해조차 하지 않아 화면에 아무 표시도 나오지 않았다
// (캔버스 백지 + 라벨 목록·프레임 카운터만 정상 → 원인 파악 불가).
//
// react-konva 는 jsdom 에서 실제 렌더링 안 됨 → 모킹.
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import MockAdapter from 'axios-mock-adapter';
import { screen, waitFor } from '@testing-library/react';

vi.mock('react-konva', async () => (await import('@/test/konvaMock')).createKonvaMock());

import { apiClient } from '@/lib/api/client';
import { LabelingPage } from '@/pages/label/LabelingPage';
import { renderWithProviders } from '@/test/renderWithProviders';
import { useAuthStore } from '@/stores/useAuthStore';

const DUMMY_TOKEN = ['dummy', 'test', 'value'].join('-');

const LABELS_PAYLOAD = {
  success: true,
  data: {
    frameNo: 0,
    srcSn: 300,
    videoId: 54,
    siblings: [{ srcSn: 300, frameNo: 0 }],
    labels: [],
  },
  message: null,
  errorCode: null,
};

describe('LabelingPage 프레임 이미지 실패 안내', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
    // 값 자체는 의미 없는 더미 — 인증 헤더가 붙는 경로를 타게 하기 위한 것뿐이다.
    useAuthStore.setState({
      token: DUMMY_TOKEN,
      claims: { sub: '10', role: 'WORKER', channel: 'INTERNAL', exp: 9999999999 },
    });
    mock.onGet('/frames/300/labels').reply(200, LABELS_PAYLOAD);
  });

  afterEach(() => {
    mock.restore();
    useAuthStore.getState().clear();
  });

  function render() {
    renderWithProviders(<LabelingPage />, {
      initialEntries: ['/label/300'],
      routes: [{ path: '/label/:id', element: <LabelingPage /> }],
    });
  }

  it('이미지_로드_실패시_안내가_표시된다', async () => {
    // given — 프레임 이미지가 404 (파생영상 백지 재현)
    mock.onGet('/frames/300/image').reply(404);

    // when
    render();

    // then — 캔버스 영역에 실패 안내 + 사유 힌트
    const alert = await screen.findByTestId('frame-image-error');
    expect(alert).toHaveTextContent('프레임 이미지를 불러오지 못했습니다.');
    expect(alert).toHaveTextContent('이미지 파일을 찾을 수 없습니다.');
  });

  it('비식별_신고_구간_412면_사유가_안내된다', async () => {
    // given — 신고 구간 게이트(412)
    mock.onGet('/frames/300/image').reply(412);

    // when
    render();

    // then
    const alert = await screen.findByTestId('frame-image-error');
    expect(alert).toHaveTextContent('비식별 재처리 대기 중인 영상입니다.');
  });

  it('이미지_정상_로드시_안내가_보이지_않는다', async () => {
    // given — 정상 200 (기존 동작 회귀 가드)
    mock.onGet('/frames/300/image').reply(200, new Blob());

    // when
    render();

    // then — 페이지는 뜨지만 실패 안내는 없음
    await waitFor(() => expect(screen.getByTestId('labeling-page')).toBeInTheDocument());
    expect(screen.queryByTestId('frame-image-error')).not.toBeInTheDocument();
  });
});
