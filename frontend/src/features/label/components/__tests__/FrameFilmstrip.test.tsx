// 프레임 상태색 컴포넌트 레벨 검증 — CURRENT/INQUIRY/SAVED/NONE 우선순위.
// v2 반려는 영상 단위(REJECTION.srcSn=null)라 프레임색(주황) 미대상 → 테스트도 미포함.
// useImageBlob 는 apiClient fetch 를 수행하므로 mock 으로 대체(썸네일 이미지는 본 검증 범위 밖).
import { describe, expect, it, vi } from 'vitest';
import { render } from '@testing-library/react';

vi.mock('../../hooks/useImageBlob', () => ({
  useImageBlob: () => ({ url: null, loading: false, error: null }),
}));

import { FrameFilmstrip } from '../FrameFilmstrip';
import type { FrameSummary } from '../../types';

function frame(srcSn: number, frameNo: number): FrameSummary {
  return {
    srcSn,
    frameNo,
    thumbnailUrl: '',
    imageUrl: '',
    imageWidth: 1920,
    imageHeight: 1080,
  };
}

function statusOf(index: number): string | null | undefined {
  return document
    .querySelector(`[data-frame-index="${index}"]`)
    ?.getAttribute('data-frame-status');
}

describe('FrameFilmstrip — 프레임 상태색', () => {
  const frames = [frame(100, 0), frame(101, 1), frame(102, 2), frame(103, 3)];

  it('현재_확인요청_저장_모두_렌더', () => {
    render(
      <FrameFilmstrip
        frames={frames}
        currentIndex={0}
        onSelect={() => {}}
        inquirySrcSns={new Set([101])}
        savedSrcSns={new Set([102])}
      />,
    );
    // idx0=현재(CURRENT), idx1=확인요청(INQUIRY, 빨강), idx2=저장(SAVED, 연두), idx3=NONE.
    expect(statusOf(0)).toBe('CURRENT');
    expect(statusOf(1)).toBe('INQUIRY');
    expect(statusOf(2)).toBe('SAVED');
    expect(statusOf(3)).toBe('NONE');
  });

  it('현재프레임은_다른_상태보다_우선', () => {
    // 현재 프레임(idx0=100)이 확인요청/저장 집합에 모두 포함돼도 CURRENT 로 표시.
    render(
      <FrameFilmstrip
        frames={frames}
        currentIndex={0}
        onSelect={() => {}}
        inquirySrcSns={new Set([100])}
        savedSrcSns={new Set([100])}
      />,
    );
    expect(statusOf(0)).toBe('CURRENT');
  });

  it('저장만_된_현재아닌_프레임은_SAVED_연두', () => {
    render(
      <FrameFilmstrip
        frames={frames}
        currentIndex={0}
        onSelect={() => {}}
        savedSrcSns={new Set([101, 102])}
      />,
    );
    expect(statusOf(1)).toBe('SAVED');
    expect(statusOf(2)).toBe('SAVED');
    // 아무 상태 없는 프레임은 NONE.
    expect(statusOf(3)).toBe('NONE');
  });

  it('확인요청이_저장보다_우선', () => {
    // 같은 프레임이 저장+확인요청이면 INQUIRY(빨강) 가 우선(resolveFrameStatus 우선순위).
    render(
      <FrameFilmstrip
        frames={frames}
        currentIndex={0}
        onSelect={() => {}}
        inquirySrcSns={new Set([101])}
        savedSrcSns={new Set([101])}
      />,
    );
    expect(statusOf(1)).toBe('INQUIRY');
  });
});
