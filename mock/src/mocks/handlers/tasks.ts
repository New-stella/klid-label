import { http } from 'msw';
import { tasks, updateTask, createTask } from '../data/tasks';
import { videos } from '../data/videos';
import { ok, fail, paginate, parsePageParams } from './_utils';
import type { BulkAssignRequest, BulkAssignResponse, TaskDto } from '../../api/types';

export const tasksHandlers = [
  http.get('/api/v1/tasks', ({ request }) => {
    const url = new URL(request.url);
    const { page, size } = parsePageParams(url);
    const assigneeId = url.searchParams.get('assigneeId');
    const reviewerId = url.searchParams.get('reviewerId');
    const status = url.searchParams.get('status');

    let filtered = [...tasks];
    if (assigneeId) filtered = filtered.filter((t) => t.assigneeId === assigneeId);
    if (reviewerId) filtered = filtered.filter((t) => t.reviewerId === reviewerId);
    if (status) filtered = filtered.filter((t) => t.status === status);
    return ok(paginate(filtered, page, size));
  }),

  http.get('/api/v1/tasks/:id', ({ params }) => {
    const task = tasks.find((t) => t.id === params['id']);
    if (!task) return fail('TASK_NOT_FOUND', '태스크를 찾을 수 없습니다.', 404);
    return ok(task);
  }),

  http.post('/api/v1/tasks/:id/assign', async ({ params, request }) => {
    const taskId = params['id'] as string;
    const body = (await request.json()) as {
      assigneeId: string;
      assigneeName?: string;
      reviewerId?: string;
    };

    const updated = updateTask(taskId, {
      assigneeId: body.assigneeId,
      assigneeName: body.assigneeName,
      reviewerId: body.reviewerId,
      status: 'IN_PROGRESS',
    });
    if (!updated) return fail('TASK_NOT_FOUND', '태스크를 찾을 수 없습니다.', 404);
    return ok(updated);
  }),

  /**
   * 단일 영상 배정 — 영상에 대응되는 task가 없으면 신규 생성, 있으면 갱신한다.
   * 화면에서 "미배정" 행을 단일 배정할 때 사용된다.
   */
  http.post('/api/v1/videos/:videoId/assign-task', async ({ params, request }) => {
    const videoId = params['videoId'] as string;
    const body = (await request.json()) as {
      assigneeId: string;
      assigneeName?: string;
      reviewerId?: string;
    };
    if (!body.assigneeId) {
      return fail('INVALID_REQUEST', '작업자 ID가 필요합니다.', 400);
    }
    const video = videos.find((v) => v.id === videoId);
    if (!video) return fail('VIDEO_NOT_FOUND', '영상을 찾을 수 없습니다.', 404);

    const existing = tasks.find((t) => t.videoId === videoId);
    if (existing) {
      const updated = updateTask(existing.id, {
        assigneeId: body.assigneeId,
        assigneeName: body.assigneeName,
        reviewerId: body.reviewerId,
        status: existing.status === 'PENDING' ? 'IN_PROGRESS' : existing.status,
      });
      if (!updated) return fail('TASK_NOT_FOUND', '태스크를 찾을 수 없습니다.', 404);
      return ok(updated);
    }
    const created = createTask({
      videoId,
      videoName: video.cctvName,
      assigneeId: body.assigneeId,
      assigneeName: body.assigneeName,
      reviewerId: body.reviewerId,
    });
    return ok(created);
  }),

  /**
   * 일괄 배정 — videoIds 배열의 각 영상에 대응하는 task에 동일 assignee/reviewer를 적용한다.
   * - task가 이미 있으면: 동일 배정인 경우 skipped, 다르면 갱신 후 assigned 카운트
   * - task가 없으면: 영상이 존재하면 신규 task 생성 후 assigned 카운트
   * - 영상 자체가 없으면 skipped (안전 가드)
   * 멱등 처리: 동일 요청을 여러 번 호출해도 최종 상태는 동일하다.
   */
  http.post('/api/v1/tasks/bulk-assign', async ({ request }) => {
    const body = (await request.json()) as BulkAssignRequest;
    if (!body.assigneeId || !Array.isArray(body.videoIds) || body.videoIds.length === 0) {
      return fail('INVALID_REQUEST', '요청 형식이 잘못되었습니다.', 400);
    }

    let assigned = 0;
    let skipped = 0;
    const updatedTasks: TaskDto[] = [];

    for (const videoId of body.videoIds) {
      const existing = tasks.find((t) => t.videoId === videoId);
      if (existing) {
        const sameAssignee = existing.assigneeId === body.assigneeId;
        const sameReviewer = (existing.reviewerId ?? '') === (body.reviewerId ?? '');
        // 이미 동일 배정이면 멱등하게 스킵
        if (sameAssignee && sameReviewer) {
          skipped += 1;
          updatedTasks.push(existing);
          continue;
        }
        const updated = updateTask(existing.id, {
          assigneeId: body.assigneeId,
          assigneeName: body.assigneeName,
          reviewerId: body.reviewerId,
          status: existing.status === 'PENDING' ? 'IN_PROGRESS' : existing.status,
        });
        if (updated) {
          assigned += 1;
          updatedTasks.push(updated);
        } else {
          skipped += 1;
        }
        continue;
      }
      // task가 없는 경우 — 처리 완료된 영상에 대해 신규 task 생성
      const video = videos.find((v) => v.id === videoId);
      if (!video) {
        skipped += 1;
        continue;
      }
      const created = createTask({
        videoId,
        videoName: video.cctvName,
        assigneeId: body.assigneeId,
        assigneeName: body.assigneeName,
        reviewerId: body.reviewerId,
      });
      assigned += 1;
      updatedTasks.push(created);
    }

    const response: BulkAssignResponse = {
      assigned,
      skipped,
      total: body.videoIds.length,
      tasks: updatedTasks,
    };
    return ok(response);
  }),
];
