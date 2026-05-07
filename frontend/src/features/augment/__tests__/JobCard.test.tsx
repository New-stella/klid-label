import { describe, expect, it } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';

import { JobCard } from '../components/JobCard';
import type { AugmentJob } from '../types';

const baseJob: AugmentJob = {
  jobId: 100,
  videoId: 10,
  cctvName: 'CCTV-A',
  types: ['WINTER', 'NIGHT'],
  status: 'COMPLETED',
  requestedAt: '2026-05-07T10:00:00Z',
  videoCount: 3,
};

describe('JobCard', () => {
  it('잡_카드_클릭시_augment_result_navigate', async () => {
    const user = userEvent.setup();
    render(
      <MemoryRouter initialEntries={['/augment']}>
        <Routes>
          <Route path="/augment" element={<JobCard job={baseJob} />} />
          <Route
            path="/augment/result/:jobId"
            element={<div>AUGMENT_RESULT_PAGE</div>}
          />
        </Routes>
      </MemoryRouter>,
    );
    await user.click(screen.getByTestId('job-card-100'));
    expect(screen.getByText('AUGMENT_RESULT_PAGE')).toBeInTheDocument();
  });

  it('잡_카드_유형_뱃지_및_상태_뱃지_노출', () => {
    render(
      <MemoryRouter>
        <JobCard job={baseJob} />
      </MemoryRouter>,
    );
    expect(screen.getByText('CCTV-A')).toBeInTheDocument();
    expect(screen.getByTestId('job-card-type-100-WINTER')).toBeInTheDocument();
    expect(screen.getByTestId('job-card-type-100-NIGHT')).toBeInTheDocument();
  });
});
