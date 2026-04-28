import { http, delay } from 'msw';
import { videos } from '../data/videos';
import { generateAutoLabels } from '../data/labels';
import { ok } from './_utils';
import dayjs from 'dayjs';
import type { PortalUserDto } from '../../api/types';

const portalUser: PortalUserDto = {
  id: 'user-0012',
  name: '홍길동',
  uploadCount: 5,
  labeledCount: 3,
  downloadDeadline: dayjs().add(30, 'day').toISOString(),
};

export const portalHandlers = [
  http.get('/api/v1/portal/me', () => {
    return ok(portalUser);
  }),

  http.post('/api/v1/portal/upload', () => {
    return ok({ id: `portal-upload-${Date.now()}`, status: 'PROCESSING' });
  }),

  http.get('/api/v1/portal/labels', ({ request }) => {
    const url = new URL(request.url);
    const page = parseInt(url.searchParams.get('page') ?? '0', 10);
    const size = parseInt(url.searchParams.get('size') ?? '20', 10);
    // Return first 5 videos as portal user's uploads, each with its own downloadDeadline.
    // Distribution (index-based, deterministic):
    //   i=0 → D-3  (만료, expired)
    //   i=1 → D+5  (임박, urgent)
    //   i=2 → D+14 (보통)
    //   i=3 → D+30 (여유)
    //   i=4 → D+56 (여유)
    const deadlineOffsets = [-3, 5, 14, 30, 56];
    const portalVideos = videos.slice(0, 5).map((v, i) => ({
      ...v,
      downloadDeadline: dayjs().add(deadlineOffsets[i], 'day').toISOString(),
    }));
    const start = page * size;
    const content = portalVideos.slice(start, start + size);
    return ok({
      content,
      totalElements: portalVideos.length,
      totalPages: Math.max(1, Math.ceil(portalVideos.length / size)),
      number: page,
      size,
    });
  }),

  http.post('/api/v1/portal/auto-label/:videoId', async ({ params }) => {
    await delay(500);
    const videoId = params['videoId'] as string;
    const objects = generateAutoLabels(videoId, 0);
    return ok({ videoId, objects, processedAt: new Date().toISOString() });
  }),
];
