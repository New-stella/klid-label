// @design SCREEN-019 §프레임 썸네일 스트립 — 못 불러온 썸네일은 빈 칸으로 두지 않는다.
//
// 배경(실측 결함): FrameTimelineThumbnail 이 useImageBlob 의 error 를 쓰지 않고
// `src={url ?? ''}` 로 렌더해, 실패한 썸네일이 **src="" 인 빈 <img>** 로 남았다(깨진 이미지
// 아이콘 또는 아무것도 없는 칸). 로딩 중인지 실패한 것인지 구분할 수 없었다.

import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest';
import { render, screen, within } from '@testing-library/react';

// useImageBlob 반환값을 테스트마다 바꾸기 위해 가변 상태로 모킹한다.
let imageBlobState: { url: string | null; loading: boolean; error: Error | null } = {
  url: null,
  loading: true,
  error: null,
};
vi.mock('@/features/label/hooks/useImageBlob', () => ({
  useImageBlob: () => imageBlobState,
}));

import { ApiError } from '@/lib/api/errors';

import { FrameTimeline } from '../components/FrameTimeline';
import type { FrameDetail } from '../types';

beforeAll(() => {
  Element.prototype.scrollIntoView = vi.fn();
});

afterEach(() => {
  imageBlobState = { url: null, loading: true, error: null };
});

const frames: FrameDetail[] = [
  { srcSn: 1000, frameNo: 0, imageUrl: '/frames/1000.jpg', labels: [] },
  { srcSn: 1001, frameNo: 1, imageUrl: '/frames/1001.jpg', labels: [] },
];

describe('FrameTimeline 썸네일 — 이미지 실패 자리표시 (SCREEN-019)', () => {
  it('실패한_썸네일은_빈_이미지가_아니라_자리표시를_보여준다', () => {
    // given — 썸네일 이미지 요청이 404
    imageBlobState = { url: null, loading: false, error: ApiError.fromStatus(404) };

    // when
    render(<FrameTimeline frames={frames} currentFrameIdx={0} onSelect={() => {}} />);

    // then — 자리표시가 뜨고, src="" 인 빈 <img> 는 남지 않는다.
    const thumb = screen.getByTestId('frame-timeline-thumb-0');
    const placeholder = within(thumb).getByTestId('frame-timeline-thumb-failed');
    expect(placeholder).toBeInTheDocument();
    expect(placeholder).toHaveAttribute('aria-label', '프레임 1 이미지를 불러오지 못했습니다');
    expect(thumb.querySelector('img')).toBeNull();
  });

  it('로딩_중_자리표시는_실패_자리표시와_구분된다', () => {
    // given — 아직 로딩 중
    imageBlobState = { url: null, loading: true, error: null };

    // when
    render(<FrameTimeline frames={frames} currentFrameIdx={0} onSelect={() => {}} />);

    // then — 실패 자리표시가 아니다(둘이 같은 표시면 사용자가 구분할 수 없다).
    const thumb = screen.getByTestId('frame-timeline-thumb-0');
    expect(within(thumb).queryByTestId('frame-timeline-thumb-failed')).toBeNull();
    expect(within(thumb).getByTestId('frame-timeline-thumb-image')).toBeInTheDocument();
  });

  it('정상_로드된_썸네일은_이미지를_그대로_렌더한다', () => {
    // given
    imageBlobState = { url: 'blob:mock-thumb', loading: false, error: null };

    // when
    render(<FrameTimeline frames={frames} currentFrameIdx={0} onSelect={() => {}} />);

    // then — 기존 계약(lazy·width·height) 유지.
    const thumb = screen.getByTestId('frame-timeline-thumb-0');
    const img = within(thumb).getByRole('img');
    expect(img).toHaveAttribute('src', 'blob:mock-thumb');
    expect(img).toHaveAttribute('loading', 'lazy');
    expect(img).toHaveAttribute('width', '64');
    expect(img).toHaveAttribute('height', '40');
    expect(within(thumb).queryByTestId('frame-timeline-thumb-failed')).toBeNull();
  });
});
