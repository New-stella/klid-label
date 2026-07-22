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

  it('해상도파생이_사용자문구로_렌더되고_기술코드는_숨긴다', () => {
    render(
      <MemoryRouter>
        <JobCard
          job={{ ...baseJob, resolutionTypes: ['RESL_720P', 'RESL_1080P'] }}
        />
      </MemoryRouter>,
    );
    expect(
      screen.getByTestId('job-card-resolution-100-RESL_720P'),
    ).toHaveTextContent('해상도 720p');
    expect(
      screen.getByTestId('job-card-resolution-100-RESL_1080P'),
    ).toHaveTextContent('해상도 1080p');
    // 화면 문구에 기술코드(RESL_*) 노출 금지
    expect(screen.queryByText(/RESL_/)).not.toBeInTheDocument();
  });

  it('해상도파생_이력카드에_검수_채택_거부_버튼이_없다', () => {
    render(
      <MemoryRouter>
        <JobCard job={{ ...baseJob, types: [], resolutionTypes: ['RESL_720P'] }} />
      </MemoryRouter>,
    );
    expect(
      screen.queryByRole('button', { name: /채택|승인|거부|반려/ }),
    ).not.toBeInTheDocument();
  });

  it('증강없이_해상도파생만있는_잡도_카드에_표시되고_파생구분자를_노출한다', () => {
    render(
      <MemoryRouter>
        <JobCard
          job={{ ...baseJob, types: [], resolutionTypes: ['RESL_480P'] }}
        />
      </MemoryRouter>,
    );
    expect(screen.getByTestId('job-card-100')).toBeInTheDocument();
    expect(
      screen.getByTestId('job-card-resolution-100-RESL_480P'),
    ).toHaveTextContent('해상도 480p');
    expect(
      screen.getByTestId('job-card-derivative-tag-100'),
    ).toHaveTextContent('파생');
  });
});
