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
            path="/augment/result/:rawSn"
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

  /**
   * 그랜드퍼더링 — BE 가 기존 파생본의 `AUG_TYPE_CD` 를 백필하지 않았다(ADR-059).
   * 신규 `AUGMENT` 와 구 3종이 이력에 **혼재**하므로 표시 축이 둘 다 견뎌야 한다.
   */
  it('신규_AUGMENT_와_구_3종이_섞여_있어도_배지가_비지_않는다', () => {
    render(
      <MemoryRouter>
        <JobCard
          job={{ ...baseJob, types: ['AUGMENT', 'WINTER', 'NIGHT', 'RAIN'] }}
        />
      </MemoryRouter>,
    );

    const labels = ['AUGMENT', 'WINTER', 'NIGHT', 'RAIN'].map(
      (t) => screen.getByTestId(`job-card-type-100-${t}`).textContent?.trim() ?? '',
    );
    // 빈 배지가 하나도 없고 서로 구분된다(같은 문구로 뭉개지면 어떤 파생인지 알 수 없다).
    labels.forEach((label) => expect(label).not.toBe(''));
    expect(new Set(labels).size).toBe(4);
    expect(screen.getByTestId('job-card-type-100-AUGMENT')).toHaveTextContent(
      '증강 AI',
    );
  });

  /**
   * ★미지 코드 폴백 — **배지가 빈칸이 되지 않는다**.
   *
   * `typeLabelMap` 은 `Record` 라 모르는 키를 조회하면 `undefined` 를 돌려주고, React 는 그것을
   * **조용히 무시**한다. 즉 BE 가 `AUG_TYPE_CD` 를 다시 넓히면 오류도 경고도 없이 배지만
   * 빈칸이 된다 — 이번 라운드에서 종류를 단일값으로 좁힌 뒤라 넓어질 여지가 실재한다.
   *
   * ⚠ 이 축은 **알려진 코드만 쓰는 픽스처로는 한 번도 실행되지 않는다**. 폴백(`?? '증강'`)을
   * 지워도 나머지 케이스가 전부 통과하므로, 미지 코드를 흘리는 이 케이스가 유일한 가드다.
   * (`augTypeLabel.test` 의 `미지의_코드는_기술코드_노출없이_폴백` 과 같은 축을 표시 경로에 세운다)
   */
  it('미지의_증강_종류가_와도_배지가_빈칸이_되지_않고_기술코드도_새지_않는다', () => {
    // given: BE 가 나중에 넓힌 값 — 우리 표시 축 union 에 없는 코드다(그래서 캐스팅한다).
    const unknownTypes = ['SOMETHING_NEW'] as unknown as AugmentJob['types'];
    render(
      <MemoryRouter>
        <JobCard job={{ ...baseJob, types: unknownTypes }} />
      </MemoryRouter>,
    );

    // then: 배지는 존재하고, 비어 있지 않으며, 폴백 문구를 보인다.
    const badge = screen.getByTestId('job-card-type-100-SOMETHING_NEW');
    expect(badge.textContent?.trim()).not.toBe('');
    expect(badge).toHaveTextContent('증강');
    // 기술코드를 화면에 그대로 노출하지 않는다.
    expect(badge).not.toHaveTextContent('SOMETHING_NEW');
  });

  it('취소된_항목이_완료로_표시되지_않는다', () => {
    // given — BE 집계 COMPLETED 는 "전 항목이 종료(채택/반려/취소)" 라는 뜻이지 성공이 아니다.
    //         취소로 종결된 잡도 이 값으로 내려오며 BE 는 enum 을 확장하지 않는다.
    render(
      <MemoryRouter>
        <JobCard job={baseJob} />
      </MemoryRouter>,
    );

    // then — 성공을 단정하는 '완료' 대신 중립 문구로 표시한다(표시 축 보정)
    expect(screen.getByTestId('job-card-100')).toHaveTextContent('처리 종료');
    expect(screen.queryByText('완료')).not.toBeInTheDocument();
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
