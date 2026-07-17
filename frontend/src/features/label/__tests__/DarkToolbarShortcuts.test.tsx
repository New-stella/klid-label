// R1 — DarkToolbar 도구 버튼의 단축키 툴팁이 키맵(SHORTCUT_KEYMAP)에서 파생되어
// 100% 일치하는지 검증. (구: 선택=S·SAM추적=T 하드코딩 오표기 → 파생으로 교체)

import { screen } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { useLabelStore } from '@/stores/useLabelStore';
import { renderWithProviders } from '@/test/renderWithProviders';

import { DarkToolbar } from '../components/DarkToolbar';
import { formatBindingKeys } from '../hooks/labelingKeymap';

describe('DarkToolbar — 단축키 툴팁 키맵 정합(R1)', () => {
  beforeEach(() => {
    useLabelStore.getState().reset();
  });

  it('툴바_도구_툴팁의_단축키가_키맵과_일치한다', () => {
    renderWithProviders(<DarkToolbar onSave={vi.fn()} onAutolabel={vi.fn()} />);

    const cases: Array<[string, string]> = [
      ['선택', 'tool.select'], // Esc
      ['바운딩 박스', 'tool.bbox'], // B
      ['폴리곤', 'tool.polygon'], // P
      ['AI 분할', 'tool.samSegment'], // G
      ['AI 추적', 'tool.track'], // Shift+T
      ['스켈레톤', 'tool.keypoint'], // K
      ['삭제', 'label.delete'], // Del
      ['실행 취소', 'edit.undo'], // Ctrl+Z
      ['저장', 'edit.save'], // Ctrl+S
    ];

    for (const [name, id] of cases) {
      const expected = formatBindingKeys(id);
      expect(expected).not.toBe(''); // 파생 근거가 실제 키맵에 존재
      const btn = screen.getByRole('button', { name });
      expect(btn).toHaveAttribute('title', expect.stringContaining(expected));
    }
  });

  it('선택_도구_툴팁은_S가_아니라_Esc다_오표기0', () => {
    renderWithProviders(<DarkToolbar onSave={vi.fn()} />);
    const btn = screen.getByRole('button', { name: '선택' });
    const title = btn.getAttribute('title') ?? '';
    expect(title).toContain('Esc');
    // 구 오표기(단독 'S')가 남지 않아야 한다.
    expect(title).not.toMatch(/(^|[^a-z])S([^a-z]|$)/);
  });

  it('SAM추적_툴팁은_T가_아니라_Shift_T다', () => {
    renderWithProviders(<DarkToolbar onSave={vi.fn()} />);
    const btn = screen.getByRole('button', { name: 'AI 추적' });
    expect(btn.getAttribute('title') ?? '').toContain('Shift+T');
  });
});
