// 배치 실패 관리 API 계약 테스트. [@design API-043] [@design API-167] [@design API-198]
// [@design API-199] [@design API-200] [@design API-201]

import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import MockAdapter from 'axios-mock-adapter';

import { apiClient } from '@/lib/api/client';

import {
  getVideo,
  rerunBatchStage,
  retryBatch,
  retryBatchBulk,
  skipBatchStage,
  unskipBatchStage,
} from '../api';
import type { StageBundle } from '../types';

function ok(data: unknown) {
  return { success: true, data, message: null, errorCode: null };
}

describe('배치 실패 관리 API', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
  });

  afterEach(() => {
    mock.restore();
  });

  describe('영상 상세 정규화', () => {
    it('실패사유와_건너뛴_작업묶음을_그대로_읽는다', async () => {
      mock.onGet('/videos/7').reply(
        200,
        ok({
          id: 7,
          cctvName: 'CCTV-7',
          batchFailureReason: '외부 시계열 분석 서버가 응답하지 않았습니다.',
          skippedStages: ['VLM', 'AUTOLABEL'],
        }),
      );

      const v = await getVideo(7);

      expect(v.batchFailureReason).toBe('외부 시계열 분석 서버가 응답하지 않았습니다.');
      expect(v.skippedStages).toEqual(['VLM', 'AUTOLABEL']);
    });

    it('필드가_없는_구_응답은_사유null_스킵빈배열로_떨어진다', async () => {
      mock.onGet('/videos/7').reply(200, ok({ id: 7, cctvName: 'CCTV-7' }));

      const v = await getVideo(7);

      expect(v.batchFailureReason).toBeNull();
      expect(v.skippedStages).toEqual([]);
    });

    it('공백만_있는_사유는_사유없음과_같게_null로_접힌다', async () => {
      mock.onGet('/videos/7').reply(200, ok({ id: 7, cctvName: 'CCTV-7', batchFailureReason: '   ' }));

      const v = await getVideo(7);

      expect(v.batchFailureReason).toBeNull();
    });

    it('알_수_없는_묶음_코드는_걸러진다', async () => {
      // 신 BE 가 대상을 넓혀도 화면에 기술 코드가 새거나 그 값이 경로 세그먼트가 되면 안 된다.
      mock.onGet('/videos/7').reply(
        200,
        ok({ id: 7, cctvName: 'CCTV-7', skippedStages: ['VLM', 'DEIDENTIFY', '../../etc'] }),
      );

      const v = await getVideo(7);

      expect(v.skippedStages).toEqual(['VLM']);
    });

    // ★ 값 공간이 개별 단계에서 **작업 묶음**으로 바뀌었다 — 구 값이 그대로 통과하면 그 코드가 다시
    //   경로 세그먼트가 되고(서버는 400), 화면에도 개별 단계 조작이 되살아난다.
    it('★구_개별단계_코드_YOLO_SAM2_는_더_이상_통과하지_않는다', async () => {
      mock.onGet('/videos/7').reply(
        200,
        ok({ id: 7, cctvName: 'CCTV-7', skippedStages: ['YOLO', 'SAM2', 'AUTOLABEL'] }),
      );

      const v = await getVideo(7);

      expect(v.skippedStages).toEqual(['AUTOLABEL']);
    });
  });

  describe('재실행 / 스킵 / 스킵 해제', () => {
    it('재실행은_영상별_retry_경로로_POST_한다', async () => {
      mock.onPost('/videos/7/batch/retry').reply(200, ok({ rawSn: 7, stage: 'VLM' }));

      const result = await retryBatch(7);

      expect(result).toEqual({ rawSn: 7, stage: 'VLM' });
      expect(mock.history.post[0].url).toBe('/videos/7/batch/retry');
    });

    it('스킵은_사유를_본문에_담아_묶음별_경로로_POST_한다', async () => {
      mock.onPost('/videos/7/batch/stages/VLM/skip').reply(
        200,
        ok({
          rawSn: 7,
          stage: 'VLM',
          skipped: true,
          reason: '벤더 장애',
          skippedAt: '2026-08-12T17:20:00',
        }),
      );

      const result = await skipBatchStage(7, 'VLM', '벤더 장애');

      expect(result.skipped).toBe(true);
      expect(JSON.parse(mock.history.post[0].data)).toEqual({ reason: '벤더 장애' });
    });

    it('스킵_해제는_같은_하위리소스로_DELETE_하고_204_본문없음을_받는다', async () => {
      mock.onDelete('/videos/7/batch/stages/AUTOLABEL/skip').reply(204);

      await expect(unskipBatchStage(7, 'AUTOLABEL')).resolves.toBeUndefined();
      expect(mock.history.delete[0].url).toBe('/videos/7/batch/stages/AUTOLABEL/skip');
    });

    it('조작대상이_아닌_묶음은_요청을_보내지_않는다', async () => {
      // 경로 조작(CWE-22) 차단 — 타입 밖에서 흘러온 값이 세그먼트가 되지 않게 한다.
      // ★ 구 개별단계 코드(`YOLO`)도 여기서 막힌다 — 값 공간이 묶음으로 바뀌었기 때문이다.
      const bogus = 'DEIDENTIFY' as StageBundle;
      const legacyStage = 'YOLO' as StageBundle;

      expect(() => skipBatchStage(7, bogus, '사유')).toThrow();
      expect(() => unskipBatchStage(7, bogus)).toThrow();
      expect(() => rerunBatchStage(7, bogus)).toThrow();
      expect(() => rerunBatchStage(7, legacyStage)).toThrow();
      expect(mock.history.post).toHaveLength(0);
      expect(mock.history.delete).toHaveLength(0);
    });
  });

  // 되돌린 작업 묶음 재수행 — 전체 재기동과 **다른 요청**이다. [@design API-201]
  describe('되돌린 작업 묶음 재수행', () => {
    it('묶음별_rerun_경로로_POST_하고_본문을_보내지_않는다', async () => {
      // ★ 구 `{scope}` 본문은 폐지됐다 — 묶음이 곧 범위라 고를 것이 없다. 본문을 되살리면
      //   보간을 뺀 부분 수행이 다시 가능해진다.
      mock
        .onPost('/videos/7/batch/stages/AUTOLABEL/rerun')
        .reply(200, ok({ rawSn: 7, stage: 'AUTOLABEL', accepted: true }));

      const result = await rerunBatchStage(7, 'AUTOLABEL');

      expect(mock.history.post[0].url).toBe('/videos/7/batch/stages/AUTOLABEL/rerun');
      expect(mock.history.post[0].data).toBeUndefined();
      expect(result.accepted).toBe(true);
      expect(result.stage).toBe('AUTOLABEL');
    });

    it('전체_재기동_경로와_섞이지_않는다', async () => {
      // 완주 영상에 전체 재기동을 쓰면 보간이 함께 돌아 사람이 손댄 보간 라벨이 지워진다 —
      // 두 요청이 같은 경로로 수렴하면 그 파괴 경로가 조용히 되살아난다.
      mock
        .onPost('/videos/7/batch/stages/VLM/rerun')
        .reply(200, ok({ rawSn: 7, stage: 'VLM', accepted: true }));

      await rerunBatchStage(7, 'VLM');

      expect(mock.history.post.map((h) => h.url)).not.toContain('/videos/7/batch/retry');
    });
  });

  describe('일괄 재시작', () => {
    it('중복을_제거한_목록을_보내고_건별_결과를_받는다', async () => {
      mock.onPost('/videos/batch/retry').reply(
        200,
        ok({
          successCount: 1,
          failureCount: 1,
          results: [
            { rawSn: 12, success: true, reason: null },
            { rawSn: 43, success: false, reason: '이미 재처리가 진행 중입니다.' },
          ],
        }),
      );

      const result = await retryBatchBulk([12, 43, 12]);

      expect(JSON.parse(mock.history.post[0].data)).toEqual({ rawSns: [12, 43] });
      expect(result.successCount).toBe(1);
      expect(result.results[1].reason).toBe('이미 재처리가 진행 중입니다.');
    });

    it('한_건도_성공하지_못해도_200이라_결과목록으로_판정한다', async () => {
      mock.onPost('/videos/batch/retry').reply(
        200,
        ok({
          successCount: 0,
          failureCount: 1,
          results: [{ rawSn: 12, success: false, reason: '실패 상태가 아닙니다.' }],
        }),
      );

      const result = await retryBatchBulk([12]);

      expect(result.successCount).toBe(0);
      expect(result.failureCount).toBe(1);
    });
  });
});
