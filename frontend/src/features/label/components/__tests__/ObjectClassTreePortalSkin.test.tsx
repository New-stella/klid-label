// 회귀 가드 — 객체 목록의 포털 꼴. [@design SCREEN-029]
//
// 부모 포털 시안(`ObjectList`)의 줄은 **두 줄**이다 —
//   윗줄: 트랙 번호 | 형태          👁 🔒 🗑
//   아랫줄: 이름 · 만든 방식 배지
// 관제 줄은 한 줄이다. 두 꼴이 갈리는 것이 확정 사양이라, 한쪽을 다른 쪽에 맞추려는 변경을
// 이 가드가 막는다.
//
// ★**부품을 갈아끼운 것이 아니라 생김새만 입혔다.** 고르기 · 숨김 · 잠금 · 삭제 배선과 잠금
//   판정은 두 채널이 같은 것을 쓴다 — 그 사실도 함께 고정한다(포털만 조용히 다르게 동작하면
//   두 벌을 유지하는 것과 같아진다).
//
// ⚠ 이 가드는 «예쁜가»를 말하지 않는다. 어느 자리에 무엇이 서고 무엇이 같은 배선을 쓰는지만 본다.

import { describe, expect, it } from 'vitest';
import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { renderWithProviders } from '@/test/renderWithProviders';

import { ObjectClassTree } from '../ObjectClassTree';
import { useLabelStore } from '@/stores/useLabelStore';

/**
 * 같은 분류 둘 + 다른 분류 하나 — 무리 묶기와 줄 꼴을 한자리에서 본다.
 * 무리 묶기 키는 `className` 이고 형태는 `shape.type` 이다(컴포넌트 실측).
 */
const LABELS = [
  {
    id: 'a1',
    className: '사람',
    labelId: 1,
    trackId: '3',
    source: 'MANUAL',
    shape: { type: 'BBOX', points: [[1, 2], [3, 4]] },
  },
  {
    id: 'a2',
    className: '사람',
    labelId: 1,
    source: 'MANUAL',
    shape: { type: 'BBOX', points: [[5, 6], [7, 8]] },
  },
  {
    id: 'b1',
    className: '차량',
    labelId: 2,
    source: 'MANUAL',
    shape: { type: 'POLYGON', points: [[1, 1], [2, 2], [3, 3]] },
  },
] as unknown as Parameters<typeof ObjectClassTree>[0]['labels'];

function renderTree(portalMode: boolean) {
  useLabelStore.getState().reset();
  return renderWithProviders(
    <ObjectClassTree labels={LABELS} portalMode={portalMode} />,
  );
}

/** 포털 줄 — 이름 버튼이 속한 줄 상자. */
function portalRow(name: string): HTMLElement {
  const btn = screen.getByRole('button', { name: `${name} 선택` });
  const row = btn.closest('.klid-object-row');
  expect(row, `${name} 줄이 킷 줄 꼴이 아니다`).not.toBeNull();
  return row as HTMLElement;
}

