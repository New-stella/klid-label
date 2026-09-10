import { describe, it, expect, vi } from 'vitest';
import { render, fireEvent } from '@testing-library/react';

import { PortalMarkingStage } from '../PortalMarkingStage';

/**
 * 포털 마킹 무대가 **재생 회복 신호를 실제로 낸다**는 것을 지킨다.
 *
 * 왜 여기에 두는가 — 화면 쪽 시험은 이 부품을 흉내내므로 「화면이 배선했는가」까지만 본다.
 * 부품에서 신호를 빼도 그 시험은 초록이다(변이로 실증했다). 그래서 부품 자리에 따로 둔다.
 *
 * 이 신호가 없으면 재발급 재시도 예산이 **누적으로만 줄어**, 서명이 여러 번 만료되는
 * 정상 동선에서 회복 경로가 영구히 닫힌다. 마킹은 오래 머무는 화면이라 반드시 겪는다.
 */
describe('포털 마킹 무대 — 재생 회복 신호', () => {
  it('★재생_가능해지면_onSrcRecovered가_불린다_재시도_예산_회복_신호', () => {
    const onSrcRecovered = vi.fn();
    render(<PortalMarkingStage src="/test.mp4" marks={[]} onSrcRecovered={onSrcRecovered} />);
    const video = document.querySelector('video') as HTMLVideoElement;

    fireEvent.canPlay(video);

    expect(onSrcRecovered).toHaveBeenCalledTimes(1);
  });

  it('회복_신호는_loadedmetadata만으로는_오지_않는다_길이만_읽힌_상태는_회복이_아니다', () => {
    const onSrcRecovered = vi.fn();
    render(<PortalMarkingStage src="/test.mp4" marks={[]} onSrcRecovered={onSrcRecovered} />);
    const video = document.querySelector('video') as HTMLVideoElement;
    Object.defineProperty(video, 'duration', { configurable: true, value: 12 });

    fireEvent.loadedMetadata(video);

    expect(onSrcRecovered).not.toHaveBeenCalled();
  });

  it('★로드_실패는_회복이_아니다_error에서는_onSrcRecovered가_불리지_않는다', () => {
    const onSrcError = vi.fn();
    const onSrcRecovered = vi.fn();
    render(
      <PortalMarkingStage
        src="/test.mp4"
        marks={[]}
        onSrcError={onSrcError}
        onSrcRecovered={onSrcRecovered}
      />,
    );
    const video = document.querySelector('video') as HTMLVideoElement;

    fireEvent.error(video);

    expect(onSrcError).toHaveBeenCalledTimes(1);
    expect(onSrcRecovered).not.toHaveBeenCalled();
  });
});
