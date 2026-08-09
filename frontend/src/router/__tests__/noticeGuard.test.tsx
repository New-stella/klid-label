import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { render, screen } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';

import { RoleGuard } from '@/router/guards';
import { useAuthStore } from '@/stores/useAuthStore';
import { Role } from '@/lib/api/types';

/**
 * 공지 작성/수정은 전용 화면으로 승격되면서 **직접 진입 가능한 URL** 을 갖게 됐다.
 * 모달일 때는 REVIEWER 에게만 열리는 버튼이 사실상 유일한 진입 관문이었지만, 이제는 URL 을
 * 아는 사람이 주소창으로 바로 들어올 수 있다 — 역할 가드가 라우트에 붙어 있어야 한다.
 * 라우터 구성(router/index.tsx)이 이 두 경로에 REVIEWER 전용 가드를 쓰는 것과 짝이다.
 */
function renderNoticeRoutes(initialPath: string) {
  return render(
    <MemoryRouter initialEntries={[initialPath]}>
      <Routes>
        <Route
          path="/notice"
          element={
            <RoleGuard allow={[Role.REVIEWER, Role.WORKER]}>
              <div>NOTICE_LIST_PAGE</div>
            </RoleGuard>
          }
        />
        <Route
          path="/notice/new"
          element={
            <RoleGuard allow={[Role.REVIEWER]}>
              <div>NOTICE_CREATE_PAGE</div>
            </RoleGuard>
          }
        />
        <Route
          path="/notice/:id"
          element={
            <RoleGuard allow={[Role.REVIEWER, Role.WORKER]}>
              <div>NOTICE_DETAIL_PAGE</div>
            </RoleGuard>
          }
        />
        <Route
          path="/notice/:id/edit"
          element={
            <RoleGuard allow={[Role.REVIEWER]}>
              <div>NOTICE_EDIT_PAGE</div>
            </RoleGuard>
          }
        />
        <Route path="/forbidden" element={<div>FORBIDDEN_PAGE</div>} />
        <Route path="/ingress" element={<div>INGRESS_PAGE</div>} />
      </Routes>
    </MemoryRouter>,
  );
}

function setRole(role: 'REVIEWER' | 'WORKER') {
  useAuthStore.setState({
    claims: { sub: 'u', role, channel: 'INTERNAL', exp: 9999999999 },
  });
}

describe('notice 라우트 RoleGuard', () => {
  beforeEach(() => {
    useAuthStore.getState().clear();
  });

  afterEach(() => {
    useAuthStore.getState().clear();
  });

  it('WORKER가_공지_작성_URL로_직접_진입하면_forbidden', () => {
    setRole('WORKER');
    renderNoticeRoutes('/notice/new');
    expect(screen.getByText('FORBIDDEN_PAGE')).toBeInTheDocument();
  });

  it('WORKER가_공지_수정_URL로_직접_진입하면_forbidden', () => {
    setRole('WORKER');
    renderNoticeRoutes('/notice/7/edit');
    expect(screen.getByText('FORBIDDEN_PAGE')).toBeInTheDocument();
  });

  it('WORKER도_공지_목록과_상세는_열람한다', () => {
    setRole('WORKER');
    const { unmount } = renderNoticeRoutes('/notice');
    expect(screen.getByText('NOTICE_LIST_PAGE')).toBeInTheDocument();
    unmount();

    renderNoticeRoutes('/notice/7');
    expect(screen.getByText('NOTICE_DETAIL_PAGE')).toBeInTheDocument();
  });

  it('REVIEWER는_작성_수정_화면에_진입한다', () => {
    setRole('REVIEWER');
    const { unmount } = renderNoticeRoutes('/notice/new');
    expect(screen.getByText('NOTICE_CREATE_PAGE')).toBeInTheDocument();
    unmount();

    renderNoticeRoutes('/notice/7/edit');
    expect(screen.getByText('NOTICE_EDIT_PAGE')).toBeInTheDocument();
  });

  it('작성_경로는_게시글_id로_해석되지_않는다', () => {
    // ':id' 가 'new' 를 먹으면 REVIEWER 전용 화면이 전원 열람 가능한 상세로 새어 나간다.
    setRole('REVIEWER');
    renderNoticeRoutes('/notice/new');
    expect(screen.queryByText('NOTICE_DETAIL_PAGE')).toBeNull();
  });
});
