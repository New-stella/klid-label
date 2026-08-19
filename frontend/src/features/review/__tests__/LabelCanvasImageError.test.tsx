// @design SCREEN-019 — 검수 캔버스: 프레임 이미지 로드 실패를 "조용한 백지"로 두지 않는다.
//
// 배경(실측 결함): 검수 상세 화면의 LabelCanvas 가 useImageBlob 의 error 를 구조분해조차 하지
// 않아, 404 가 나면 캔버스가 아무 안내 없이 빈 회색으로 남았다. 우측 객체 목록(라벨)은 정상
// 표시되므로 사용자는 왜 안 보이는지 알 수 없었다. 로딩 스피너는 실패 시 loading=false 로
// 전이해 **사라지기까지** 한다.
//
// react-konva 는 jsdom 에서 실제 렌더되지 않아 mock.

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';

vi.mock('react-konva', async () => (await import('@/test/konvaMock')).createKonvaMock());

// useImageBlob 반환값을 테스트마다 바꾸기 위해 가변 상태로 모킹한다.
let imageBlobState: { url: string | null; loading: boolean; error: Error | null } = {
  url: 'blob:mock-frame-image',
  loading: false,
  error: null,
};
vi.mock('@/features/label/hooks/useImageBlob', () => ({
  useImageBlob: () => imageBlobState,
}));

// 색상 판정은 라벨 마스터가 단일 진실원 — QueryClientProvider 없이 렌더하기 위해 고정 목록.
vi.mock('@/features/label/hooks/useLabelMasters', () => ({
  useLabelMasters: () => ({ data: [] }),
}));

import { ApiError } from '@/lib/api/errors';

import { LabelCanvas } from '../components/LabelCanvas';
import type { FrameDetail, LabelItem } from '../types';

// jsdom 의 Image 는 src 할당 시 load 이벤트를 발화하지 않으므로 stub 으로 제어한다.
const realImage = globalThis.Image;
class MockImage {
  onload: (() => void) | null = null;
  onerror: (() => void) | null = null;
  crossOrigin: string | null = null;
  naturalWidth = 0;
  naturalHeight = 0;
  private _src = '';
  set src(value: string) {
    this._src = value;
    if (!value) return;
    queueMicrotask(() => {
      this.naturalWidth = 640;
      this.naturalHeight = 480;
      this.onload?.();
    });
  }
  get src() {
    return this._src;
  }
}

const bboxLabel: LabelItem = {
  id: 1,
  lblTypeCd: 'BBOX',
  label: '사람',
  points: [
    [10, 20],
    [110, 220],
  ],
  autoLblYn: 'N',
  confScore: null,
};

const frame: FrameDetail = {
  srcSn: 300,
  frameNo: 0,
  imageUrl: '/api/v1/frames/300/image',
  labels: [bboxLabel],
};

/** 컨테이너 크기를 확보해야 Stage 가 마운트된다(jsdom 은 0 으로 측정). */
function withCanvasSize<T>(run: () => T | Promise<T>): Promise<T> {
  const origW = Object.getOwnPropertyDescriptor(HTMLElement.prototype, 'clientWidth');
  const origH = Object.getOwnPropertyDescriptor(HTMLElement.prototype, 'clientHeight');
  Object.defineProperty(HTMLDivElement.prototype, 'clientWidth', {
    configurable: true,
    get: () => 800,
  });
  Object.defineProperty(HTMLDivElement.prototype, 'clientHeight', {
    configurable: true,
    get: () => 600,
  });
  const restore = () => {
    if (origW) Object.defineProperty(HTMLDivElement.prototype, 'clientWidth', origW);
    else delete (HTMLDivElement.prototype as unknown as Record<string, unknown>).clientWidth;
    if (origH) Object.defineProperty(HTMLDivElement.prototype, 'clientHeight', origH);
    else delete (HTMLDivElement.prototype as unknown as Record<string, unknown>).clientHeight;
  };
  return Promise.resolve(run()).finally(restore);
}

