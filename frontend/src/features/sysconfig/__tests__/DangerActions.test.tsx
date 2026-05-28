// DangerActions 컴포넌트 — TDD RED 단계.
//
// BE API 없음 확인 완료 — 현재 placeholder(운영 도구 이관 예정) 동작 검증.
//
// given/when/then 구조 필수.

import { describe, expect, it } from 'vitest';
import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { renderWithProviders } from '@/test/renderWithProviders';

import { DangerActions } from '../components/DangerActions';

describe('DangerActions', () => {
  it('위험_액션_운영도구_이관_안내_표시', () => {
    // given: DangerActions 렌더
    renderWithProviders(<DangerActions />);

    // then: 운영 도구 이관 예정 안내 배너가 표시됨
    expect(
      screen.getByText(/운영 도구로 이관 예정/),
    ).toBeInTheDocument();
  });

  it('위험_액션_3건_버튼_렌더링', () => {
    // given: DangerActions 렌더
    renderWithProviders(<DangerActions />);

    // then: 3건의 위험 액션 버튼이 표시됨
    expect(screen.getByRole('button', { name: /시스템 초기화/ })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /배치 큐 초기화/ })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /캐시 삭제/ })).toBeInTheDocument();
  });

  it('위험_액션_확인_다이얼로그_placeholder_토스트', async () => {
    // given: DangerActions 렌더
    const user = userEvent.setup();
    renderWithProviders(<DangerActions />);

    // when: 캐시 삭제 버튼 클릭
    await user.click(screen.getByRole('button', { name: /캐시 삭제/ }));

    // then: 확인 다이얼로그 표시
    expect(screen.getByText(/이 작업은 되돌릴 수 없습니다/)).toBeInTheDocument();

    // when: 실행 버튼 클릭
    await user.click(screen.getByRole('button', { name: '실행' }));

    // then: placeholder 토스트 표시 (실제 API 호출 없음)
    // toast는 store에 push되므로 다이얼로그가 닫히는 것으로 검증
    // (ConfirmDialog의 onConfirm 호출 → setOpen(null) → 모달 닫힘)
    expect(screen.queryByText(/이 작업은 되돌릴 수 없습니다/)).not.toBeInTheDocument();
  });
});
