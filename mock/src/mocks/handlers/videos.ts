import { http } from 'msw';
import { videos } from '../data/videos';
import { getFrames } from '../data/frames';
import { ok, fail, paginate, parsePageParams } from './_utils';

export const videosHandlers = [
  http.get('/api/v1/videos', ({ request }) => {
    const url = new URL(request.url);
    const { page, size } = parsePageParams(url);
    const status = url.searchParams.get('status');
    const eventType = url.searchParams.get('eventType');
    const q = url.searchParams.get('q');

    let filtered = [...videos];
    if (status) filtered = filtered.filter((v) => v.batchStatus === status || v.taskStatus === status);
    if (eventType) filtered = filtered.filter((v) => v.eventType === eventType);
    if (q) {
      const lq = q.toLowerCase();
      filtered = filtered.filter(
        (v) => v.cctvName.toLowerCase().includes(lq) || v.eventType.toLowerCase().includes(lq),
      );
    }
    return ok(paginate(filtered, page, size));
  }),

  http.get('/api/v1/videos/monitoring', () => {
    const processing = videos.filter((v) => v.batchStatus === 'PROCESSING');
    return ok(processing);
  }),

  http.get('/api/v1/videos/:id', ({ params }) => {
    const video = videos.find((v) => v.id === params['id']);
    if (!video) return fail('VIDEO_NOT_FOUND', '영상을 찾을 수 없습니다.', 404);
    return ok(video);
  }),

  http.get('/api/v1/videos/:id/frames', ({ params }) => {
    const videoId = params['id'] as string;
    const frames = getFrames(videoId);
    return ok(frames);
  }),
];