describe('검수 캔버스 — 프레임 이미지 실패 안내 (SCREEN-019)', () => {
  beforeEach(() => {
    globalThis.Image = MockImage as unknown as typeof Image;
    imageBlobState = { url: 'blob:mock-frame-image', loading: false, error: null };
  });

  afterEach(() => {
    globalThis.Image = realImage;
  });

  it('검수_캔버스는_프레임_이미지_404면_실패_안내를_보여준다', async () => {
    // given — 이미지 요청이 404 (파생영상 백지 재현)
    imageBlobState = { url: null, loading: false, error: ApiError.fromStatus(404) };

    // when
    render(<LabelCanvas frame={frame} />);

    // then — 실패 사실 + 원인 힌트가 캔버스 위에 뜬다.
    const alert = await screen.findByTestId('review-frame-image-error');
    expect(alert).toHaveAttribute('role', 'alert');
    expect(alert).toHaveTextContent('프레임 이미지를 불러오지 못했습니다.');
    expect(alert).toHaveTextContent('이미지 파일을 찾을 수 없습니다.');
  });

  it('검수_캔버스는_412면_비식별_재처리_대기_힌트를_보여준다', async () => {
    // given — 비식별 신고 구간 게이트(412)
    imageBlobState = { url: null, loading: false, error: ApiError.fromStatus(412) };

    // when
    render(<LabelCanvas frame={frame} />);

    // then
    const alert = await screen.findByTestId('review-frame-image-error');
    expect(alert).toHaveTextContent('비식별 재처리 대기 중인 영상입니다.');
  });

  it('검수_캔버스는_403이면_권한_힌트를_보여준다', async () => {
    // given
    imageBlobState = { url: null, loading: false, error: ApiError.fromStatus(403) };

    // when
    render(<LabelCanvas frame={frame} />);

    // then
    const alert = await screen.findByTestId('review-frame-image-error');
    expect(alert).toHaveTextContent('이 프레임에 접근할 권한이 없습니다.');
  });

  it('로딩_중에는_실패_안내를_보여주지_않는다', async () => {
    // given — 아직 로딩 중(직전 실패 상태가 남아 있어도 로딩이 우선한다)
    imageBlobState = { url: null, loading: true, error: ApiError.fromStatus(404) };

    // when
    render(<LabelCanvas frame={frame} />);

    // then — 스피너만 보이고 실패 안내는 없다(로딩·실패 동시 표시/깜빡임 방지).
    expect(screen.getByTestId('review-canvas-image-spinner')).toBeInTheDocument();
    expect(screen.queryByTestId('review-frame-image-error')).toBeNull();
  });

  it('이미지_로드에_성공하면_실패_안내가_사라진다', async () => {
    // given — 정상 로드 (기본 상태)
    // when
    render(<LabelCanvas frame={frame} />);

    // then
    await waitFor(() => {
      expect(screen.getByTestId('review-label-canvas')).toBeInTheDocument();
    });
    expect(screen.queryByTestId('review-frame-image-error')).toBeNull();
  });

  it('이미지를_못_불러와도_라벨_오버레이는_그리지_않는다', async () => {
    // 현행 정책 고정(회귀 가드) — 배경 없이 좌표만 뜨면 검수 판단의 근거가 되지 못한다.
    // 또한 imgW/imgH=0 이면 getFitScale 이 scale≈0 을 돌려줘 strokeWidth 가 폭주한다.
    // given
    imageBlobState = { url: null, loading: false, error: ApiError.fromStatus(404) };

    await withCanvasSize(async () => {
      // when
      const { container } = render(<LabelCanvas frame={frame} />);
      await screen.findByTestId('review-frame-image-error');

      // then — 안내는 뜨지만 Stage/라벨 shape 는 렌더되지 않는다.
      expect(container.querySelector('[data-konva="Stage"]')).toBeNull();
      expect(container.querySelector('[data-konva="Rect"]')).toBeNull();
    });
  });
});
