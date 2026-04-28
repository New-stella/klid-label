import { http } from 'msw';
import { historyByVideo } from '../data/history';
import { ok, fail } from './_utils';
import { labelsByVideoFrame } from '../data/labels';
import type { LabelObject } from '../../api/types';

export const historyHandlers = [
  http.get('/api/v1/history/videos/:videoId', ({ params }) => {
    const videoId = params['videoId'] as string;
    const commits = historyByVideo[videoId];
    if (!commits) return ok([]);
    return ok(commits);
  }),

  http.get('/api/v1/history/videos/:videoId/diff', ({ params, request }) => {
    const videoId = params['videoId'] as string;
    const url = new URL(request.url);
    const from = url.searchParams.get('from');
    const to = url.searchParams.get('to');

    if (!from || !to) {
      return fail('INVALID_PARAMS', 'from, to 파라미터가 필요합니다.');
    }

    // simplified mock diff
    const frameLabels = labelsByVideoFrame[videoId];
    const allObjects: LabelObject[] = frameLabels
      ? Object.values(frameLabels).flatMap((fl) => fl.objects)
      : [];

    const added = allObjects.slice(0, 3);
    const removed = allObjects.slice(3, 5);
    const modified = allObjects.slice(5, 8);

    return ok({ added, removed, modified, from, to });
  }),

  http.post('/api/v1/history/videos/:videoId/rollback', async ({ params, request }) => {
    const videoId = params['videoId'] as string;
    const body = (await request.json()) as { hash: string; reason: string };
    return ok({ videoId, hash: body.hash, reason: body.reason, rolledBackAt: new Date().toISOString() });
  }),

  http.post('/api/v1/history/videos/:videoId/commit', async ({ params, request }) => {
    const videoId = params['videoId'] as string;
    const body = (await request.json()) as { message?: string; frame: number; objectsCount: number };
    const hashChars = '0123456789abcdef';
    let hash = '';
    for (let i = 0; i < 16; i++) {
      hash += hashChars[Math.floor(Math.random() * 16)];
    }
    const commit = {
      hash,
      videoId,
      message: body.message ?? `frame ${body.frame} 자동 저장`,
      authorName: '현재 사용자',
      committedAt: new Date().toISOString(),
    };
    if (!historyByVideo[videoId]) {
      historyByVideo[videoId] = [];
    }
    historyByVideo[videoId].unshift(commit);
    return ok(commit);
  }),
];
