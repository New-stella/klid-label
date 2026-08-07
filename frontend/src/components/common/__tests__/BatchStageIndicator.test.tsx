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

  // ── UI-018 회귀 가드: 접근성 라이브 리전 ──────────────────────────────────
  // 시각적으로는 아이콘 색만 바뀌므로, 라이브 리전이 없으면 스크린리더는 단계 전환에 침묵한다.
  it('진행_중인_단계를_aria_live_영역으로_안내한다', () => {
    render(<BatchStageIndicator stages={stages} />);
    const live = screen.getByTestId('batch-stage-live');
    expect(live).toHaveAttribute('aria-live', 'polite');
    // FRAME_EXTRACT 가 PROGRESS 이므로 그 단계명 + 상태 문구
    expect(live).toHaveTextContent('프레임추출 진행 중');
    // 화면에는 보이지 않는다(sr-only)
    expect(live.className).toMatch(/sr-only/);
  });

  it('라이브_리전에도_기술_모델명을_노출하지_않는다', () => {
    const yoloRunning: BatchStageItem[] = [
      { name: 'DEIDENTIFY', status: 'DONE', progress: null },
      { name: 'YOLO', status: 'PROGRESS', progress: null },
    ];
    render(<BatchStageIndicator stages={yoloRunning} />);
    const live = screen.getByTestId('batch-stage-live');
    expect(live).toHaveTextContent('AI 탐지 진행 중');
    expect(live.textContent).not.toContain('YOLO');
  });

  it('진행_중_단계가_없으면_실패나_마지막_완료_단계를_안내한다', () => {
    const failed: BatchStageItem[] = [
      { name: 'DEIDENTIFY', status: 'DONE', progress: null },
      { name: 'MARKING', status: 'FAIL', progress: null },
      { name: 'VLM', status: 'PENDING', progress: null },
    ];
    const { unmount } = render(<BatchStageIndicator stages={failed} />);
    expect(screen.getByTestId('batch-stage-live')).toHaveTextContent('마킹 실패');
    unmount();

    const allDone: BatchStageItem[] = [
      { name: 'DEIDENTIFY', status: 'DONE', progress: null },
      { name: 'INTERPOLATE', status: 'DONE', progress: null },
    ];
    render(<BatchStageIndicator stages={allDone} />);
    expect(screen.getByTestId('batch-stage-live')).toHaveTextContent('보간 완료');
  });

  it('stages가_빈배열이면_아무것도_렌더하지_않는다_배지폴백', () => {
    const { container } = render(<BatchStageIndicator stages={[]} />);
    expect(container.firstChild).toBeNull();
    expect(screen.queryByTestId('batch-stage-indicator')).not.toBeInTheDocument();
  });
});
