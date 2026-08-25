import fs from 'node:fs';
import path from 'node:path';

import { describe, expect, it } from 'vitest';
import { render, screen } from '@testing-library/react';

import type { BatchStageItem } from '@/features/video/types';

import {
  BatchStageIndicator,
  COLLAPSED_BUNDLE_LABEL,
  bundleLabel,
  collapseStages,
  collapsedStatus,
  stageLabel,
} from '../BatchStageIndicator';

// BE canonical stages (name = BatchStage enum name). progress nullable.
// ★ BE 계약은 7단계 그대로다 — 접기는 표시 층에서만 일어난다(UI-018 v7).
const stages: BatchStageItem[] = [
  { name: 'DEIDENTIFY', status: 'DONE', progress: null },
  { name: 'MARKING', status: 'DONE', progress: null },
  { name: 'VLM', status: 'DONE', progress: null },
  { name: 'FRAME_EXTRACT', status: 'PROGRESS', progress: null },
  { name: 'YOLO', status: 'PENDING', progress: null },
  { name: 'SAM2', status: 'PENDING', progress: null },
  { name: 'INTERPOLATE', status: 'PENDING', progress: null },
];

const item = (name: string, status: BatchStageItem['status']): BatchStageItem => ({
  name,
  status,
  progress: null,
});

/**
 * `src` 아래 소스 파일 전수(테스트 제외) — 정적 가드가 「어디에도 없다」를 세는 축.
 *
 * 런타임 렌더 테스트는 "지금 무엇이 그려지는가"만 보므로, 지운 prop·주장이 **다른 파일에서**
 * 되살아나는 것을 잡지 못한다. 그 사각을 이 목록이 덮는다.
 */
function sourceFiles(): { abs: string; rel: string }[] {
  const srcDir = path.resolve(__dirname, '../../..');
  const out: { abs: string; rel: string }[] = [];
  const walk = (dir: string) => {
    for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
      const full = path.join(dir, entry.name);
      if (entry.isDirectory()) {
        if (entry.name === '__tests__' || entry.name === 'test') continue;
        walk(full);
      } else if (/\.tsx?$/.test(entry.name)) {
        out.push({ abs: full, rel: path.relative(srcDir, full).split(path.sep).join('/') });
      }
    }
  };
  walk(srcDir);
  return out;
}

/** 렌더된 칸(스텝)의 key 목록 — 접기 결과를 셀 수 있는 유일한 축. */
function renderedCellKeys(container: HTMLElement): string[] {
  return Array.from(container.querySelectorAll('[data-testid^="batch-stage-item-"]')).map((el) =>
    (el.getAttribute('data-testid') ?? '').replace('batch-stage-item-', ''),
  );
}

