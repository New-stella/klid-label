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
    // 두 시각의 컬럼 수가 다른데(8 vs 6) 같은 최소폭을 쓰면 WORKER 는 1280px 에서
    // 스크롤이 필요 없는데도 가로 스크롤이 생긴다.
    // ⚠ 구 주석은 「8 vs 7」이었다 — 검수자 열이 빠졌는데(ADR-067) 검수자 쪽만 고쳐진 값이라,
    //   다음 사람이 이 주석을 근거로 WORKER 7 을 정상으로 읽게 되는 자리였다.
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

  it('★최소폭은_컬럼_구성과_함께_고정된다_한쪽만_되돌리면_깨진다', () => {
    // ★위 케이스들은 전부 **관계**만 본다(범위·대소). 그래서 검수자 열이 빠진 만큼 줄인 폭을
    //   구 값으로 되돌려도 전부 통과한다(1120 ≤ 1150 · 900 < 1120) — 컬럼은 줄었는데 폭만
    //   남아 열 간 여백이 벌어지는 상태가 **조용히** 돌아온다.
    //
    // 폭과 컬럼 수를 **한 케이스에서 함께** 못박는다. 컬럼을 되돌리면 개수 단언이, 폭만
    // 되돌리면 값 단언이 깨진다 — 어느 쪽을 건드려도 재산정이 강제된다.
    const reviewer = renderTable({ isReviewer: true });
    expect(Number(/\bmin-w-\[(\d+)px\]/.exec(reviewer.table.className)![1])).toBe(1020);
    // 검수자 열을 되살리면 9가 되어 깨진다(ADR-067 이 그 열을 걷어냈다).
    expect(screen.getAllByRole('columnheader')).toHaveLength(8);
    reviewer.unmount();

    const worker = renderTable({ isReviewer: false, rows: [row({ task: task() })] });
    expect(Number(/\bmin-w-\[(\d+)px\]/.exec(worker.table.className)![1])).toBe(900);
    // WORKER 시각도 검수자 열을 잃어 6이다(체크박스·촬영일시는 원래 없다).
    expect(screen.getAllByRole('columnheader')).toHaveLength(6);
    worker.unmount();
  });

  /**
   * ★파생 컬럼 수 가드 — 헤더 개수와 **그 개수를 쓰는 자리**가 갈라지지 않게 한다.
   *
   * 실측 결함: 검수자 열이 빠졌는데(`ADR-067`) 개수 상수는 검수자 쪽만 고쳐져 작업자 시각이
   * 7 로 남았다. 실제 헤더는 6이라 **로딩 행에 칸이 하나 더** 생기고 **빈 상태가 한 열을 더**
   * 덮었는데, 타입 오류도 시험 실패도 나지 않았다 — 어떤 케이스도 이 축을 보지 않았기 때문이다.
   *
   * ⚠ **두 시각 모두** 돈다. 한쪽만 보면 정확히 이번 결함(반대쪽만 안 따라옴)을 놓친다.
   */
  describe('★로딩·빈_상태의_칸_수는_헤더_수에서_파생된다', () => {
    for (const isReviewer of [true, false]) {
      const view = isReviewer ? 'REVIEWER' : 'WORKER';

      it(`${view}_로딩_스켈레톤_행의_td_개수가_헤더_개수와_같다`, () => {
        const { container, unmount } = renderTable({ isReviewer, isLoading: true });
        const headerCount = screen.getAllByRole('columnheader').length;
        const bodyRows = container.querySelectorAll('tbody tr');
        expect(bodyRows.length, '로딩 스켈레톤 행이 없다').toBeGreaterThan(0);
        for (const tr of bodyRows) {
          expect(tr.querySelectorAll('td'), `${view} 로딩 행`).toHaveLength(headerCount);
        }
        unmount();
      });

      it(`${view}_빈_목록_안내의_colSpan_이_헤더_개수와_같다`, () => {
        // 넘치면 표 밖까지 덮고, 모자라면 마지막 열이 안내 옆에 빈칸으로 남는다.
        const { container, unmount } = renderTable({ isReviewer, rows: [] });
        const headerCount = screen.getAllByRole('columnheader').length;
        const cell = container.querySelector('tbody td[colspan]');
        expect(cell, `${view} 빈 상태 셀을 찾지 못했다`).not.toBeNull();
        expect(Number(cell!.getAttribute('colspan'))).toBe(headerCount);
        unmount();
      });
    }
  });

  it('컬럼_헤더는_줄바꿈되지_않는다', () => {
    renderTable({ isReviewer: true });

    // 실측 결함: 헤더 "영상 ID" 가 2줄로 쪼개졌다.
    // ★검수자 헤더는 목록에 **없다**(ADR-067) — 아래 별도 케이스가 그 부재를 고정한다.
    for (const label of ['영상명', '영상 ID', '촬영일시', '이벤트', '상태', '작업자', '액션']) {
      const th = screen.getByRole('columnheader', { name: new RegExp(label) });
      expect(th.className, `헤더 "${label}"`).toContain('whitespace-nowrap');
    }
  });

  it('검수자_열이_없고_작업자_열은_그대로_있다', () => {
    // 검수는 배정 없이 전체 대기열에서 집어가므로 「이 영상의 검수자」라는 값이 없다(ADR-067).
    //
    // ★부재 단언만 두면 표가 통째로 비어도 통과한다 — 같은 자리에 남아야 하는 작업자 열의
    //   존재를 짝으로 단언해, 열을 다 지우는 변이가 이 케이스에서 죽게 한다.
    renderTable({ isReviewer: true, rows: [row({ task: task(), rowStatus: 'IN_PROGRESS' })] });

    expect(screen.queryByRole('columnheader', { name: /검수자/ })).toBeNull();
    expect(screen.getByRole('columnheader', { name: /작업자/ })).toBeInTheDocument();
    expect(screen.getByText('김작업')).toBeInTheDocument();
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

  it('작업자_셀은_줄바꿈되지_않는다', () => {
    // 검수자 셀이 사라져(ADR-067) 미배정 행의 폴백 문구는 작업자 쪽 "미배정" 하나뿐이다.
    // 그 문구는 상태 뱃지와 글자가 같아 텍스트로 특정할 수 없으므로 배정된 행으로 확인한다.
    const assigned = renderTable({
      isReviewer: true,
      rows: [row({ task: task(), rowStatus: 'IN_PROGRESS' })],
    });
    expect(cellOf(screen.getByText('김작업')).className).toContain('whitespace-nowrap');
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
