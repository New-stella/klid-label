import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { render, screen } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';

import { RoleGuard } from '@/router/guards';
import { useAuthStore } from '@/stores/useAuthStore';
import { Role } from '@/lib/api/types';

function renderReview(initialPath: string) {
  return render(
    <MemoryRouter initialEntries={[initialPath]}>
      <Routes>
        <Route
          path="/review"
          element={
            <RoleGuard allow={[Role.REVIEWER]}>
              <div>REVIEW_LIST_PAGE</div>
            </RoleGuard>
          }
        />
        <Route
          path="/review/:id"
          element={
            <RoleGuard allow={[Role.REVIEWER]}>
              <div>REVIEW_PAGE</div>
            </RoleGuard>
          }
        />
        <Route path="/forbidden" element={<div>FORBIDDEN_PAGE</div>} />
        <Route path="/ingress" element={<div>INGRESS_PAGE</div>} />
      </Routes>
    </MemoryRouter>,
  );
}

describe('review 라우트 RoleGuard', () => {
  beforeEach(() => {
    useAuthStore.getState().clear();
  });

  afterEach(() => {
    useAuthStore.getState().clear();
  });

  it('WORKER_권한으로_/review_접근시_/forbidden', () => {
    useAuthStore.setState({
      token: 'tok',
      claims: { sub: 'u', role: 'WORKER', channel: 'INTERNAL', exp: 9999999999 },
    });
    renderReview('/review');
    expect(screen.getByText('FORBIDDEN_PAGE')).toBeInTheDocument();
  });

  it('WORKER_권한으로_/review/:id_접근시_/forbidden', () => {
    useAuthStore.setState({
      token: 'tok',
      claims: { sub: 'u', role: 'WORKER', channel: 'INTERNAL', exp: 9999999999 },
    });
    renderReview('/review/10');
    expect(screen.getByText('FORBIDDEN_PAGE')).toBeInTheDocument();
  });

  it('REVIEWER는_review_및_review_id_접근_허용', () => {
    useAuthStore.setState({
      token: 'tok',
      claims: { sub: 'u', role: 'REVIEWER', channel: 'INTERNAL', exp: 9999999999 },
    });
    const { unmount } = renderReview('/review');
    expect(screen.getByText('REVIEW_LIST_PAGE')).toBeInTheDocument();
    unmount();

    renderReview('/review/10');
    expect(screen.getByText('REVIEW_PAGE')).toBeInTheDocument();
  });
});
