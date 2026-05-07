import { type ReactElement, type ReactNode } from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, type RenderOptions } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';

interface ProvidersOptions extends Omit<RenderOptions, 'wrapper'> {
  initialEntries?: string[];
  routes?: { path: string; element: ReactNode }[];
  queryClient?: QueryClient;
}

export function createTestQueryClient() {
  return new QueryClient({
    defaultOptions: {
      queries: {
        retry: false,
        gcTime: 0,
        staleTime: 0,
        refetchOnWindowFocus: false,
      },
      mutations: { retry: false },
    },
  });
}

/**
 * 테스트용 React Query + Router providers 래퍼.
 * routes 옵션 사용 시 다중 경로 매칭 지원.
 */
export function renderWithProviders(ui: ReactElement, options: ProvidersOptions = {}) {
  const {
    initialEntries = ['/'],
    routes,
    queryClient = createTestQueryClient(),
    ...rest
  } = options;

  const wrapper = ({ children }: { children: ReactNode }) => (
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={initialEntries}>
        {routes ? (
          <Routes>
            {routes.map((r) => (
              <Route key={r.path} path={r.path} element={r.element} />
            ))}
            <Route path="*" element={children} />
          </Routes>
        ) : (
          children
        )}
      </MemoryRouter>
    </QueryClientProvider>
  );

  return { ...render(ui, { wrapper, ...rest }), queryClient };
}
