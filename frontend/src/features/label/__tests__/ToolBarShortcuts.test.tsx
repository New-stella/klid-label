// R1 — ToolBar 도구 버튼의 단축키 툴팁이 키맵(SHORTCUT_KEYMAP)에서 파생되어
// 100% 일치하는지 검증. (구: 선택=S·SAM추적=T 하드코딩 오표기 → 파생으로 교체)

import { screen } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { useLabelStore } from '@/stores/useLabelStore';
import { renderWithProviders } from '@/test/renderWithProviders';

import { ToolBar } from '../components/ToolBar';
import { formatBindingKeys, SHORTCUT_KEYMAP } from '../hooks/labelingKeymap';

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

  /*
   * ★일반 가드 (2026-08-18 신설) — **키맵에 없는 단축키 문자열은 도구바에 노출되지 않는다.**
   *
   * 실제 결함: 'AI 탐지' 액션이 고정값 `shortcut: 'Y'` 를 들고 있어 버튼 `title` 에 `(Y)` 가
   * 실렸는데, SHORTCUT_KEYMAP 에 `y` 바인딩이 **없어** 눌러도 아무 일이 일어나지 않았다
   * (화면이 존재하지 않는 단축키를 광고). 개별 버튼만 단언하면 다음 액션이 같은 실수를
   * 반복하므로, **그리기 도구 그룹 전체**를 훑어 파생 근거 없는 표기를 통째로 막는다.
   *
   * 범위를 그리기 그룹으로 한정하는 이유: 보기 그룹의 회전 버튼은 `(현재 0도)` 처럼 단축키가
   * 아니라 **현재 상태**를 툴팁에 싣는 다른 축이다(키맵 파생 대상이 아님).
   */
  it('키맵에_없는_단축키_문자열은_도구바에_노출되지_않는다', () => {
    renderWithProviders(<ToolBar onAutolabel={vi.fn()} />);

    // 파생 근거의 전체 집합 — 키맵이 실제로 만들어 낼 수 있는 표기만 허용한다.
    const allowed = new Set(
      SHORTCUT_KEYMAP.map((b) => formatBindingKeys(b.id)).filter((s) => s !== ''),
    );
    expect(allowed.size).toBeGreaterThan(0); // 집합이 비면 가드가 무의미해진다

    const drawGroup = screen.getByRole('heading', { name: '그리기 도구' }).closest('section');
    expect(drawGroup).not.toBeNull();

    const buttons = Array.from(drawGroup!.querySelectorAll('button'));
    expect(buttons.length).toBeGreaterThan(0);

    for (const btn of buttons) {
      const title = btn.getAttribute('title') ?? '';
      const name = btn.getAttribute('aria-label') ?? '';
      // `title` 은 `{이름}` 또는 `{이름} ({표기})` 다. 괄호 표기가 있으면 키맵 파생이어야 한다.
      const suffix = title.startsWith(`${name} (`) ? title.slice(name.length + 2, -1) : null;
      if (suffix !== null) {
        expect(allowed, `버튼 '${name}' 의 단축키 표기 '${suffix}' 가 키맵에 없다`).toContain(suffix);
      }
    }
  });

  /*
   * 위 일반 가드의 **구체 사례 고정** — 일반 가드만 두면 'AI 탐지' 버튼이 사라져도 통과하므로
   * (공집합 순회) 그 버튼이 실재하면서 단축키 표기가 없다는 것을 따로 못박는다.
   * 구 동작(`title="AI 탐지 (Y)"`) → **폐기**. 접근성 이름·동작은 그대로다.
   */
  it('AI탐지_버튼은_단축키_표기_없이_이름만_노출한다_구_Y표기_폐기', () => {
    const onAutolabel = vi.fn();
    renderWithProviders(<ToolBar onAutolabel={onAutolabel} />);

    const btn = screen.getByRole('button', { name: 'AI 탐지' });
    expect(btn).toHaveAttribute('title', 'AI 탐지');
    expect(btn.getAttribute('title')).not.toContain('Y');
    // 키맵에 `y` 바인딩이 없다는 것이 이 가드의 근거다 — 생기면 이 단언이 먼저 깨진다.
    expect(SHORTCUT_KEYMAP.some((b) => b.key === 'y')).toBe(false);
  });
});
