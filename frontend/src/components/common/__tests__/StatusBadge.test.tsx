import { describe, expect, it } from 'vitest';
import { render, screen } from '@testing-library/react';

import { StatusBadge, type BadgeStatus } from '../StatusBadge';

describe('StatusBadge', () => {
  const statuses: BadgeStatus[] = [
    'BATCH_PROCESSING',
    'BATCH_COMPLETED',
    'BATCH_FAILED',
    'PENDING',
    'MARKING_READY',
    'IN_PROGRESS',
    'REVIEW_PENDING',
    'REVIEWING',
    'COMPLETED',
    'REJECTED',
  ];

  it('StatusBadge_9종_상태별_색상_렌더', () => {
    statuses.forEach((s) => {
      const { unmount } = render(<StatusBadge status={s} />);
      const el = screen.getByText(/.*/, { selector: `[data-status="${s}"]` });
      expect(el).toBeInTheDocument();
      // 색상 토큰 클래스 검증 — KRDS 시맨틱 토큰 또는 gray/purple 카테고리 매핑
      expect(el.className).toMatch(
        /bg-(success|danger|warning|info)\/10|text-(success|danger|warning|info)|bg-(gray|purple)-(50|100|200)|text-(gray|purple)-(600|700|800)/,
      );
      unmount();
    });
  });

  it('StatusBadge_BATCH_FAILED_은_danger_색상', () => {
    render(<StatusBadge status="BATCH_FAILED" />);
    const el = screen.getByText('배치 실패');
    // danger 톤은 KRDS danger 토큰으로 표현 (tonal pill)
    expect(el.className).toMatch(/text-danger|bg-danger/);
  });

  it('StatusBadge_MARKING_READY_는_마킹_대기_라벨_렌더', () => {
    render(<StatusBadge status="MARKING_READY" />);
    expect(screen.getByText('마킹 대기')).toBeInTheDocument();
  });

  it('StatusBadge_PROCESSING_는_처리중_라벨_렌더', () => {
    // Bug 2 — 마킹 완료~배치 완료 구간 '처리중' 표시
    render(<StatusBadge status="PROCESSING" />);
    const el = screen.getByText('처리중');
    expect(el).toBeInTheDocument();
    expect(el.className).toMatch(/bg-info|text-info/);
  });

  it('StatusBadge_FAILED_는_실패_라벨_렌더', () => {
    // Bug 2 — 배치 실패 시 '실패' 표시 (MARKING_READY 고착 금지)
    render(<StatusBadge status="FAILED" />);
    const el = screen.getByText('실패');
    expect(el).toBeInTheDocument();
    expect(el.className).toMatch(/bg-danger|text-danger/);
  });

  // ── UI-014 회귀 가드 ─────────────────────────────────────────────────────
  // 구 구현은 FALLBACK.label='' 이고 렌더가 `label ?? cfg.label ?? status` 였다.
  // `??` 는 빈 문자열을 통과시키므로 매핑 밖 상태에서 **아이콘만 있고 텍스트가 빈 배지**가 됐다.
  it('StatusBadge_매핑에_없는_상태는_원문을_라벨로_보여준다', () => {
    render(<StatusBadge status="SOME_NEW_BE_ALIAS" />);
    expect(screen.getByText('SOME_NEW_BE_ALIAS')).toBeInTheDocument();
  });

  it('StatusBadge_매핑에_없는_상태는_회색_폴백_톤이다', () => {
    const { container } = render(<StatusBadge status="SOME_NEW_BE_ALIAS" />);
    const el = container.querySelector('[data-status="SOME_NEW_BE_ALIAS"]') as HTMLElement;
    expect(el.className).toMatch(/bg-gray-100/);
    expect(el.className).toMatch(/text-gray-600/);
    // 라벨 텍스트가 함께 렌더돼 색 단독 구분이 되지 않는다.
    // ★구 단언은 여기서 아이콘(svg) 존재를 봤으나, 상태 아이콘 폐지(2026-08-10)로
    //  색 단독 구분 금지를 충족하는 축이 텍스트 하나가 됐다 — 그 축으로 옮겼다.
    expect((el.textContent ?? '').trim().length).toBeGreaterThan(0);
  });

  it('StatusBadge_매핑에_없는_상태에서도_커스텀_label_이_우선한다', () => {
    render(<StatusBadge status="SOME_NEW_BE_ALIAS" label="새 상태" />);
    expect(screen.getByText('새 상태')).toBeInTheDocument();
    expect(screen.queryByText('SOME_NEW_BE_ALIAS')).not.toBeInTheDocument();
  });

  it('StatusBadge_커스텀_label_사용', () => {
    render(<StatusBadge status="COMPLETED" label="완료됨" />);
    expect(screen.getByText('완료됨')).toBeInTheDocument();
  });

  it('★StatusBadge_상태별_색과_텍스트_병기_아이콘은_폐지됐다', () => {
    // KRDS: 색만으로 상태 구분 금지 — 색 + **텍스트** 병기로 충족한다.
    // ★2026-08-10 상태 아이콘 폐지. 구 단언(색+아이콘+텍스트 3중 병기)에서 아이콘 축을 뺀 대신,
    //  장식 아이콘이 되살아나지 않는지를 함께 고정한다(같은 배지에 표현이 두 벌 생기는 재발 방지).
    const cases: BadgeStatus[] = [
      'COMPLETED',
      'FAILED',
      'IN_PROGRESS',
      'REVIEWING',
      'PENDING',
    ];
    cases.forEach((s) => {
      const { container, unmount } = render(<StatusBadge status={s} />);
      const badge = container.querySelector(`[data-status="${s}"]`) as HTMLElement;
      // 텍스트 라벨 존재 — 정보 전달의 단독 축
      expect((badge.textContent ?? '').trim().length).toBeGreaterThan(0);
      // 장식 아이콘 부재
      expect(badge.querySelector('svg')).toBeNull();
      // 색상 토큰 유지 (KRDS 시맨틱 토큰 tonal pill 또는 gray/purple 카테고리)
      expect(badge.className).toMatch(
        /bg-(success|danger|warning|info)\/10|bg-(gray|purple)-(50|100|200)/,
      );
      unmount();
    });
  });
  // ── 시안(SCREEN-009 `.badge`) 정합 — 타이포·좌우여백 축 ────────────────
  // 시안은 `font-size: 14px; font-weight: 600; line-height: 1.4` 이고, ladder 의 `label`
  // step 이 정확히 그 셋이다. 구 구현은 `sub`(14px/1.6/400) + 별도 굵기 유틸이라
  // **행간과 굵기가 둘 다** 어긋나 있었다.
  it('StatusBadge_타이포는_ladder_label_step_한_개로_적힌다', () => {
    render(<StatusBadge status="COMPLETED" />);
    const cls = screen.getByText('완료').className.split(/\s+/);

    expect(cls).toContain('text-label');
    expect(cls).not.toContain('text-sub');
    // 굵기 유틸이 남아 있으면 500 이 step 의 600 을 덮는다 — 크기만 맞고 굵기는 어긋난다.
    expect(cls.filter((c) => /^font-/.test(c))).toEqual([]);
  });

  it('StatusBadge_좌우여백은_시안_10px_이다', () => {
    render(<StatusBadge status="COMPLETED" />);
    const cls = screen.getByText('완료').className.split(/\s+/);
    expect(cls).toContain('px-2.5');
    expect(cls).not.toContain('px-2');
  });
});
