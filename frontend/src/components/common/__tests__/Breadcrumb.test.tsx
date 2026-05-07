import { describe, expect, it } from 'vitest';
import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';

import { Breadcrumb } from '../Breadcrumb';

describe('Breadcrumb', () => {
  it('Breadcrumb_마지막_항목_aria_current_page', () => {
    render(
      <MemoryRouter>
        <Breadcrumb
          items={[
            { label: '홈', href: '/' },
            { label: '영상', href: '/videos' },
            { label: '상세' },
          ]}
        />
      </MemoryRouter>,
    );
    const last = screen.getByText('상세');
    expect(last).toHaveAttribute('aria-current', 'page');
  });
});
