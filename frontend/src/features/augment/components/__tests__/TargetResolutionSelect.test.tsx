import { describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';

import { TargetResolutionSelect } from '../TargetResolutionSelect';

/**
 * ★ 안내 문구는 확정 정책(CLAUDE.md SFR-06-03)과 일치해야 한다 — "라벨/이미지 좌표를
 * 해상도 배율(scaleX/scaleY)로 재계산해 적재한다"(2026-07-21 설계 반전). 구 문구는 폐기된
 * "좌표 미복사" 정책을 사실처럼 안내하고 있었다.
 */
describe('TargetResolutionSelect 안내 문구', () => {
  it('좌표가_복사되지_않는다는_폐기된_문구를_노출하지_않는다', () => {
    render(
      <TargetResolutionSelect value={['RESL_1080P', 'RESL_720P', 'RESL_480P']} onChange={vi.fn()} />,
    );

    expect(screen.queryByText(/좌표는 복사되지/)).not.toBeInTheDocument();
  });

  it('라벨_좌표가_재계산되어_적용된다는_문구를_노출한다', () => {
    render(
      <TargetResolutionSelect value={['RESL_1080P', 'RESL_720P', 'RESL_480P']} onChange={vi.fn()} />,
    );

    expect(screen.getByText(/라벨 좌표는 목표\s*해상도 배율로 재계산되어/)).toBeInTheDocument();
  });
});
