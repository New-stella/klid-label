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

  // ── UI-018 회귀 가드: 캡션이 상태를 함께 적는다(색 단독 구분 금지) ──────────
  // ★ 점은 4상태 모두 모양·크기가 같다 — 상태를 나르는 축이 색뿐이면 grayscale 에서
  //   완료(초록)와 실패(빨강)가 동일해진다(적록색약이 겪는 쌍). 캡션의 상태 문구가
  //   색을 대신하는 유일한 구분 수단이므로, 그것이 빠지면 이 가드가 실패해야 한다.
  it('★각_단계_캡션이_단계명과_상태를_함께_적는다_색_단독_구분_금지', () => {
    render(<BatchStageIndicator stages={stages} />);

    // 캡션은 두 줄(단계명 / 상태)이라 textContent 를 이으면 공백이 없다 — 줄 단위로 본다.
    const captionOf = (name: string): [string, string] => [
      (screen.getByTestId(`batch-stage-name-${name}`).textContent ?? '').trim(),
      (screen.getByTestId(`batch-stage-status-${name}`).textContent ?? '').trim(),
    ];

    // 완료 / 진행 중 / 대기 — 4상태 중 이 stages 에 등장하는 3종
    expect(captionOf('DEIDENTIFY')).toEqual(['비식별', '완료']);
    expect(captionOf('FRAME_EXTRACT')).toEqual(['프레임추출', '진행 중']);
    expect(captionOf('YOLO')).toEqual(['AI 탐지', '대기']);

    // 단계명만 있고 상태가 빠지는 회귀를 막는다(그 순간 상태 축이 색 단독이 된다).
    stages.forEach((s) => {
      expect(screen.getByTestId(`batch-stage-status-${s.name}`).textContent).toBeTruthy();
    });
  });

  it('★실패_단계도_캡션에_상태가_적힌다_완료와_회색조에서_구분된다', () => {
    const failed: BatchStageItem[] = [
      { name: 'DEIDENTIFY', status: 'DONE', progress: null },
      { name: 'MARKING', status: 'FAIL', progress: null },
    ];
    render(<BatchStageIndicator stages={failed} />);

    // 색을 걷어내도(=텍스트만 남겨도) 완료와 실패가 서로 다른 문자열이다.
    expect(screen.getByTestId('batch-stage-status-DEIDENTIFY')).toHaveTextContent('완료');
    expect(screen.getByTestId('batch-stage-status-MARKING')).toHaveTextContent('실패');
  });

  it('캡션_상태_문구와_aria_live_안내가_같은_어휘를_쓴다', () => {
    // 두 축이 상수를 복제하면 한쪽만 갱신돼 보는 것과 듣는 것이 갈린다.
    render(<BatchStageIndicator stages={stages} />);
    const caption = screen.getByTestId('batch-stage-status-FRAME_EXTRACT').textContent ?? '';
    // 빈 캡션은 아래 substring 단언을 무조건 통과시킨다 — 먼저 존재를 못 박는다.
    expect(caption).not.toBe('');
    expect(screen.getByTestId('batch-stage-live')).toHaveTextContent(`프레임추출 ${caption}`);
  });

  it('stages가_빈배열이면_아무것도_렌더하지_않는다_배지폴백', () => {
    const { container } = render(<BatchStageIndicator stages={[]} />);
    expect(container.firstChild).toBeNull();
    expect(screen.queryByTestId('batch-stage-indicator')).not.toBeInTheDocument();
  });
});
