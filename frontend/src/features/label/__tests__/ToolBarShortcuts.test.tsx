// R1 — ToolBar 도구 버튼의 단축키 툴팁이 키맵(SHORTCUT_KEYMAP)에서 파생되어
// 100% 일치하는지 검증. (구: 선택=S·SAM추적=T 하드코딩 오표기 → 파생으로 교체)

import { screen } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { useLabelStore } from '@/stores/useLabelStore';
import { renderWithProviders } from '@/test/renderWithProviders';

import { ToolBar } from '../components/ToolBar';
import { formatBindingKeys } from '../hooks/labelingKeymap';

describe('ToolBar — 단축키 툴팁 키맵 정합(R1)', () => {
  beforeEach(() => {
    useLabelStore.getState().reset();
  });

  it('툴바_도구_툴팁의_단축키가_키맵과_일치한다', () => {
    renderWithProviders(<ToolBar onAutolabel={vi.fn()} />);

    const cases: Array<[string, string]> = [
      ['선택', 'tool.select'], // Esc
      ['바운딩 박스', 'tool.bbox'], // B
      ['폴리곤', 'tool.polygon'], // P
      ['AI 분할', 'tool.samSegment'], // G
      // ★'AI 추적'은 도구바 버튼이 아니므로 여기서 세지 않는다(사양 SCREEN-005 §좌측 도구바).
      //   단축키(Shift+T) 자체는 유지되며 그 표기 정합은 labelingKeymap 테스트가 고정한다.
      ['스켈레톤', 'tool.keypoint'], // K
      // ★삭제·실행취소·저장은 캔버스 상단 옵션바로 이관됐다 — 그 툴팁 정합은
      //   CanvasOptionBar.test.tsx 가 같은 키맵 파생으로 검증한다.
    ];

    for (const [name, id] of cases) {
      const expected = formatBindingKeys(id);
      expect(expected).not.toBe(''); // 파생 근거가 실제 키맵에 존재
      const btn = screen.getByRole('button', { name });
      expect(btn).toHaveAttribute('title', expect.stringContaining(expected));
    }
  });

  it('선택_도구_툴팁은_S가_아니라_Esc다_오표기0', () => {
    renderWithProviders(<ToolBar />);
    const btn = screen.getByRole('button', { name: '선택' });
    const title = btn.getAttribute('title') ?? '';
    expect(title).toContain('Esc');
    // 구 오표기(단독 'S')가 남지 않아야 한다.
    expect(title).not.toMatch(/(^|[^a-z])S([^a-z]|$)/);
  });

  /*
   * ★반전된 가드 — 구 케이스 `SAM추적_툴팁은_T가_아니라_Shift_T다` 를 대체한다(지우지 않고 뒤집는다).
   *
   * 구 단언: 도구바의 'AI 추적' 버튼 툴팁이 Shift+T 를 담는다.
   * 새 단언: 도구바에 그 버튼이 **없다**(사양 SCREEN-005 §좌측 도구바 — 실행 진입점은 우측 객체 패널).
   *
   * 단축키 Shift+T 는 폐기되지 않았다 — 키맵 표기 정합은 labelingKeymap 테스트가 계속 검증한다.
   */
  it('AI추적은_도구바_버튼이_아니므로_툴팁_정합_대상이_아니다', () => {
    renderWithProviders(<ToolBar />);
    expect(screen.queryByRole('button', { name: 'AI 추적' })).toBeNull();
  });
});
