// R5 프레임 4색(21 §21.9) 컴포넌트 레벨 검증 — CURRENT/INQUIRY/REJECTION/SAVED/NONE 우선순위.
// useImageBlob 는 apiClient fetch 를 수행하므로 mock 으로 대체(썸네일 이미지는 본 검증 범위 밖).
import { describe, expect, it, vi } from 'vitest';
import { render } from '@testing-library/react';

vi.mock('../../hooks/useImageBlob', () => ({
  useImageBlob: () => ({ url: null, loading: false, error: null }),
}));

import { DarkFrameStrip } from '../DarkFrameStrip';
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

describe('DarkFrameStrip — 프레임 4색 상태', () => {
  const frames = [frame(100, 0), frame(101, 1), frame(102, 2), frame(103, 3)];

  it('4색_모두_렌더_현재_확인요청_반려_저장', () => {
    render(
      <DarkFrameStrip
        frames={frames}
        currentIndex={0}
        onSelect={() => {}}
        inquirySrcSns={new Set([101])}
        rejectionSrcSns={new Set([102])}
        savedSrcSns={new Set([103])}
      />,
    );
    // idx0=현재(CURRENT), idx1=확인요청(INQUIRY), idx2=반려(REJECTION), idx3=저장(SAVED).
    expect(statusOf(0)).toBe('CURRENT');
    expect(statusOf(1)).toBe('INQUIRY');
    expect(statusOf(2)).toBe('REJECTION');
    expect(statusOf(3)).toBe('SAVED');
  });

  it('현재프레임은_다른_상태보다_우선', () => {
    // 현재 프레임(idx0=100)이 반려/확인요청/저장 집합에 모두 포함돼도 CURRENT 로 표시.
    render(
      <DarkFrameStrip
        frames={frames}
        currentIndex={0}
        onSelect={() => {}}
        inquirySrcSns={new Set([100])}
        rejectionSrcSns={new Set([100])}
        savedSrcSns={new Set([100])}
      />,
    );
    expect(statusOf(0)).toBe('CURRENT');
  });

  it('저장만_된_현재아닌_프레임은_SAVED_연두', () => {
    render(
      <DarkFrameStrip
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

  it('반려가_저장보다_우선', () => {
    // 같은 프레임이 저장+반려면 REJECTION(주황) 이 우선(resolveFrameStatus 우선순위).
    render(
      <DarkFrameStrip
        frames={frames}
        currentIndex={0}
        onSelect={() => {}}
        rejectionSrcSns={new Set([101])}
        savedSrcSns={new Set([101])}
      />,
    );
    expect(statusOf(1)).toBe('REJECTION');
  });
});
