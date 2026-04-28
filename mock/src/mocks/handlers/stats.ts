import { http } from 'msw';
import { workerStats, overallStat } from '../data/stats';
import { ok } from './_utils';

export const statsHandlers = [
  http.get('/api/v1/stats/worker', ({ request }) => {
    const url = new URL(request.url);
    const workerId = url.searchParams.get('workerId');
    if (workerId) {
      const stat = workerStats.find((w) => w.workerId === workerId);
      return ok(stat ?? workerStats[0]);
    }
    return ok(workerStats);
  }),

  http.get('/api/v1/stats/overall', () => {
    return ok(overallStat);
  }),
];
