import { http } from 'msw';
import { martDatasets } from '../data/mart';
import type { MartDataset } from '../../api/types';
import { ok, paginate, parsePageParams } from './_utils';

let exportJobCounter = 1;

export const exportHandlers = [
  http.post('/api/v1/export', async ({ request }) => {
    const body = (await request.json()) as {
      format: string;
      videoIds: string[];
      saveNas: boolean;
      registerMart: boolean;
    };
    const jobId = `export-${String(exportJobCounter++).padStart(4, '0')}`;
    return ok({
      jobId,
      format: body.format,
      videoCount: body.videoIds.length,
      previewUrl: `https://picsum.photos/seed/${jobId}/640/360`,
    });
  }),

  http.get('/api/v1/mart', ({ request }) => {
    const url = new URL(request.url);
    const { page, size } = parsePageParams(url);
    const eventType = url.searchParams.get('eventType');
    const weather = url.searchParams.get('weather');
    const season = url.searchParams.get('season');
    const q = url.searchParams.get('q');

    let filtered = [...martDatasets];
    if (eventType) filtered = filtered.filter((d) => d.eventType === eventType);
    if (weather) filtered = filtered.filter((d) => d.weather === weather);
    if (season) filtered = filtered.filter((d) => d.season === season);
    if (q) {
      const lq = q.toLowerCase();
      filtered = filtered.filter((d) => d.name.toLowerCase().includes(lq));
    }
    return ok(paginate(filtered, page, size));
  }),

  http.get('/api/v1/mart/:id/download', ({ params }) => {
    const id = params['id'] as string;
    return ok({ downloadUrl: `https://example.com/downloads/mart/${id}.zip` });
  }),

  http.post('/api/v1/mart', async ({ request }) => {
    const body = (await request.json()) as {
      name: string;
      version: string;
      description?: string;
      format: 'COCO' | 'YOLO' | 'PASCAL_VOC';
      eventType: string;
      weather: string;
      season: string;
      videoIds: string[];
      linkedModels?: string[];
    };
    const id = `mart-${String(martDatasets.length + 1).padStart(4, '0')}`;
    const newDataset: MartDataset = {
      id,
      name: body.name,
      eventType: body.eventType,
      weather: body.weather,
      season: body.season,
      count: body.videoIds.length * 100,
      version: body.version,
      sizeBytes: body.videoIds.length * 256 * 1024 * 1024,
      createdAt: new Date().toISOString(),
      linkedModels: body.linkedModels ?? [],
    };
    martDatasets.unshift(newDataset);
    return ok(newDataset);
  }),
];
