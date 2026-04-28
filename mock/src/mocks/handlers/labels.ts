import { http, delay } from 'msw';
import { getFrameLabels, setFrameLabels, generateAutoLabels } from '../data/labels';
import { ok } from './_utils';
import type { LabelObject } from '../../api/types';

export const labelsHandlers = [
  http.get('/api/v1/videos/:videoId/frames/:frameNo/labels', ({ params }) => {
    const videoId = params['videoId'] as string;
    const frameNo = parseInt(params['frameNo'] as string, 10);
    return ok(getFrameLabels(videoId, frameNo));
  }),

  http.put('/api/v1/videos/:videoId/frames/:frameNo/labels', async ({ params, request }) => {
    const videoId = params['videoId'] as string;
    const frameNo = parseInt(params['frameNo'] as string, 10);
    const body = (await request.json()) as { objects: LabelObject[] };
    const result = setFrameLabels(videoId, frameNo, body.objects);
    return ok(result);
  }),

  http.post('/api/v1/videos/:videoId/labels/auto', async ({ params }) => {
    await delay(500);
    const videoId = params['videoId'] as string;
    const objects = generateAutoLabels(videoId, 0);
    return ok({ videoId, objects });
  }),
];
