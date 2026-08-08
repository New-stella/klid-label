import { describe, expect, it, vi } from 'vitest';
import { screen } from '@testing-library/react';

import {
  TaskBoardTable,
  type TaskBoardTableProps,
  type TaskRow,
} from '@/features/task/components/TaskBoardTable';
import type { Task } from '@/features/task/types';
import type { Video } from '@/features/video/types';
import { renderWithProviders } from '@/test/renderWithProviders';

/**
 * 작업목록 표 **레이아웃 클래스 계약** 가드.
 *
 * ★ 이 파일은 "폭이 좁을 때 셀이 뭉개지지 않는다" 를 **직접** 단언하지 않는다 —
 * jsdom 은 레이아웃을 계산하지 않아 `getBoundingClientRect()` 가 전부 0 이라,
 * 폭 기반 런타임 단언은 클래스를 전부 지워도 통과하는 **거짓 통과**가 된다.
 * 대신 뭉개짐을 막는 클래스가 마크업에 실재하는지를 고정한다(누가 지우면 여기서 잡힌다).
 *
 * 배경(1280×800 실측 결함): 래퍼에 `overflow-x-auto` 는 있는데 표에 `min-width` 가 없어
 * `table-layout: auto` 가 표를 부모 폭(990px)에 억지로 맞췄고, 한글은 단어 경계가 없어
 * 셀이 글자 단위로 끊겼다("미배정" → `미/배/정`, 행 높이 111px).
 */

function video(overrides: Partial<Video> = {}): Video {
  return {
    id: 10,
    cctvName: 'CCTV-강남대로-001',
    vmsClipId: 'clip-1',
    eventName: '이상행동(유괴)',
    eventTypeCd: 'EV01000102',
    frameCount: 3,
    status: 'COMPLETED',
    capturedAt: '2026-05-07T10:00:00Z',
    ...overrides,
  };
}

function task(overrides: Partial<Task> = {}): Task {
  return {
    id: 501,
    videoId: 10,
    cctvName: 'CCTV-강남대로-001',
    workerId: 7,
    workerName: '김작업',
    status: 'IN_PROGRESS',
    assignedAt: '2026-05-07T11:00:00Z',
    ...overrides,
  };
}

function row(overrides: Partial<TaskRow> = {}): TaskRow {
  return {
    id: '10',
    video: video(),
    task: undefined,
    rowStatus: 'UNASSIGNED',
    videoName: 'CCTV-강남대로-001',
    augmented: true,
    augType: 'WINTER',
    ...overrides,
  };
}

function renderTable(overrides: Partial<TaskBoardTableProps> = {}) {
  const props: TaskBoardTableProps = {
    rows: [row()],
    isReviewer: true,
    isLoading: false,
    refreshing: false,
    totalElements: 1,
    totalPages: 1,
    currentPage: 0,
    sort: [],
    onSort: vi.fn(),
    selectedVideoIds: new Set<number>(),
    onToggleRow: vi.fn(),
    onToggleAllPaged: vi.fn(),
    actionsDisabled: false,
    reviewerMap: {},
    onAssign: vi.fn(),
    onHistory: vi.fn(),
    onOpenLabel: vi.fn(),
    onOpenMarking: vi.fn(),
    ...overrides,
  };
  const utils = renderWithProviders(<TaskBoardTable {...props} />);
  const table = utils.container.querySelector('table');
  if (!table) throw new Error('table 이 렌더되지 않았다');
  return { ...utils, table };
}

/** 주어진 요소가 속한 `td` 를 찾는다(셀 단위 클래스 계약 확인용). */
function cellOf(el: HTMLElement): HTMLElement {
  const td = el.closest('td');
  if (!td) throw new Error('td 를 찾지 못했다');
  return td as HTMLElement;
}

