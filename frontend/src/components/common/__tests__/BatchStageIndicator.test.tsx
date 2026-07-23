import { describe, expect, it } from 'vitest';
import { render, screen } from '@testing-library/react';

import { BatchStageIndicator } from '../BatchStageIndicator';
import type { BatchStageItem } from '@/features/video/types';

// BE canonical stages (name = BatchStage enum name). progress nullable.
const stages: BatchStageItem[] = [
  { name: 'DEIDENTIFY', status: 'DONE', progress: null },
  { name: 'MARKING', status: 'DONE', progress: null },
  { name: 'VLM', status: 'DONE', progress: null },
  { name: 'FRAME_EXTRACT', status: 'PROGRESS', progress: null },
  { name: 'YOLO', status: 'PENDING', progress: null },
  { name: 'SAM2', status: 'PENDING', progress: null },
  { name: 'INTERPOLATE', status: 'PENDING', progress: null },
];

describe('BatchStageIndicator', () => {
  it('stages가_있으면_BE순서대로_단계라벨을_렌더한다', () => {
    render(<BatchStageIndicator stages={stages} />);

    // BE name → 한글 라벨 매핑 (canonical 순서 그대로)
    expect(screen.getByText('비식별')).toBeInTheDocument();
    expect(screen.getByText('마킹')).toBeInTheDocument();
    expect(screen.getByText('VLM')).toBeInTheDocument();
    expect(screen.getByText('프레임추출')).toBeInTheDocument();
    expect(screen.getByText('보간')).toBeInTheDocument();
  });

  it('YOLO는_AI_탐지_SAM2는_AI_분할로_표기하고_기술모델명은_노출하지_않는다', () => {
    const { container } = render(<BatchStageIndicator stages={stages} />);

    // 사용자 문구: AI 탐지 / AI 분할
    expect(screen.getByText('AI 탐지')).toBeInTheDocument();
    expect(screen.getByText('AI 분할')).toBeInTheDocument();

    // 문구 규칙: 화면 텍스트에 기술 모델명(YOLO/SAM2) 미노출
    expect(container.textContent).not.toContain('YOLO');
    expect(container.textContent).not.toContain('SAM2');
  });

  it('BE_name과_FE_라벨키가_일치해_status_룩업이_정상_렌더된다', () => {
    // 진행 단계(FRAME_EXTRACT=PROGRESS)와 완료 단계(DEIDENTIFY=DONE) 라벨이 모두 렌더되면
    // name↔라벨 매핑이 맞아 각 status 아이콘이 정상 노출됨을 의미한다.
    render(<BatchStageIndicator stages={stages} />);
    expect(screen.getByTestId('batch-stage-indicator')).toBeInTheDocument();
    // 7단계 라벨 전부 존재
    ['비식별', '마킹', 'VLM', '프레임추출', 'AI 탐지', 'AI 분할', '보간'].forEach((label) => {
      expect(screen.getByText(label)).toBeInTheDocument();
    });
  });

  it('stages가_빈배열이면_아무것도_렌더하지_않는다_배지폴백', () => {
    const { container } = render(<BatchStageIndicator stages={[]} />);
    expect(container.firstChild).toBeNull();
    expect(screen.queryByTestId('batch-stage-indicator')).not.toBeInTheDocument();
  });
});