describe('BatchStageIndicator', () => {
  it('stages가_있으면_BE순서대로_단계라벨을_렌더한다', () => {
    render(<BatchStageIndicator stages={stages} />);

    // BE name → 한글 라벨 매핑 (canonical 순서 그대로)
    expect(screen.getByText('비식별')).toBeInTheDocument();
    expect(screen.getByText('마킹')).toBeInTheDocument();
    // ★ 구 기대값 'VLM' → 폐기. 기술 모델명 노출 금지(UI-018)로 「시계열」로 표시한다(코드는 유지).
    expect(screen.getByText('시계열')).toBeInTheDocument();
    expect(screen.getByText('프레임추출')).toBeInTheDocument();
    // ★ 회차 45 정정 — 오토라벨 세 단계(AI 탐지·AI 분할·보간)는 개별 칸이 아니라 한 칸이다.
    //   구 기대값 '보간' 칸 존재 → 폐기(UI-018 v7 이 5칸으로 접으라고 규정).
    expect(screen.getByText(COLLAPSED_BUNDLE_LABEL)).toBeInTheDocument();
  });

  it('기술모델명은_노출하지_않는다', () => {
    const { container } = render(<BatchStageIndicator stages={stages} />);

    // 문구 규칙: 화면 텍스트에 기술 모델명(YOLO/SAM2/VLM) 미노출
    expect(container.textContent).not.toContain('YOLO');
    expect(container.textContent).not.toContain('SAM2');
    expect(container.textContent).not.toContain('VLM');
  });

  it('BE_name과_FE_라벨키가_일치해_status_룩업이_정상_렌더된다', () => {
    // 진행 단계(FRAME_EXTRACT=PROGRESS)와 완료 단계(DEIDENTIFY=DONE) 라벨이 모두 렌더되면
    // name↔라벨 매핑이 맞아 각 status 점이 정상 노출됨을 의미한다.
    render(<BatchStageIndicator stages={stages} />);
    expect(screen.getByTestId('batch-stage-indicator')).toBeInTheDocument();
    // 표시 축 5칸 라벨 전부 존재 (구 기대값 7개 → 폐기)
    ['비식별', '마킹', '시계열', '프레임추출', COLLAPSED_BUNDLE_LABEL].forEach((label) => {
      expect(screen.getByText(label)).toBeInTheDocument();
    });
  });

  // ── UI-018 v7 회귀 가드: 오토라벨 세 단계를 한 칸으로 접는다 ────────────────
  // ★ 조작(건너뛰기·해제·재수행)이 오토라벨 묶음 단위로만 동작하는데 세 칸으로 보이면
  //   각 칸을 따로 조작할 수 있다고 읽힌다. 표시 단위를 조작 단위에 맞추는 것이 이 가드의 목적.
  describe('★오토라벨 묶음 접기(UI-018 v7)', () => {
    it('★7단계를_받으면_칸은_5개다', () => {
      const { container } = render(<BatchStageIndicator stages={stages} />);
      const keys = renderedCellKeys(container);
      expect(keys).toHaveLength(5);
      expect(keys).toEqual(['DEIDENTIFY', 'MARKING', 'VLM', 'FRAME_EXTRACT', 'AUTOLABEL']);
    });

    it('★접기는_표시_층에서만_한다_props_배열은_그대로다', () => {
      const received = [...stages];
      render(<BatchStageIndicator stages={received} />);
      // 컴포넌트가 입력 배열을 변형하면(정렬/삭제) BE 계약 소비자가 함께 깨진다.
      expect(received).toHaveLength(7);
      expect(received.map((s) => s.name)).toEqual(stages.map((s) => s.name));
    });

    it('★접은_칸은_오토라벨_묶음_자리에_놓인다_순서_보존', () => {
      const { container } = render(
        <BatchStageIndicator
          stages={[
            item('DEIDENTIFY', 'DONE'),
            item('YOLO', 'PROGRESS'),
            item('SAM2', 'PENDING'),
            item('INTERPOLATE', 'PENDING'),
          ]}
        />,
      );
      expect(renderedCellKeys(container)).toEqual(['DEIDENTIFY', 'AUTOLABEL']);
    });

    it('★오토라벨_멤버가_하나도_없으면_접은_칸을_만들지_않는다', () => {
      const { container } = render(
        <BatchStageIndicator
          stages={[item('DEIDENTIFY', 'DONE'), item('MARKING', 'PROGRESS')]}
        />,
      );
      expect(renderedCellKeys(container)).toEqual(['DEIDENTIFY', 'MARKING']);
      expect(screen.queryByText(COLLAPSED_BUNDLE_LABEL)).not.toBeInTheDocument();
    });

    it('★멤버가_일부만_와도_한_칸으로_접는다', () => {
      const { container } = render(
        <BatchStageIndicator stages={[item('DEIDENTIFY', 'DONE'), item('SAM2', 'FAIL')]} />,
      );
      expect(renderedCellKeys(container)).toEqual(['DEIDENTIFY', 'AUTOLABEL']);
      expect(screen.getByTestId('batch-stage-status-AUTOLABEL')).toHaveTextContent('실패');
    });

    it('★멤버_순서가_달라도_깨지지_않고_한_칸으로_접힌다', () => {
      const { container } = render(
        <BatchStageIndicator
          stages={[
            item('INTERPOLATE', 'PENDING'),
            item('DEIDENTIFY', 'DONE'),
            item('SAM2', 'PROGRESS'),
            item('YOLO', 'DONE'),
          ]}
        />,
      );
      // 첫 멤버가 나타난 자리에 접은 칸이 놓이고, 흩어진 나머지 멤버도 그 칸에 흡수된다.
      expect(renderedCellKeys(container)).toEqual(['AUTOLABEL', 'DEIDENTIFY']);
      expect(screen.getByTestId('batch-stage-status-AUTOLABEL')).toHaveTextContent('진행 중');
    });

    it('★캡션은_묶음명과_상태를_함께_적는다_색_단독_구분_금지', () => {
      render(
        <BatchStageIndicator
          stages={[item('YOLO', 'DONE'), item('SAM2', 'DONE'), item('INTERPOLATE', 'FAIL')]}
        />,
      );
      expect(screen.getByTestId('batch-stage-name-AUTOLABEL')).toHaveTextContent(
        COLLAPSED_BUNDLE_LABEL,
      );
      expect(screen.getByTestId('batch-stage-status-AUTOLABEL')).toHaveTextContent('실패');
    });
  });

  // ── UI-018 v7: 접은 칸의 상태 합성 (순수 함수) ────────────────────────────
  describe('★접은 칸 상태 합성(collapsedStatus)', () => {
    it('★하나라도_실패면_실패다', () => {
      expect(collapsedStatus([item('YOLO', 'DONE'), item('SAM2', 'FAIL')])).toBe('FAIL');
      // 실패가 진행 중보다 우선한다(실패를 진행 중으로 덮으면 사람이 못 본다).
      expect(collapsedStatus([item('YOLO', 'PROGRESS'), item('SAM2', 'FAIL')])).toBe('FAIL');
    });

    it('★실패가_없고_하나라도_진행중이면_진행중이다', () => {
      expect(
        collapsedStatus([item('YOLO', 'DONE'), item('SAM2', 'PROGRESS'), item('INTERPOLATE', 'PENDING')]),
      ).toBe('PROGRESS');
    });

    it('★셋_다_끝났으면_완료다', () => {
      expect(
        collapsedStatus([item('YOLO', 'DONE'), item('SAM2', 'DONE'), item('INTERPOLATE', 'DONE')]),
      ).toBe('DONE');
    });

    it('★전부_대기면_대기다', () => {
      expect(
        collapsedStatus([
          item('YOLO', 'PENDING'),
          item('SAM2', 'PENDING'),
          item('INTERPOLATE', 'PENDING'),
        ]),
      ).toBe('PENDING');
    });

    it('★일부만_완료면_완료가_아니라_대기다', () => {
      // "셋 다 끝났으면 완료" — 하나라도 안 끝났으면 완료로 적지 않는다(과대 보고 금지).
      expect(
        collapsedStatus([item('YOLO', 'DONE'), item('SAM2', 'PENDING'), item('INTERPOLATE', 'PENDING')]),
      ).toBe('PENDING');
    });
  });

  // ── UI-018 v7: 보조 표기(세부 단계) ───────────────────────────────────────
  describe('★접은 칸의 보조 표기', () => {
    it('★실패면_어느_세부_단계에서_실패했는지_적는다', () => {
      render(
        <BatchStageIndicator
          stages={[item('YOLO', 'DONE'), item('SAM2', 'DONE'), item('INTERPOLATE', 'FAIL')]}
        />,
      );
      expect(screen.getByTestId('batch-stage-note-AUTOLABEL')).toHaveTextContent('보간에서 실패');
    });

    it('★진행중이면_지금_어느_세부_단계인지_적는다', () => {
      render(
        <BatchStageIndicator
          stages={[item('YOLO', 'DONE'), item('SAM2', 'PROGRESS'), item('INTERPOLATE', 'PENDING')]}
        />,
      );
      expect(screen.getByTestId('batch-stage-note-AUTOLABEL')).toHaveTextContent('AI 분할 진행 중');
    });

    it('★세부_단계가_여럿_실패하면_BE_순서상_앞선_단계를_적는다', () => {
      // 뒤 단계는 앞 결과를 입력으로 받으므로 앞선 실패가 원인이다(사양 미규정 — 결정 근거를 고정).
      render(
        <BatchStageIndicator
          stages={[item('YOLO', 'FAIL'), item('SAM2', 'FAIL'), item('INTERPOLATE', 'PENDING')]}
        />,
      );
      expect(screen.getByTestId('batch-stage-note-AUTOLABEL')).toHaveTextContent('AI 탐지에서 실패');
    });

    it('★완료면_보조_표기를_두지_않는다', () => {
      render(
        <BatchStageIndicator
          stages={[item('YOLO', 'DONE'), item('SAM2', 'DONE'), item('INTERPOLATE', 'DONE')]}
        />,
      );
      expect(screen.queryByTestId('batch-stage-note-AUTOLABEL')).not.toBeInTheDocument();
    });

    it('★대기면_보조_표기를_두지_않는다', () => {
      render(
        <BatchStageIndicator
          stages={[
            item('YOLO', 'PENDING'),
            item('SAM2', 'PENDING'),
            item('INTERPOLATE', 'PENDING'),
          ]}
        />,
      );
      expect(screen.queryByTestId('batch-stage-note-AUTOLABEL')).not.toBeInTheDocument();
    });

    it('★접지_않은_단계에는_보조_표기가_없다', () => {
      render(<BatchStageIndicator stages={stages} />);
      expect(screen.queryByTestId('batch-stage-note-FRAME_EXTRACT')).not.toBeInTheDocument();
    });

    it('★보조_표기는_글자이며_백분율_진행률_바를_렌더하지_않는다', () => {
      const { container } = render(
        <BatchStageIndicator
          stages={[
            { name: 'YOLO', status: 'DONE', progress: 100 },
            { name: 'SAM2', status: 'PROGRESS', progress: 42 },
            { name: 'INTERPOLATE', status: 'PENDING', progress: 0 },
          ]}
        />,
      );
      expect(container.querySelector('progress')).toBeNull();
      expect(container.querySelector('[role="progressbar"]')).toBeNull();
      expect(container.textContent).not.toContain('%');
      expect(container.textContent).not.toContain('42');
    });
  });

  // ── UI-018 회귀 가드: 접근성 라이브 리전 ──────────────────────────────────
  // 시각적으로는 점 색만 바뀌므로, 라이브 리전이 없으면 스크린리더는 단계 전환에 침묵한다.
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
      item('DEIDENTIFY', 'DONE'),
      item('YOLO', 'PROGRESS'),
    ];
    render(<BatchStageIndicator stages={yoloRunning} />);
    const live = screen.getByTestId('batch-stage-live');
    // ★ 회차 45 정정 — 구 기대값 'AI 탐지 진행 중'(7칸 전제) → 폐기. 접은 칸 이름으로 알린다.
    expect(live).toHaveTextContent(`${COLLAPSED_BUNDLE_LABEL} 진행 중`);
    expect(live.textContent).not.toContain('YOLO');
  });

  it('★라이브_리전도_접은_칸의_세부_단계를_함께_알린다', () => {
    render(
      <BatchStageIndicator
        stages={[item('YOLO', 'DONE'), item('SAM2', 'DONE'), item('INTERPOLATE', 'FAIL')]}
      />,
    );
    const live = screen.getByTestId('batch-stage-live');
    expect(live).toHaveTextContent(`${COLLAPSED_BUNDLE_LABEL} 실패`);
    expect(live).toHaveTextContent('보간에서 실패');
  });

  it('진행_중_단계가_없으면_실패나_마지막_완료_단계를_안내한다', () => {
    const failed: BatchStageItem[] = [
      item('DEIDENTIFY', 'DONE'),
      item('MARKING', 'FAIL'),
      item('VLM', 'PENDING'),
    ];
    const { unmount } = render(<BatchStageIndicator stages={failed} />);
    expect(screen.getByTestId('batch-stage-live')).toHaveTextContent('마킹 실패');
    unmount();

    const allDone: BatchStageItem[] = [item('DEIDENTIFY', 'DONE'), item('INTERPOLATE', 'DONE')];
    render(<BatchStageIndicator stages={allDone} />);
    // ★ 회차 45 정정 — 구 기대값 '보간 완료' → 폐기. 보간은 접은 칸에 흡수된다.
    expect(screen.getByTestId('batch-stage-live')).toHaveTextContent(
      `${COLLAPSED_BUNDLE_LABEL} 완료`,
    );
  });

  // ── UI-018 회귀 가드: 캡션이 상태를 함께 적는다(색 단독 구분 금지) ──────────
  // ★ 점은 4상태 모두 모양·크기가 같다 — 상태를 나르는 축이 색뿐이면 grayscale 에서
  //   완료(초록)와 실패(빨강)가 동일해진다(적록색약이 겪는 쌍). 캡션의 상태 문구가
  //   색을 대신하는 유일한 구분 수단이므로, 그것이 빠지면 이 가드가 실패해야 한다.
  it('★각_단계_캡션이_단계명과_상태를_함께_적는다_색_단독_구분_금지', () => {
    const { container } = render(<BatchStageIndicator stages={stages} />);

    // 캡션은 두 줄(단계명 / 상태)이라 textContent 를 이으면 공백이 없다 — 줄 단위로 본다.
    const captionOf = (key: string): [string, string] => [
      (screen.getByTestId(`batch-stage-name-${key}`).textContent ?? '').trim(),
      (screen.getByTestId(`batch-stage-status-${key}`).textContent ?? '').trim(),
    ];

    // 완료 / 진행 중 / 대기 — 4상태 중 이 stages 에 등장하는 3종
    expect(captionOf('DEIDENTIFY')).toEqual(['비식별', '완료']);
    expect(captionOf('FRAME_EXTRACT')).toEqual(['프레임추출', '진행 중']);
    expect(captionOf('AUTOLABEL')).toEqual([COLLAPSED_BUNDLE_LABEL, '대기']);

    // 단계명만 있고 상태가 빠지는 회귀를 막는다(그 순간 상태 축이 색 단독이 된다).
    renderedCellKeys(container).forEach((key) => {
      expect(screen.getByTestId(`batch-stage-status-${key}`).textContent).toBeTruthy();
    });
  });

  it('★실패_단계도_캡션에_상태가_적힌다_완료와_회색조에서_구분된다', () => {
    const failed: BatchStageItem[] = [item('DEIDENTIFY', 'DONE'), item('MARKING', 'FAIL')];
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

  // ── 접기 순수 함수 직접 검증 ───────────────────────────────────────────────
  it('★collapseStages는_멤버_목록_진실원에서_파생된다', () => {
    const cells = collapseStages(stages);
    expect(cells.map((c) => c.key)).toEqual([
      'DEIDENTIFY',
      'MARKING',
      'VLM',
      'FRAME_EXTRACT',
      'AUTOLABEL',
    ]);
    const autolabel = cells[cells.length - 1]!;
    // 접은 칸이 품는 멤버는 STAGE_BUNDLE_MEMBERS.AUTOLABEL 3종 그대로
    expect(autolabel.members.map((m) => m.name)).toEqual(['YOLO', 'SAM2', 'INTERPOLATE']);
  });

  // ── 작업 묶음 표시명 — 한 이름, 한 정의처 ────────────────────────────────
  // ★ 같은 묶음이 한 화면에서 두 이름으로 불리던 것을 고친 자리다(회차 45): 스테퍼 캡션은
  //   '오토라벨링', 조작 UI(건너뛰기·재수행·모달·토스트)는 멤버 단계명을 이어 붙인
  //   'AI 탐지 · AI 분할 · 보간' 이었다. 사양(SCREEN-009)의 어휘가 「오토라벨」이므로 조작 UI 쪽을
  //   맞췄다 — 사양 되돌리기가 아니라 구현을 사양으로 되돌린 것이다.
  //
  // ⚠ 잃은 것: 멤버 나열 이름은 **이름만으로** 보간이 이 묶음 안에 있다는 사실을 알렸다. 그 고지는
  //   이제 재수행 경고 문단·확인 창 본문이 전담한다(BatchFailurePanel 의 보간 경고 가드가 짝이다).
  describe('★작업 묶음 표시명(SCREEN-009)', () => {
    it('★오토라벨_묶음_표시명은_오토라벨링이다_구_멤버나열_폐기', () => {
      expect(bundleLabel('AUTOLABEL')).toBe('오토라벨링');
      // 구 기대값 'AI 탐지 · AI 분할 · 보간' → 폐기.
      expect(bundleLabel('AUTOLABEL')).not.toContain('·');
      expect(bundleLabel('AUTOLABEL')).not.toContain('AI 탐지');
    });

    it('★스테퍼_캡션과_조작_UI_표시명이_같은_문자열이다', () => {
      // 두 축이 갈리면(이번에 고친 그 상태) 여기서 실패한다.
      expect(COLLAPSED_BUNDLE_LABEL).toBe(bundleLabel('AUTOLABEL'));

      render(<BatchStageIndicator stages={stages} />);
      expect(screen.getByTestId('batch-stage-name-AUTOLABEL')).toHaveTextContent(
        bundleLabel('AUTOLABEL'),
      );
    });

    // ★ 이름 충돌의 재발 방지 — 두 축이 각자 문자열을 적으면 한쪽만 갱신돼 다시 갈린다.
    //   런타임 동치(위 테스트)만으로는 못 잡는다: 두 곳에 같은 문자열을 적어도 통과하기 때문이다.
    it('★표시명_문자열은_소스에_한_번만_적힌다', () => {
      const srcDir = path.resolve(__dirname, '../../..');
      const files: string[] = [];
      const walk = (dir: string) => {
        for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
          const full = path.join(dir, entry.name);
          if (entry.isDirectory()) {
            if (entry.name === '__tests__' || entry.name === 'test') continue;
            walk(full);
          } else if (/\.tsx?$/.test(entry.name)) {
            files.push(full);
          }
        }
      };
      walk(srcDir);

      // 따옴표로 감싼 **정확한** 리터럴만 센다 — 산문·주석의 '오토라벨링' 언급은 대상이 아니다.
      const LITERAL = /(['"`])오토라벨링\1/g;
      const hits = files.flatMap((f) => {
        const count = (fs.readFileSync(f, 'utf-8').match(LITERAL) ?? []).length;
        return count > 0
          ? [`${path.relative(srcDir, f).split(path.sep).join('/')} x${count}`]
          : [];
      });

      expect(hits).toEqual(['components/common/BatchStageIndicator.tsx x1']);
    });

    // ★ 별건으로 유예돼 있던 드리프트가 해소된 자리다.
    //   회차 45 당시 주석: "사양은 이 묶음을 「시계열」이라 부르는데 구현 출력은 'VLM' 이다 —
    //   별건으로 보고됨." 그 별건이 이번에 처리됐다 — 기술 모델명 노출 금지 정책(UI-017/UI-018)에
    //   따라 단계 표의 `VLM` 라벨이 「시계열」이 되었고, 이 묶음 이름은 그 표에서 **파생**되므로
    //   함께 따라왔다(묶음 쪽에 이름을 따로 적어 고친 것이 아니다).
    it('★시계열_묶음_표시명은_시계열이다_구_VLM_노출_폐기', () => {
      expect(bundleLabel('VLM')).toBe('시계열');
      // 손으로 적은 두 번째 이름이 아니라 단계 표에서 파생된 값이다.
      expect(bundleLabel('VLM')).toBe(stageLabel('VLM'));
      // 기술 모델명이 묶음 이름으로 새지 않는다.
      expect(bundleLabel('VLM')).not.toContain('VLM');
    });
  });

  // ── SCREEN-009 회귀 가드: 전폭 밴드에서 칸을 균등 분산한다 ──────────────────
  // ★ 앞선 라운드가 '처리 단계'를 전폭 밴드로 빼냈는데도 **표시기 자신이 내용 폭**이라
  //   노드가 좌측에 뭉쳤다(브라우저 실측: 886px 밴드에 273px 만 사용). 밴드를 넓히는 것과
  //   칸을 분산하는 것은 **다른 축**이며, 앞의 것만 고치면 원래 고치려던 판독성 저하가 남는다.
  //
  // ⚠ jsdom 은 레이아웃을 계산하지 않아 실제 분산 폭은 여기서 판정할 수 없다(브라우저 실측이
  //   짝이다). 이 가드가 고정하는 것은 **분산을 만드는 구조**(칸의 flex-1 · 한 줄 캡션)다.
  //
  // ⚠ **[폐기] 구 `fill` prop 과 비-fill 렌더 경로** — 칸을 내용 폭으로 두던 그 경로는 **마킹 화면
  //   헤더에 인라인으로 놓이는 자리** 하나를 위한 것이었다. 그 자리가 사라져 prop·기본값·분기를
  //   함께 걷어냈고, 전폭 분산이 **유일한 렌더**가 됐다. 그래서 아래 가드는 "켰을 때"가 아니라
  //   **언제나** 그런지를 본다. 함께 폐기된 케이스 셋:
  //     ①`★기본값은_구_동작이라_다른_사용처를_늘리지_않는다` — 관측할 기본 모드가 없다.
  //     ②`★fill은_표시_계약을_바꾸지_않는다_칸수_문구_라이브안내_동일` — 비교할 두 번째 모드가 없다.
  //       그 계약(칸수·상태 문구·라이브 안내)은 이 파일의 접기·라이브 리전 케이스가 계속 고정한다.
  //     ③`★fill을_켜는_사용처는_영상_상세_한_곳뿐이다` — 아래 「prop 이 남아 있지 않다」로 대체.
  describe('★전폭 분산(@design SCREEN-009 · @design UI-018)', () => {
    const cellsOf = (container: HTMLElement) =>
      Array.from(container.querySelectorAll('[data-testid^="batch-stage-item-"]')).map(
        (el) => el.parentElement as HTMLElement,
      );

    it('★칸이_flex-1이라_컨테이너_전폭에_균등_분산된다', () => {
      const { container } = render(<BatchStageIndicator stages={stages} />);
      const cells = cellsOf(container);
      expect(cells).toHaveLength(5);
      // 칸마다 같은 폭을 갖는 것이 분산의 조건이다 — 하나라도 내용 폭이면 정렬이 어긋난다.
      cells.forEach((cell) => {
        expect(cell.className).toContain('flex-1');
      });
      expect(screen.getByTestId('batch-stage-indicator').className).toContain('w-full');
    });

    it('★캡션이_한_줄이다_단계명과_상태가_같은_부모_줄에_온다', () => {
      render(<BatchStageIndicator stages={stages} />);
      const name = screen.getByTestId('batch-stage-name-DEIDENTIFY');
      const status = screen.getByTestId('batch-stage-status-DEIDENTIFY');
      // 한 줄 = 두 조각이 같은 부모 안에 공백으로 이어진다(디자인 캡션 `비식별 완료`).
      expect(name.parentElement).toBe(status.parentElement);
      expect(name.parentElement?.textContent).toBe('비식별 완료');
    });

    it('★접은_칸의_보조_표기는_전폭_분산에서도_남는다', () => {
      render(
        <BatchStageIndicator
          stages={[item('DEIDENTIFY', 'DONE'), item('YOLO', 'DONE'), item('INTERPOLATE', 'FAIL')]}
        />,
      );
      expect(screen.getByTestId('batch-stage-note-AUTOLABEL')).toHaveTextContent('보간에서 실패');
    });

    // ★ 되돌림 방지 — 구 분기가 코드로 되살아나면 즉시 실패한다. 런타임 렌더 테스트는 "지금
    //   무엇이 그려지는가"만 보므로, prop 이 다시 생겨 어느 화면이 그것을 켜는 상황을 못 잡는다.
    it('★fill_prop과_비-fill_렌더_경로가_소스에_남아_있지_않다', () => {
      const defPath = path.resolve(__dirname, '../BatchStageIndicator.tsx');
      // 주석의 폐기 서술(`fill` 이라는 낱말)은 대상이 아니다 — 블록·JSX·줄 주석을 걷어낸
      // **코드 본문**만 센다. 주석을 안 걷으면 이 파일의 폐기 표기 자체가 가드를 붉게 만든다.
      const code = fs
        .readFileSync(defPath, 'utf-8')
        .replace(/\/\*[\s\S]*?\*\//g, '')
        .replace(/\/\/[^\n]*/g, '');

      expect(code.match(/\bfill\b/g) ?? []).toEqual([]);
      // 비-fill 전용 캡션 축소도 함께 사라졌다(전폭 경로로 새면 캡션이 10px 로 줄어든다).
      expect(code.match(/fontSize/g) ?? []).toEqual([]);

      // 렌더 호출부 어디에도 이 prop 이 남지 않는다.
      const hits = sourceFiles().flatMap((f) => {
        const count = (fs.readFileSync(f.abs, 'utf-8').match(/<BatchStageIndicator[^>]*\bfill\b/gs) ?? [])
          .length;
        return count > 0 ? [`${f.rel} x${count}`] : [];
      });
      expect(hits).toEqual([]);
    });

    // ★ 근거가 소멸한 주장을 사실로 남겨 두지 않는다(CO-011). 마킹 화면은 이 표시기를 더 쓰지
    //   않으므로 "표시기를 마킹 화면과 공유한다"는 서술은 거짓이다. 폐기 표기와 함께 남긴
    //   **이력**은 허용하고, 표기 없는 **주장**만 막는다.
    it('★표시기를_마킹_화면과_공유한다는_주장이_남아_있지_않다', () => {
      const CLAIM = /마킹\s*화면[^\n]{0,20}공유/;
      const offenders = sourceFiles().flatMap((f) =>
        fs
          .readFileSync(f.abs, 'utf-8')
          .split('\n')
          .filter((line) => CLAIM.test(line) && !line.includes('폐기'))
          .map((line) => `${f.rel}: ${line.trim()}`),
      );
      expect(offenders).toEqual([]);
    });
  });
});