describe('객체 목록 — 포털 꼴', () => {
  describe('① 한 객체가 두 줄이다', () => {
    it('★윗줄에 트랙 번호와 형태가 선다', () => {
      renderTree(true);
      const row = portalRow('사람 #1');
      expect(within(row).getByText('T:3')).toBeInTheDocument();
      expect(within(row).getByText('BBOX')).toBeInTheDocument();
    });

    it('★트랙이 없으면 「T:—」로 선다 — 빈 칸으로 두지 않는다', () => {
      renderTree(true);
      expect(within(portalRow('사람 #2')).getByText('T:—')).toBeInTheDocument();
    });

    it('★아랫줄에 이름과 만든 방식 배지가 선다', () => {
      renderTree(true);
      const row = portalRow('사람 #1');
      expect(within(row).getByText('사람 #1')).toBeInTheDocument();
      expect(within(row).getByText('수동')).toBeInTheDocument();
    });

    it('★관제는 한 줄 그대로다 — 한쪽에 맞추지 않는다', () => {
      renderTree(false);
      expect(document.querySelector('.klid-object-row')).toBeNull();
      expect(screen.getByRole('button', { name: '사람 #1 선택' })).toBeInTheDocument();
    });
  });

  describe('② 무리 머리는 색 점 · 이름 · 개수다', () => {
    it('★포털 무리 머리가 킷 꼴이고 접고 펼 수 있다', async () => {
      const user = userEvent.setup();
      renderTree(true);
      // 무리 머리만 집는다 — 같은 이름이 줄 버튼에도 있어 이름만으로는 갈리지 않는다.
      const head = screen
        .getAllByRole('button', { name: /사람/ })
        .find((b) => b.classList.contains('klid-object-group-head')) as HTMLElement;
      expect(head, '무리 머리가 킷 꼴이 아니다').toBeDefined();
      expect(head).toHaveAttribute('aria-expanded', 'true');
      await user.click(head);
      expect(head).toHaveAttribute('aria-expanded', 'false');
      // 접으면 그 무리의 줄이 사라진다.
      expect(screen.queryByRole('button', { name: '사람 #1 선택' })).toBeNull();
    });

    it('줄은 목록 감싸개 안에 선다 — `li` 가 홀로 서지 않는다', () => {
      renderTree(true);
      const row = portalRow('사람 #1');
      expect(row.tagName).toBe('LI');
      expect(row.parentElement).toHaveClass('klid-object-rows');
    });
  });

  describe('③ 배선은 두 채널이 같은 것을 쓴다', () => {
    it.each([true, false])('★고르기가 같은 스토어를 부른다 (portalMode=%s)', async (portalMode) => {
      const user = userEvent.setup();
      renderTree(portalMode);
      await user.click(screen.getByRole('button', { name: '사람 #1 선택' }));
      expect(useLabelStore.getState().selectedLabelId).toBe('a1');
    });

    it.each([true, false])('★숨김 토글이 같은 스토어를 부른다 (portalMode=%s)', async (portalMode) => {
      const user = userEvent.setup();
      renderTree(portalMode);
      await user.click(screen.getByRole('button', { name: '사람 #1 숨김' }));
      expect(useLabelStore.getState().hiddenLabelIds.has('a1')).toBe(true);
    });

    it('★잠긴 객체는 지울 수 없다 — 관제와 같은 판정', async () => {
      const user = userEvent.setup();
      renderTree(true);
      await user.click(screen.getByRole('button', { name: '사람 #1 잠금' }));
      const row = portalRow('사람 #1');
      expect(within(row).getByRole('button', { name: '객체 삭제' })).toBeDisabled();
    });

    it('★삭제 걸음이 늘 보인다 — 얹어야 드러나면 손가락 입력에 닿지 않는다', () => {
      renderTree(true);
      const row = portalRow('사람 #2');
      const del = within(row).getByRole('button', { name: '객체 삭제' });
      expect(del).toBeEnabled();
      // 관제처럼 `opacity-0` 으로 숨겨 두지 않는다.
      expect(del.className).not.toMatch(/opacity-0/);
    });
  });

  describe('④ 포털에 없는 걸음은 그대로 없다', () => {
    it('트랙 이름 바꾸기·트랙 삭제는 포털에 서지 않는다', () => {
      renderTree(true);
      expect(screen.queryByRole('button', { name: /트랙 ID 변경/ })).toBeNull();
      expect(screen.queryByRole('button', { name: /트랙 삭제/ })).toBeNull();
    });

    it('양성 대조 — 관제에서는 그 걸음이 실제로 선다', () => {
      renderTree(false);
      // 0건 단언이 «애초에 없어서» 통과하는 것이 아님을 반대쪽으로 확인한다.
      expect(screen.getAllByRole('button', { name: /트랙 ID 변경/ }).length).toBeGreaterThan(0);
    });
  });
});
