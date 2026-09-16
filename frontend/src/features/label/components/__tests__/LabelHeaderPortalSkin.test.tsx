// 회귀 가드 — 라벨링 머리 줄의 포털 꼴. [@design SCREEN-029]
//
// 부모 포털 시안(`AuthoringLabelingView` 의 `header`)은 **이름 · 저장 상태 · 뒤로** 세 자리다.
// 관제는 **✕ 닫기 · 이름 · 저장 상태 · (신고·도움말·검수제출)** 로 구성이 다르다.
// 두 꼴이 갈리는 것이 확정 사양이라, 한쪽을 다른 쪽에 맞추려는 변경을 이 가드가 막는다.
//
// ⚠ 이 가드는 «예쁜가»를 말하지 않는다 — 어느 자리에 무엇이 서는가만 고정한다.

import { describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';

import { LabelHeader } from '../LabelHeader';

function renderHeader(overrides: Partial<Parameters<typeof LabelHeader>[0]> = {}) {
  const props = {
    currentFrame: 0,
    dirty: false,
    ...overrides,
  } as Parameters<typeof LabelHeader>[0];
  return render(
    <MemoryRouter>
      <LabelHeader {...props} />
    </MemoryRouter>,
  );
}

describe('라벨링 머리 줄 — 포털 꼴', () => {
  describe('① 이탈 경로가 오른쪽 「뒤로」로 옮겨간다', () => {
    it('★포털은 「뒤로」 하나이고 왼쪽 ✕ 를 두지 않는다', () => {
      renderHeader({ portalMode: true, cctvName: '실종자추적.mp4' });
      const back = screen.getByRole('button', { name: '뒤로가기' });
      expect(back).toHaveTextContent('뒤로');
      // ✕ 만 있던 구 꼴로 되돌아가지 않는다 — 이탈 경로는 하나다.
      expect(screen.getAllByRole('button', { name: '뒤로가기' })).toHaveLength(1);
    });

    it('★관제는 종전 ✕ 그대로다 — 한쪽에 맞추지 않는다', () => {
      renderHeader({ cctvName: '실종자추적.mp4' });
      const back = screen.getByRole('button', { name: '뒤로가기' });
      // 관제 ✕ 는 글자를 담지 않는다(아이콘 하나).
      expect(back).toHaveTextContent('');
    });

    it('두 채널 모두 이탈 콜백을 그대로 부른다 — 자리만 옮겼고 동작은 같다', async () => {
      const user = userEvent.setup();
      for (const portalMode of [true, false]) {
        const onClose = vi.fn();
        const { unmount } = renderHeader({ portalMode, onClose });
        await user.click(screen.getByRole('button', { name: '뒤로가기' }));
        expect(onClose, `portalMode=${portalMode}`).toHaveBeenCalledTimes(1);
        unmount();
      }
    });
  });

  describe('② 저장 상태는 세 갈래이고 진행 중이 미저장을 이긴다', () => {
    it.each([
      { name: '저장 중', props: { saving: true, dirty: true }, text: '저장 중' },
      { name: '편집 중', props: { saving: false, dirty: true }, text: '편집 중' },
      { name: '저장됨', props: { saving: false, dirty: false }, text: '저장됨' },
    ])('★$name', ({ props, text }) => {
      renderHeader({ portalMode: true, ...props });
      expect(screen.getByText(text)).toBeInTheDocument();
    });

    it('★진행 중이 미저장보다 앞선다 — 뒤에 두면 진행 표시가 영영 뜨지 않는다', () => {
      // 저장은 «미저장 상태에서» 시작된다. dirty 를 먼저 보면 저장 중이 가려진다.
      renderHeader({ portalMode: true, saving: true, dirty: true });
      expect(screen.getByText('저장 중')).toBeInTheDocument();
      expect(screen.queryByText('편집 중')).toBeNull();
    });

    it('상태가 바뀌면 소리로도 알린다', () => {
      renderHeader({ portalMode: true });
      // 킷 상태 줄이 `role="status"` 를 진다 — 저장이 끝났는지를 눈으로만 확인할 수는 없다.
      expect(screen.getByRole('status')).toHaveTextContent('저장됨');
    });
  });

  describe('③ 포털에 서지 않는 것', () => {
    it('★도움말은 남는다 — 좌측 도구 칸의 미리 보기와 여는 표면이 다르다', () => {
      renderHeader({ portalMode: true, onHelpClick: vi.fn() });
      expect(screen.getByRole('button', { name: '단축키 도움말 전체 보기' })).toBeInTheDocument();
    });

    it('도움말 콜백이 없으면 그 자리를 두지 않는다', () => {
      renderHeader({ portalMode: true });
      expect(screen.queryByRole('button', { name: '단축키 도움말 전체 보기' })).toBeNull();
    });

    it('★이벤트 유형 배지를 그리지 않는다 — 포털 호출부가 값을 주지 않는다', () => {
      // 감추는 코드를 두지 않는다. 값이 없어 서지 않는 것이 맞다 —
      // 그래서 값이 «들어와도» 포털 머리 줄에는 배지 자리가 없다는 것을 함께 고정한다.
      renderHeader({ portalMode: true, eventType: 'EV01000101' });
      expect(screen.queryByText('EV01000101')).toBeNull();
    });
  });
});
