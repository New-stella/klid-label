// event_annotation evidence 후보 행 — '선택 객체 추가' 버튼 활성/비활성 + MAX_ID 힌트 노출.

import { describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';

import { EvidenceCandidateRow } from '../EvidenceCandidateRow';
import type { EvidenceRow } from '../eventAnnotationShared';

const baseRow: EvidenceRow = {
  key: 'c1',
  evidenceText: '',
  frameId: '',
  objId: '',
  objBbox: '',
  objLabel: '',
};

function renderRow(overrides: Partial<React.ComponentProps<typeof EvidenceCandidateRow>> = {}) {
  return render(
    <EvidenceCandidateRow
      row={baseRow}
      hasSelectedObject={false}
      onRemove={vi.fn()}
      onFieldChange={vi.fn()}
      onAppendCurrentFrame={vi.fn()}
      onAppendSelectedObject={vi.fn()}
      {...overrides}
    />,
  );
}

describe('EvidenceCandidateRow', () => {
  it('선택객체_있으면_선택객체추가_버튼_활성', () => {
    renderRow({ hasSelectedObject: true });
    const btn = screen.getByTestId('ea-evidence-add-selected-c1');
    expect(btn).toBeInTheDocument();
    expect(btn).not.toBeDisabled();
  });

  it('선택객체_없으면_버튼_disabled', () => {
    renderRow({ hasSelectedObject: false });
    expect(screen.getByTestId('ea-evidence-add-selected-c1')).toBeDisabled();
  });

  it('MAX_ID_힌트_텍스트_노출', () => {
    renderRow();
    // obj_id / obj_label 하단 힌트 — "원소당 최대 200자"
    const hints = screen.getAllByText(/원소당 최대 200자/);
    expect(hints.length).toBeGreaterThanOrEqual(1);
  });
});
