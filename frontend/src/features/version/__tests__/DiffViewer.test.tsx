import { describe, expect, it } from 'vitest';
import { screen } from '@testing-library/react';

import { renderWithProviders } from '@/test/renderWithProviders';

import { DiffViewer } from '../components/DiffViewer';
import type { LabelDiff } from '../types';

const diffs: LabelDiff[] = [
  {
    type: 'ADDED',
    frameId: 1,
    objectId: 'obj-1',
    after: { type: 'BBOX', left: 0, top: 0, right: 10, bottom: 10 },
  },
  {
    type: 'MODIFIED',
    frameId: 1,
    objectId: 'obj-2',
    before: { type: 'BBOX', left: 0, top: 0, right: 5, bottom: 5 },
    after: { type: 'BBOX', left: 0, top: 0, right: 8, bottom: 8 },
  },
  {
    type: 'REMOVED',
    frameId: 2,
    objectId: 'obj-3',
    before: { type: 'BBOX', left: 1, top: 1, right: 3, bottom: 3 },
  },
];

describe('DiffViewer', () => {
  it('diff_ADDED_MODIFIED_REMOVED_색상_분리', () => {
    renderWithProviders(<DiffViewer diffs={diffs} />);
    const added = screen.getByTestId('diff-row-ADDED-obj-1');
    const modified = screen.getByTestId('diff-row-MODIFIED-obj-2');
    const removed = screen.getByTestId('diff-row-REMOVED-obj-3');

    // ADDED → green 계열
    expect(added.className).toMatch(/green/);
    // MODIFIED → yellow 계열
    expect(modified.className).toMatch(/yellow/);
    // REMOVED → red 계열
    expect(removed.className).toMatch(/red/);
  });

  it('diff_없을때_EmptyState_표시', () => {
    renderWithProviders(<DiffViewer diffs={[]} />);
    expect(screen.getByText('변경된 라벨이 없습니다')).toBeInTheDocument();
  });
});
