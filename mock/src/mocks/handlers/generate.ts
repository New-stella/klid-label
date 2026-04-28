import { http } from 'msw';
import { ok, fail } from './_utils';

export type BackgroundGenerateType = 'FIRE' | 'FLOOD';

export interface BackgroundGenerateRequestBody {
  videoId: string;
  frameIndex: number;
  type: BackgroundGenerateType;
}

export interface BackgroundGenerateRecord {
  id: string;
  videoId: string;
  frameIndex: number;
  type: BackgroundGenerateType;
  requestedAt: string;
}

const requests = new Map<string, BackgroundGenerateRecord>();
let counter = 1;

export const generateHandlers = [
  http.post('/api/v1/generate/background', async ({ request }) => {
    const body = (await request.json()) as BackgroundGenerateRequestBody;
    if (!body || !body.videoId || body.frameIndex === undefined || !body.type) {
      return fail('INVALID_PARAMS', '필수 파라미터가 누락되었습니다.');
    }
    const id = `bg-gen-${String(counter++).padStart(3, '0')}`;
    const record: BackgroundGenerateRecord = {
      id,
      videoId: body.videoId,
      frameIndex: body.frameIndex,
      type: body.type,
      requestedAt: new Date().toISOString(),
    };
    requests.set(id, record);
    return ok({ jobId: id });
  }),

  http.get('/api/v1/generate/background/:jobId', ({ params }) => {
    const id = params['jobId'] as string;
    const record = requests.get(id);
    if (!record) return fail('NOT_FOUND', '요청을 찾을 수 없습니다.', 404);
    return ok(record);
  }),
];
