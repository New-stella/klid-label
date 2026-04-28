import { http } from 'msw';
import { ok, fail, paginate, parsePageParams } from './_utils';
import type { PresetDto } from '../../api/types';
import dayjs from 'dayjs';

let presets: PresetDto[] = [
  {
    id: 'preset-0001',
    name: '교통 표준 프리셋',
    description: '교통사고 시나리오 라벨링 기본 프리셋',
    labelCodes: ['PERSON', 'VEHICLE', 'BICYCLE', 'MOTORCYCLE', 'TRUCK', 'BUS'],
    createdAt: dayjs().subtract(30, 'day').toISOString(),
    updatedAt: dayjs().subtract(10, 'day').toISOString(),
  },
  {
    id: 'preset-0002',
    name: '화재/연기 프리셋',
    description: '화재 재난 시나리오 라벨링 프리셋',
    labelCodes: ['FIRE', 'SMOKE', 'PERSON'],
    createdAt: dayjs().subtract(20, 'day').toISOString(),
    updatedAt: dayjs().subtract(5, 'day').toISOString(),
  },
  {
    id: 'preset-0003',
    name: '침수 시나리오 프리셋',
    description: '홍수/침수 재난 시나리오',
    labelCodes: ['PERSON', 'VEHICLE', 'TRUCK'],
    createdAt: dayjs().subtract(15, 'day').toISOString(),
    updatedAt: dayjs().subtract(2, 'day').toISOString(),
  },
];

let presetCounter = 10;

export const presetHandlers = [
  http.get('/api/v1/presets', ({ request }) => {
    const url = new URL(request.url);
    const { page, size } = parsePageParams(url);
    return ok(paginate(presets, page, size));
  }),

  http.post('/api/v1/presets', async ({ request }) => {
    const body = (await request.json()) as Omit<PresetDto, 'id' | 'createdAt' | 'updatedAt'>;
    const newPreset: PresetDto = {
      ...body,
      id: `preset-${String(presetCounter++).padStart(4, '0')}`,
      createdAt: dayjs().toISOString(),
      updatedAt: dayjs().toISOString(),
    };
    presets = [newPreset, ...presets];
    return ok(newPreset);
  }),

  http.put('/api/v1/presets/:id', async ({ params, request }) => {
    const presetId = params['id'] as string;
    const body = (await request.json()) as Partial<PresetDto>;
    const idx = presets.findIndex((p) => p.id === presetId);
    if (idx === -1) return fail('PRESET_NOT_FOUND', '프리셋을 찾을 수 없습니다.', 404);
    presets[idx] = { ...presets[idx], ...body, updatedAt: dayjs().toISOString() };
    return ok(presets[idx]);
  }),

  http.delete('/api/v1/presets/:id', ({ params }) => {
    const presetId = params['id'] as string;
    const idx = presets.findIndex((p) => p.id === presetId);
    if (idx === -1) return fail('PRESET_NOT_FOUND', '프리셋을 찾을 수 없습니다.', 404);
    presets = presets.filter((p) => p.id !== presetId);
    return ok({ deleted: true });
  }),
];
