import { http } from 'msw';
import { ok } from './_utils';

let exportJobCounter = 1;

export const exportHandlers = [
  http.post('/api/v1/export', async ({ request }) => {
    const body = (await request.json()) as {
      format: string;
      videoIds: string[];
      saveNas: boolean;
    };
    const jobId = `export-${String(exportJobCounter++).padStart(4, '0')}`;
    return ok({
      jobId,
      format: body.format,
      videoCount: body.videoIds.length,
      previewUrl: `https://picsum.photos/seed/${jobId}/640/360`,
    });
  }),
];
