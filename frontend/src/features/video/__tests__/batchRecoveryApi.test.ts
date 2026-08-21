// 배치 실패 관리 API 계약 테스트. [@design API-043] [@design API-167] [@design API-198]
// [@design API-199] [@design API-200] [@design API-201] [@design API-212] [@design API-213]
// [@design API-214]

import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import MockAdapter from 'axios-mock-adapter';

import { apiClient } from '@/lib/api/client';

import {
  clearBatchStageSkipBulk,
  getVideo,
  rerunBatchStage,
  rerunBatchStageBulk,
  retryBatch,
  retryBatchBulk,
  skipBatchStage,
  skipBatchStageBulk,
  unskipBatchStage,
} from '../api';
import type { StageBundle } from '../types';
import { BULK_STAGE_BUNDLE, isBulkStageBundle, SKIP_REASON_MAX } from '../types';

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

  // 건너뛰기를 해제한 작업 묶음 재수행 — 전체 재기동과 **다른 요청**이다. [@design API-201]
  describe('건너뛰기를 해제한 작업 묶음 재수행', () => {
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

  // 시계열 묶음 일괄 건너뛰기 / 건너뛰기 해제 / 재수행.
  // [@design API-212] [@design API-213] [@design API-214]
  describe('작업 묶음 일괄 조작', () => {
    function bulkOk(successCount: number, failureCount: number, results: unknown[]) {
      return ok({ successCount, failureCount, results });
    }

    it('일괄_건너뛰기는_사유와_중복제거한_목록을_담아_묶음_경로로_POST_한다', async () => {
      mock
        .onPost('/videos/batch/stages/VLM/skip')
        .reply(200, bulkOk(2, 0, [
          { rawSn: 12, success: true, reason: null },
          { rawSn: 43, success: true, reason: null },
        ]));

      const result = await skipBatchStageBulk([12, 43, 12], '외부 시계열 분석 벤더 연동 전');

      expect(mock.history.post[0].url).toBe('/videos/batch/stages/VLM/skip');
      expect(JSON.parse(mock.history.post[0].data)).toEqual({
        rawSns: [12, 43],
        reason: '외부 시계열 분석 벤더 연동 전',
      });
      expect(result.successCount).toBe(2);
    });

    // 건너뜀 표식을 푸는 조작의 이름은 「건너뛰기 해제」다. 서버는 표식을 지우는 대신 해제 표식을
    // 덧붙이므로 누가 언제 풀었는지가 남는다 — 해제만으로 시계열이 채워지지는 않는다.
    it('일괄_건너뛰기_해제는_같은_하위리소스로_DELETE_하고_목록을_본문에_담는다', async () => {
      mock
        .onDelete('/videos/batch/stages/VLM/skip')
        .reply(200, bulkOk(2, 0, [
          { rawSn: 12, success: true, reason: null },
          { rawSn: 43, success: true, reason: null },
        ]));

      const result = await clearBatchStageSkipBulk([12, 43, 43]);

      expect(mock.history.delete[0].url).toBe('/videos/batch/stages/VLM/skip');
      expect(JSON.parse(mock.history.delete[0].data)).toEqual({ rawSns: [12, 43] });
      expect(result.results).toHaveLength(2);
    });

    it('일괄_재수행은_rerun_경로로_POST_하고_사유를_보내지_않는다', async () => {
      mock
        .onPost('/videos/batch/stages/VLM/rerun')
        .reply(200, bulkOk(1, 0, [{ rawSn: 12, success: true, reason: null }]));

      const result = await rerunBatchStageBulk([12, 12]);

      expect(mock.history.post[0].url).toBe('/videos/batch/stages/VLM/rerun');
      expect(JSON.parse(mock.history.post[0].data)).toEqual({ rawSns: [12] });
      expect(result.successCount).toBe(1);
    });

    it('일괄_건너뛰기와_전체_재시작_경로가_섞이지_않는다', async () => {
      // 세그먼트 수가 달라 단건(7)과도, 축이 달라 전체 재시작과도 겹치지 않는다.
      mock
        .onPost('/videos/batch/stages/VLM/rerun')
        .reply(200, bulkOk(1, 0, [{ rawSn: 12, success: true, reason: null }]));

      await rerunBatchStageBulk([12]);

      const urls = mock.history.post.map((h) => h.url);
      expect(urls).not.toContain('/videos/batch/retry');
      expect(urls).not.toContain('/videos/12/batch/stages/VLM/rerun');
    });

    it('★일괄_축은_시계열_묶음만_받는다_그_외는_요청_전에_막힌다', async () => {
      // 오토라벨은 산출물이 라벨이라 대량으로 건너뛸 수 있게 열지 않았다(서버도 400).
      // 경로 세그먼트는 이 상수 하나뿐이라 사용자 입력이 URL 로 흘러갈 자리가 없다(CWE-22).
      expect(BULK_STAGE_BUNDLE).toBe('VLM');
      expect(isBulkStageBundle('VLM')).toBe(true);
      expect(isBulkStageBundle('AUTOLABEL')).toBe(false);
      expect(isBulkStageBundle('../../etc')).toBe(false);

      mock
        .onPost('/videos/batch/stages/VLM/rerun')
        .reply(200, bulkOk(0, 0, []));
      await rerunBatchStageBulk([12]);

      expect(mock.history.post.map((h) => h.url)).toEqual([
        `/videos/batch/stages/${BULK_STAGE_BUNDLE}/rerun`,
      ]);
    });

    it('한_건도_처리되지_못해도_200이라_결과목록으로_판정한다', async () => {
      // 부분 성공이다 — 상태코드가 아니라 results 로 판정해야 무엇이 안 됐는지 사용자가 안다.
      mock
        .onPost('/videos/batch/stages/VLM/skip')
        .reply(200, bulkOk(0, 2, [
          { rawSn: 12, success: false, reason: '영상을 찾을 수 없습니다.' },
          { rawSn: 43, success: false, reason: '파생영상은 대상이 아닙니다.' },
        ]));

      const result = await skipBatchStageBulk([12, 43], '벤더 장애');

      expect(result.successCount).toBe(0);
      expect(result.failureCount).toBe(2);
      expect(result.results[0].reason).toBe('영상을 찾을 수 없습니다.');
    });

    it('사유_상한은_서버_제약과_같은_값이다', () => {
      // BE ManualStageSkip.REASON_MAX_LENGTH 와 갈리면 화면 안내와 서버 거부가 어긋난다.
      expect(SKIP_REASON_MAX).toBe(500);
    });
  });
});
