import { http } from 'msw';
import { tasks, updateTask } from '../data/tasks';
import { ok, fail, paginate, parsePageParams } from './_utils';

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
];
