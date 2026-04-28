import { http } from 'msw';
import { users } from '../data/users';
import { ok, fail, paginate, parsePageParams } from './_utils';
import type { UserDto } from '../../api/types';

let mutableUsers = [...users];

const defaultSettings = {
  ffmpegConfig: {
    threads: 4,
    outputFps: 1,
    resolution: '1920x1080',
  },
  batchInterval: 60,
  externalSystems: [
    { name: '관제서버', url: 'https://control.example.com', status: 'CONNECTED' },
    { name: '포털서버', url: 'https://portal.example.com', status: 'CONNECTED' },
    { name: 'AI서버', url: 'http://localhost:9300', status: 'CONNECTED' },
    { name: 'Gitea', url: 'https://gitea.example.com', status: 'CONNECTED' },
    { name: '비식별서버', url: 'https://deident.example.com', status: 'DISCONNECTED' },
  ],
};

let currentSettings = { ...defaultSettings };

export const adminHandlers = [
  http.get('/api/v1/admin/users', ({ request }) => {
    const url = new URL(request.url);
    const { page, size } = parsePageParams(url);
    const role = url.searchParams.get('role');
    const filtered = role ? mutableUsers.filter((u) => u.role === role) : mutableUsers;
    return ok(paginate(filtered, page, size));
  }),

  http.put('/api/v1/admin/users/:id', async ({ params, request }) => {
    const userId = params['id'] as string;
    const body = (await request.json()) as Partial<Pick<UserDto, 'role' | 'status'>>;
    const idx = mutableUsers.findIndex((u) => u.id === userId);
    if (idx === -1) return fail('USER_NOT_FOUND', '사용자를 찾을 수 없습니다.', 404);
    mutableUsers[idx] = { ...mutableUsers[idx], ...body };
    return ok(mutableUsers[idx]);
  }),

  http.get('/api/v1/admin/settings', () => {
    return ok(currentSettings);
  }),

  http.put('/api/v1/admin/settings', async ({ request }) => {
    const body = (await request.json()) as typeof currentSettings;
    currentSettings = { ...currentSettings, ...body };
    return ok(currentSettings);
  }),
];
