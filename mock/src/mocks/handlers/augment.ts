import { http } from 'msw';
import { augmentJobs, addAugmentJob, findAugmentJob } from '../data/augment';
import { videos } from '../data/videos';
import type { AugmentDecision } from '../../api/types';
import { ok, fail, paginate, parsePageParams } from './_utils';
import dayjs from 'dayjs';

let jobCounter = 100;

export const augmentHandlers = [
  http.get('/api/v1/augment', ({ request }) => {
    const url = new URL(request.url);
    const { page, size } = parsePageParams(url);
    return ok(paginate([...augmentJobs], page, size));
  }),

  http.get('/api/v1/augment/:id', ({ params }) => {
    const job = findAugmentJob(params['id'] as string);
    if (!job) return fail('AUGMENT_NOT_FOUND', '증강 작업을 찾을 수 없습니다.', 404);
    return ok(job);
  }),

  http.post('/api/v1/augment/request', async ({ request }) => {
    const body = (await request.json()) as { videoIds: string[]; types: ('WINTER' | 'NIGHT' | 'RAIN' | 'RESOLUTION')[] };

    // SFR-07 — 검수 완료(승인)된 영상만 증강 요청 가능. taskStatus === 'COMPLETED'을 승인 판정으로 사용.
    const approvedIds: string[] = [];
    const rejectedIds: string[] = [];
    for (const vid of body.videoIds) {
      const v = videos.find((x) => x.id === vid);
      if (v && v.taskStatus === 'COMPLETED') {
        approvedIds.push(vid);
      } else {
        rejectedIds.push(vid);
      }
    }

    if (approvedIds.length === 0) {
      return fail(
        'AUGMENT_NO_APPROVED_VIDEO',
        '검수 완료된 영상만 증강 요청 가능합니다.',
        422,
      );
    }

    const newJob = {
      id: `aug-${String(jobCounter++).padStart(4, '0')}`,
      videoIds: approvedIds,
      types: body.types,
      status: 'PENDING' as const,
      progress: 0,
      labelIntegrity: 98,
      createdAt: dayjs().toISOString(),
      decision: 'PENDING' as const,
    };
    addAugmentJob(newJob);

    if (rejectedIds.length > 0) {
      // 일부 영상이 차단된 경우에도 승인된 영상으로 잡은 생성하되, 응답 message에 차단 카운트 명시
      return ok({
        ...newJob,
        message: `검수 미완료 ${rejectedIds.length}건은 제외되고 ${approvedIds.length}건으로 처리됩니다.`,
      });
    }
    return ok(newJob);
  }),

  // SFR-07 — 학습데이터 활용 여부 결정
  http.post('/api/v1/augment/:id/decision', async ({ params, request }) => {
    const job = findAugmentJob(params['id'] as string);
    if (!job) return fail('AUGMENT_NOT_FOUND', '증강 작업을 찾을 수 없습니다.', 404);
    if (job.status !== 'COMPLETED') {
      return fail(
        'AUGMENT_NOT_COMPLETED',
        '완료된 증강 작업만 결정할 수 있습니다.',
        400,
      );
    }
    const body = (await request.json()) as {
      decision: 'ACCEPTED' | 'REJECTED';
      reason?: string;
    };
    if (body.decision !== 'ACCEPTED' && body.decision !== 'REJECTED') {
      return fail('INVALID_DECISION', '결정 값이 올바르지 않습니다.', 400);
    }
    job.decision = body.decision as AugmentDecision;
    job.decisionAt = dayjs().toISOString();
    job.decisionBy = 'user-0002';
    job.decisionReason = body.decision === 'REJECTED' ? (body.reason ?? '') : undefined;
    return ok(job);
  }),
];
