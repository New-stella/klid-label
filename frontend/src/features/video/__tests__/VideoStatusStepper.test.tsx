import { describe, expect, it } from 'vitest';
import { render, screen } from '@testing-library/react';

import { VideoStatusStepper } from '../components/VideoStatusStepper';
const stages = [
  { stage: 'FRAME_EXTRACT', label: '프레임 추출', status: 'COMPLETED', progressPercent: 100 },
  { stage: 'DEIDENTIFY', label: '비식별화', status: 'COMPLETED', progressPercent: 100 },
  { stage: 'YOLO', label: 'YOLO', status: 'IN_PROGRESS', progressPercent: 60 },
  { stage: 'SAM2', label: 'SAM2', status: 'PENDING', progressPercent: 0 },
  { stage: 'VLM_VERIFY', label: 'VLM 검증', status: 'PENDING', progressPercent: 0 },
];

describe('VideoStatusStepper', () => {
  it('처리_현황_단계별_진행률_바_렌더링', () => {
    render(<VideoStatusStepper stages={stages} />);

    // 5개 단계 모두 progressbar 노출
    const bars = screen.getAllByRole('progressbar');
    expect(bars).toHaveLength(5);

    // YOLO 단계는 60% 진행
    const yoloBar = bars.find(
      (b) => b.getAttribute('aria-label') === 'YOLO 진행률',
    );
    expect(yoloBar).toBeDefined();
    expect(yoloBar).toHaveAttribute('aria-valuenow', '60');
  });

  it('각_단계의_label_렌더', () => {
    render(<VideoStatusStepper stages={stages} />);
    expect(screen.getByText('프레임 추출')).toBeInTheDocument();
    expect(screen.getByText('비식별화')).toBeInTheDocument();
    expect(screen.getByText('YOLO')).toBeInTheDocument();
    expect(screen.getByText('SAM2')).toBeInTheDocument();
    expect(screen.getByText('VLM 검증')).toBeInTheDocument();
  });
});
