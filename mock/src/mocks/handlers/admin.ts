import { http } from 'msw';
import { users } from '../data/users';
import { ok, fail, paginate, parsePageParams } from './_utils';
import type { UserDto } from '../../api/types';

let mutableUsers = [...users];

// ── DB 영속화 대상 (LS_SYSTEM_CONFIG 예정) ─────────────────────────────────
interface FfmpegConfig {
  threads: number;
  outputFps: number;
}

interface BatchConfig {
  interval: number;
  concurrency: number;
}

// ── read-only (actuator/health 기반 실시간 조회) ───────────────────────────
interface ExternalSystemStatus {
  name: string;
  url: string;
  status: 'CONNECTED' | 'DISCONNECTED';
  latencyMs: number;
}

interface AdminSettings {
  ffmpegConfig: FfmpegConfig;
  batchConfig: BatchConfig;
  /** @deprecated batchInterval — 하위호환용. batchConfig.interval 사용 권장 */
  batchInterval: number;
  externalSystems: ExternalSystemStatus[];
}

const defaultSettings: AdminSettings = {
  ffmpegConfig: {
    threads: 4,
    outputFps: 1,
  },
  batchConfig: {
    interval: 60,
    concurrency: 2,
  },
  batchInterval: 60,
  externalSystems: [
    { name: '관제서버', url: 'https://control.example.com', status: 'CONNECTED', latencyMs: 12 },
    { name: '포털서버', url: 'https://portal.example.com', status: 'CONNECTED', latencyMs: 8 },
    { name: '비식별서버', url: 'https://deident.example.com', status: 'DISCONNECTED', latencyMs: 320 },
    { name: 'AI서버', url: 'http://localhost:9300', status: 'CONNECTED', latencyMs: 45 },
    { name: 'Gitea', url: 'https://gitea.example.com', status: 'CONNECTED', latencyMs: 22 },
  ],
};

let currentSettings: AdminSettings = { ...defaultSettings, ffmpegConfig: { ...defaultSettings.ffmpegConfig }, batchConfig: { ...defaultSettings.batchConfig } };

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

  // ── 전체 설정 조회 (GET) ────────────────────────────────────────────────
  http.get('/api/v1/admin/settings', () => {
    return ok(currentSettings);
  }),

  // ── FFmpeg 설정 저장 (PUT /settings/ffmpeg) — DB 영속화 대상 ────────────
  http.put('/api/v1/admin/settings/ffmpeg', async ({ request }) => {
    const body = (await request.json()) as Partial<FfmpegConfig>;
    currentSettings = {
      ...currentSettings,
      ffmpegConfig: { ...currentSettings.ffmpegConfig, ...body },
    };
    return ok(currentSettings.ffmpegConfig);
  }),

  // ── 배치 처리 설정 저장 (PUT /settings/batch) — DB 영속화 대상 ──────────
  http.put('/api/v1/admin/settings/batch', async ({ request }) => {
    const body = (await request.json()) as Partial<BatchConfig>;
    currentSettings = {
      ...currentSettings,
      batchConfig: { ...currentSettings.batchConfig, ...body },
      // 하위호환
      batchInterval: body.interval ?? currentSettings.batchConfig.interval,
    };
    return ok(currentSettings.batchConfig);
  }),

  // ── 기존 단일 PUT 유지 (하위호환) ────────────────────────────────────────
  http.put('/api/v1/admin/settings', async ({ request }) => {
    const body = (await request.json()) as Partial<AdminSettings>;
    currentSettings = { ...currentSettings, ...body };
    return ok(currentSettings);
  }),
];
