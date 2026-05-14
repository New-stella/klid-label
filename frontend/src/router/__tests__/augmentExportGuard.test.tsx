import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { render, screen } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';

import { RoleGuard } from '@/router/guards';
import { useAuthStore } from '@/stores/useAuthStore';
import { Role } from '@/lib/api/types';

function renderGuard(initialPath: string) {
  return render(
    <MemoryRouter initialEntries={[initialPath]}>
      <Routes>
        <Route
          path="/augment"
          element={
            <RoleGuard allow={[Role.REVIEWER]}>
              <div>AUGMENT_PAGE</div>
            </RoleGuard>
          }
        />
        <Route
          path="/augment/result/:jobId"
          element={
            <RoleGuard allow={[Role.REVIEWER]}>
              <div>AUGMENT_RESULT_PAGE</div>
            </RoleGuard>
          }
        />
        <Route
          path="/generate/result/:jobId"
          element={
            <RoleGuard allow={[Role.REVIEWER]}>
              <div>GENERATE_RESULT_PAGE</div>
            </RoleGuard>
          }
        />
        <Route path="/forbidden" element={<div>FORBIDDEN_PAGE</div>} />
        <Route path="/ingress" element={<div>INGRESS_PAGE</div>} />
      </Routes>
    </MemoryRouter>,
  );
}

describe('augment/generate 라우트 RoleGuard', () => {
  beforeEach(() => {
    useAuthStore.getState().clear();
  });

  afterEach(() => {
    useAuthStore.getState().clear();
  });

  it('WORKER가_augment_generate_접근시_forbidden', () => {
    useAuthStore.setState({
      token: 'tok',
      claims: { sub: 'u', role: 'WORKER', channel: 'INTERNAL', exp: 9999999999 },
    });

    const { unmount: u1 } = renderGuard('/augment');
    expect(screen.getByText('FORBIDDEN_PAGE')).toBeInTheDocument();
    u1();

    const { unmount: u2 } = renderGuard('/augment/result/100');
    expect(screen.getByText('FORBIDDEN_PAGE')).toBeInTheDocument();
    u2();

    renderGuard('/generate/result/555');
    expect(screen.getByText('FORBIDDEN_PAGE')).toBeInTheDocument();
  });

  it('REVIEWER는_augment_generate_경로_접근_허용', () => {
    useAuthStore.setState({
      token: 'tok',
      claims: { sub: 'u', role: 'REVIEWER', channel: 'INTERNAL', exp: 9999999999 },
    });

    const { unmount: u1 } = renderGuard('/augment');
    expect(screen.getByText('AUGMENT_PAGE')).toBeInTheDocument();
    u1();

    const { unmount: u2 } = renderGuard('/augment/result/100');
    expect(screen.getByText('AUGMENT_RESULT_PAGE')).toBeInTheDocument();
    u2();

    renderGuard('/generate/result/555');
    expect(screen.getByText('GENERATE_RESULT_PAGE')).toBeInTheDocument();
  });
});
