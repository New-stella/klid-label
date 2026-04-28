import { http } from 'msw';
import { reviews, updateReview } from '../data/reviews';
import { ok, fail, paginate, parsePageParams } from './_utils';
import type { ReviewDto } from '../../api/types';

export const reviewsHandlers = [
  http.get('/api/v1/reviews/pending', ({ request }) => {
    const url = new URL(request.url);
    const { page, size } = parsePageParams(url);
    const pending = reviews.filter((r) => r.status === 'PENDING');
    return ok(paginate(pending, page, size));
  }),

  http.get('/api/v1/reviews', ({ request }) => {
    const url = new URL(request.url);
    const { page, size } = parsePageParams(url);
    const status = url.searchParams.get('status');
    const filtered = status ? reviews.filter((r) => r.status === status) : reviews;
    return ok(paginate(filtered, page, size));
  }),

  http.get('/api/v1/reviews/:id', ({ params }) => {
    const review = reviews.find((r) => r.id === params['id']);
    if (!review) return fail('REVIEW_NOT_FOUND', '검수 항목을 찾을 수 없습니다.', 404);
    return ok(review);
  }),

  http.post('/api/v1/reviews/:id/approve', ({ params }) => {
    const updated = updateReview(params['id'] as string, { status: 'APPROVED' });
    if (!updated) return fail('REVIEW_NOT_FOUND', '검수 항목을 찾을 수 없습니다.', 404);
    return ok(updated);
  }),

  http.post('/api/v1/reviews/:id/reject', async ({ params, request }) => {
    const body = (await request.json()) as { reason: string; issues?: ReviewDto['issues'] };
    const updated = updateReview(params['id'] as string, {
      status: 'REJECTED',
      rejectReason: body.reason,
      issues: body.issues,
    });
    if (!updated) return fail('REVIEW_NOT_FOUND', '검수 항목을 찾을 수 없습니다.', 404);
    return ok(updated);
  }),
];