describe('작업목록 표 레이아웃 클래스 계약', () => {
  it('표_루트에_min_width_가_있어야_좁은_폭에서_가로스크롤이_발동한다', () => {
    const { table } = renderTable({ isReviewer: true });

    // min-width 가 없으면 표가 부모 폭으로 압축되어 셀이 글자 단위로 뭉개진다.
    expect(table.className).toMatch(/\bmin-w-\[\d+px\]/);
  });

  it('표_최소폭은_FHD_표_래퍼폭_1630px_미만이어야_한다', () => {
    // 1920×1080 이 이 프로젝트의 기본 타깃이고 그 폭에서 표 래퍼 clientWidth 가 1630px 다.
    // min-width 가 이를 넘으면 지금 정상인 FHD 에 가로 스크롤이 새로 생긴다.
    for (const isReviewer of [true, false]) {
      const { table, unmount } = renderTable({ isReviewer });
      const matched = /\bmin-w-\[(\d+)px\]/.exec(table.className);
      expect(matched, `isReviewer=${isReviewer} 에 min-w 가 없다`).not.toBeNull();
      expect(Number(matched![1])).toBeLessThan(1630);
      unmount();
    }
  });

  it('표_최소폭은_1440_뷰포트의_표_래퍼폭_1150px_이하여야_한다', () => {
    // 실측 결함(1440×900): 최소폭 1200 이 래퍼(1150)를 50px 넘겨 마지막 액션 '이력' 버튼이
    // 뷰포트 밖(right=1446)에 있었다. 가로 스크롤로 도달은 됐지만 단서가 없어 사실상 보이지 않았다.
    // 1440 은 이 프로젝트에서 실제로 쓰이는 폭이라 그 폭에서 스크롤이 없어야 한다.
    const { table } = renderTable({ isReviewer: true });
    const width = Number(/\bmin-w-\[(\d+)px\]/.exec(table.className)![1]);
    expect(width).toBeLessThanOrEqual(1150);
  });

  it('헤더와_본문_셀의_좌우여백이_같아야_최소폭_산정이_성립한다', () => {
    // 최소폭은 "콘텐츠 + 좌우 여백" 합으로 산정한다. 헤더/본문 중 한쪽만 여백을 키우면
    // 계산이 어긋나 실제 표가 선언한 최소폭을 넘고, 1440 무스크롤 보장이 조용히 깨진다.
    const { container } = renderTable({ isReviewer: true, rows: [row({ task: task() })] });
    for (const cell of container.querySelectorAll('th, td')) {
      // colSpan 빈 상태 셀(px-3 py-12)도 px-3 이라 같은 규칙으로 통과한다.
      expect(cell.className, `셀 클래스: ${cell.className}`).toMatch(/\bpx-3\b/);
    }
  });

  it('WORKER_표는_컬럼이_적으므로_REVIEWER_보다_최소폭이_작다', () => {
    // 두 시각의 컬럼 수가 다른데(9 vs 7) 같은 최소폭을 쓰면 WORKER 는 1280px 에서
    // 스크롤이 필요 없는데도 가로 스크롤이 생긴다.
    const reviewer = renderTable({ isReviewer: true });
    const reviewerWidth = Number(/\bmin-w-\[(\d+)px\]/.exec(reviewer.table.className)![1]);
    reviewer.unmount();

    const worker = renderTable({ isReviewer: false, rows: [row({ task: task() })] });
    const workerWidth = Number(/\bmin-w-\[(\d+)px\]/.exec(worker.table.className)![1]);
    worker.unmount();

    expect(workerWidth).toBeLessThan(reviewerWidth);
    // WORKER 는 1280px 뷰포트의 표 래퍼(990px) 안에 들어가야 스크롤이 안 생긴다.
    expect(workerWidth).toBeLessThanOrEqual(990);
  });

  it('컬럼_헤더는_줄바꿈되지_않는다', () => {
    renderTable({ isReviewer: true });

    // 실측 결함: 헤더 "영상 ID" 가 2줄로 쪼개졌다.
    for (const label of ['영상명', '영상 ID', '촬영일시', '이벤트', '상태', '작업자', '검수자', '액션']) {
      const th = screen.getByRole('columnheader', { name: new RegExp(label) });
      expect(th.className, `헤더 "${label}"`).toContain('whitespace-nowrap');
    }
  });

  it('상태_뱃지_셀은_줄바꿈되지_않는다', () => {
    const { container } = renderTable({ isReviewer: true, rows: [row({ rowStatus: 'UNASSIGNED' })] });

    // 실측 결함: 상태 뱃지 "미배정" 이 `미 / 배 / 정` 3줄로 쪼개졌다.
    // (작업자 폴백 문구도 "미배정" 이라 텍스트가 아니라 StatusBadge 의 data-status 로 특정한다.)
    const badge = container.querySelector('[data-status]');
    expect(badge, '상태 뱃지를 찾지 못했다').not.toBeNull();
    expect(badge!.textContent).toContain('미배정');
    expect(cellOf(badge as HTMLElement).className).toContain('whitespace-nowrap');
  });

  it('작업자_검수자_셀은_줄바꿈되지_않는다', () => {
    renderTable({ isReviewer: true, rows: [row()] });

    // 미배정 행의 두 폴백 문구("미배정"/"미등록")가 각각 작업자·검수자 셀이다.
    expect(cellOf(screen.getByText('미등록')).className).toContain('whitespace-nowrap');

    const assigned = renderTable({
      isReviewer: true,
      rows: [row({ task: task({ reviewerName: '박검수' }), rowStatus: 'IN_PROGRESS' })],
    });
    expect(cellOf(screen.getByText('김작업')).className).toContain('whitespace-nowrap');
    expect(cellOf(screen.getByText('박검수')).className).toContain('whitespace-nowrap');
    assigned.unmount();
  });

  it('액션_버튼_셀은_줄바꿈되지_않는다', () => {
    renderTable({ isReviewer: true, rows: [row()] });

    // 실측 결함: "배정" 이 `배 / 정` 2줄로 쪼개졌다.
    const cell = cellOf(screen.getByRole('button', { name: '배정' }));
    expect(cell.className).toContain('whitespace-nowrap');
  });

  it('영상_ID_셀은_줄바꿈되지_않는다', () => {
    renderTable({ isReviewer: true, rows: [row()] });

    // `video-0010` 은 단일 식별자다 — 하이픈에서 끊기면 두 개처럼 읽힌다.
    const cell = cellOf(screen.getByText('video-0010'));
    expect(cell.className).toContain('whitespace-nowrap');
  });

  it('이벤트_증강_뱃지_셀은_줄바꿈되지_않는다', () => {
    renderTable({ isReviewer: true, rows: [row({ augmented: true, augType: 'WINTER' })] });

    const cell = cellOf(screen.getByTestId('task-aug-badge-10'));
    expect(cell.className).toContain('whitespace-nowrap');
  });

  /**
   * 스크롤 단서 가드.
   *
   * jsdom 은 레이아웃을 계산하지 않아 `scrollWidth`/`clientWidth` 가 항상 0 이다 —
   * 그대로 두면 "넘치지 않음" 한 갈래만 실행돼 단서 코드를 통째로 지워도 통과하는 거짓 통과가 된다.
   * 그래서 두 값을 명시적으로 심어 **양쪽 갈래를 모두** 실행시킨다.
   */
  function withMeasuredWidths(scrollWidth: number, clientWidth: number, fn: () => void) {
    const original = {
      scrollWidth: Object.getOwnPropertyDescriptor(HTMLElement.prototype, 'scrollWidth'),
      clientWidth: Object.getOwnPropertyDescriptor(HTMLElement.prototype, 'clientWidth'),
    };
    Object.defineProperty(HTMLElement.prototype, 'scrollWidth', {
      configurable: true,
      get: () => scrollWidth,
    });
    Object.defineProperty(HTMLElement.prototype, 'clientWidth', {
      configurable: true,
      get: () => clientWidth,
    });
    try {
      fn();
    } finally {
      if (original.scrollWidth) {
        Object.defineProperty(HTMLElement.prototype, 'scrollWidth', original.scrollWidth);
      }
      if (original.clientWidth) {
        Object.defineProperty(HTMLElement.prototype, 'clientWidth', original.clientWidth);
      }
    }
  }

  it('표가_넘칠_때는_스크롤_단서와_키보드_접근이_생긴다', () => {
    // 실측 결함: 넘쳐도 아무 단서가 없어 화면 밖 '이력' 버튼의 존재를 알 수 없었다.
    withMeasuredWidths(1120, 900, () => {
      const { container } = renderTable({ isReviewer: true, rows: [row({ task: task() })] });

      // 색·그림자에만 의존하지 않는 텍스트 단서 (색만으로 정보 전달 금지)
      expect(screen.getByTestId('task-board-scroll-hint')).toBeInTheDocument();
      // 마우스 없이도 가려진 컬럼에 도달할 수 있어야 한다
      const region = container.querySelector('[role="region"][aria-label="작업 목록 표"]');
      expect(region, '스크롤 영역에 role/aria-label 이 없다').not.toBeNull();
      expect(region!.getAttribute('tabindex')).toBe('0');
    });
  });

  it('표가_넘치지_않으면_단서도_탭_정지점도_만들지_않는다', () => {
    // 항상 띄우면 스크롤이 없는 폭에서도 "더 있다"는 거짓 안내가 되고, 무의미한 탭 정지점이 는다.
    withMeasuredWidths(900, 900, () => {
      const { container } = renderTable({ isReviewer: true, rows: [row({ task: task() })] });

      expect(screen.queryByTestId('task-board-scroll-hint')).toBeNull();
      const region = container.querySelector('[role="region"][aria-label="작업 목록 표"]');
      expect(region!.getAttribute('tabindex')).toBeNull();
    });
  });

  it('영상명_셀은_길어질_수_있으므로_nowrap_이_아니라_truncate_로_처리한다', () => {
    renderTable({ isReviewer: true, rows: [row({ videoName: 'CCTV-강남대로-001' })] });

    const name = screen.getByText('CCTV-강남대로-001');
    // nowrap 을 주면 긴 영상명이 표를 무한정 밀어낸다 — 여기서는 truncate 가 맞다.
    expect(name.className).toContain('truncate');
    expect(cellOf(name).className).not.toContain('whitespace-nowrap');
  });
});
