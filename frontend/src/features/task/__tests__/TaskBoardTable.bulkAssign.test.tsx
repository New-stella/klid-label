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
 * ★ 일괄 배정은 미배정 행 전용이다(사양 SCREEN-012) — 이미 작업자가 배정된 행은
 * 선택 체크박스가 비활성화되고, "현재 페이지 전체 선택"도 미배정 행만 대상으로 삼는다.
 *
 * 구 버그: 배정 여부와 무관하게 모든 행 체크박스가 항상 활성이었다.
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
    augmented: false,
    augType: null,
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
  return renderWithProviders(<TaskBoardTable {...props} />);
}

describe('작업목록 일괄 배정 — 미배정 행 전용 선택', () => {
  it('이미_배정된_행의_선택_체크박스는_비활성화된다', () => {
    renderTable({
      rows: [row({ id: '10', video: video({ id: 10 }), task: task({ videoId: 10 }) })],
    });

    const checkbox = screen.getByRole('checkbox', { name: /CCTV-강남대로-001 선택/ });
    expect(checkbox).toBeDisabled();
  });

  it('미배정_행의_선택_체크박스는_활성_상태다', () => {
    renderTable({
      rows: [row({ id: '10', video: video({ id: 10 }), task: undefined })],
    });

    const checkbox = screen.getByRole('checkbox', { name: /CCTV-강남대로-001 선택/ });
    expect(checkbox).toBeEnabled();
  });

  it('전체_선택_체크박스는_배정된_행을_제외한_미배정_행만_대상으로_삼는다', () => {
    const assignedRow = row({
      id: '10',
      video: video({ id: 10 }),
      task: task({ videoId: 10 }),
      videoName: '배정됨',
    });
    const unassignedRow = row({
      id: '20',
      video: video({ id: 20 }),
      task: undefined,
      videoName: '미배정됨',
    });

    // when: 미배정 행 하나만 선택된 상태 — 배정된 행은 대상이 아니므로 "전체 선택" 이 이미
    // 체크(indeterminate 아님)로 표시돼야 한다.
    renderTable({
      rows: [assignedRow, unassignedRow],
      selectedVideoIds: new Set([20]),
    });

    const selectAll = screen.getByRole('checkbox', { name: '현재 페이지 전체 선택' });
    expect(selectAll).not.toBePartiallyChecked();
    expect(selectAll).toBeChecked();
  });
});
