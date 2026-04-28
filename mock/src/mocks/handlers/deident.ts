import { http } from 'msw';
import { deidents, updateDeident } from '../data/deident';
import { ok, fail, paginate, parsePageParams } from './_utils';
import dayjs from 'dayjs';

export const deidentHandlers = [
  http.get('/api/v1/deident', ({ request }) => {
    const url = new URL(request.url);
    const { page, size } = parsePageParams(url);
    const status = url.searchParams.get('status');
    const filtered = status ? deidents.filter((d) => d.status === status) : deidents;
    return ok(paginate(filtered, page, size));
  }),

  http.get('/api/v1/deident/:id', ({ params }) => {
    const item = deidents.find((d) => d.id === params['id']);
    if (!item) return fail('DEIDENT_NOT_FOUND', '비식별 항목을 찾을 수 없습니다.', 404);
    return ok(item);
  }),

  http.post('/api/v1/deident/:id/retry', ({ params }) => {
    const updated = updateDeident(params['id'] as string, {
      status: 'PENDING',
      processedAt: undefined,
      deidentifiedUrl: undefined,
    });
    if (!updated) return fail('DEIDENT_NOT_FOUND', '비식별 항목을 찾을 수 없습니다.', 404);
    // simulate async processing start
    setTimeout(() => {
      updateDeident(params['id'] as string, {
        status: 'SUCCESS',
        processedAt: dayjs().toISOString(),
        deidentifiedUrl: `https://picsum.photos/seed/${params['id'] as string}-retry/640/360`,
      });
    }, 2000);
    return ok(updated);
  }),
];
