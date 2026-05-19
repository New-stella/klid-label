import { http } from 'msw';
import { videos } from '../data/videos';
import { getFrames } from '../data/frames';
import { ok, fail, paginate, parsePageParams } from './_utils';

export const videosHandlers = [
  http.get('/api/v1/videos', ({ request }) => {
    const url = new URL(request.url);
    const { page, size } = parsePageParams(url);
    const status = url.searchParams.get('status');
    const eventType = url.searchParams.get('eventType');
    const q = url.searchParams.get('q');
    // BE 호환 파라미터 — FE/BE 양쪽에서 동일한 API 표면 유지
    const dataSttsCd = url.searchParams.get('dataSttsCd');
    const reviewStatusCd = url.searchParams.get('reviewStatusCd');

    let filtered = [...videos];
    if (status) filtered = filtered.filter((v) => v.batchStatus === status || v.taskStatus === status);
    // dataSttsCd: 배치 파이프라인 상태 (LS_DATA_RAW.DATA_STTS_CD) 매핑
    if (dataSttsCd && dataSttsCd.trim()) {
      const code = dataSttsCd.trim();
      filtered = filtered.filter((v) => v.batchStatus === code);
    }
    // reviewStatusCd: 검수 상태 (LS_RAW_DATA_STATUS.DATA_STTS_CD) 매핑.
    // mock 시드에서는 taskStatus 가 검수 상태 역할을 한다 (BATCH_COMPLETED/IN_PROGRESS/REVIEW_PENDING/REVIEW/COMPLETED/REJECTED).
    // 'APPROVED' 요청은 검수 완료(=mock 의 taskStatus 'COMPLETED')로 매핑.
    if (reviewStatusCd && reviewStatusCd.trim()) {
      const code = reviewStatusCd.trim();
      const mapped = code === 'APPROVED' ? 'COMPLETED' : code;
      filtered = filtered.filter((v) => v.taskStatus === mapped);
    }
    if (eventType) filtered = filtered.filter((v) => v.eventType === eventType);
    if (q) {
      const lq = q.toLowerCase();
      filtered = filtered.filter(
        (v) => v.cctvName.toLowerCase().includes(lq) || v.eventType.toLowerCase().includes(lq),
      );
    }
    return ok(paginate(filtered, page, size));
  }),

  http.get('/api/v1/videos/monitoring', () => {
    const processing = videos.filter((v) => v.batchStatus === 'PROCESSING');
    return ok(processing);
  }),

  http.get('/api/v1/videos/:id', ({ params }) => {
    const video = videos.find((v) => v.id === params['id']);
    if (!video) return fail('VIDEO_NOT_FOUND', '영상을 찾을 수 없습니다.', 404);
    return ok(video);
  }),

  http.get('/api/v1/videos/:id/frames', ({ params }) => {
    const videoId = params['id'] as string;
    const frames = getFrames(videoId);
    return ok(frames);
  }),
];
