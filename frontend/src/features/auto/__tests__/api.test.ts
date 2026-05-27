import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import MockAdapter from 'axios-mock-adapter';

import { apiClient } from '@/lib/api/client';

import { getAutoLabelSummary, getMeta, updateMeta } from '../api';

describe('auto api', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
  });

  afterEach(() => {
    mock.restore();
  });

  it('오토라벨_요약_조회_엔드포인트_video_id_경로', async () => {
    mock.onGet('/videos/42/auto-summary').reply(200, {
      success: true,
      data: {
        videoId: 42,
        totalFrames: 900,
        totalLabels: 1500,
        averageConfidence: 0.82,
        vlmVerifiedCount: 1200,
        vlmRejectedCount: 80,
        buckets: [
          { bucket: 'high', count: 1000, ratio: 0.66 },
          { bucket: 'mid', count: 400, ratio: 0.27 },
          { bucket: 'low', count: 100, ratio: 0.07 },
        ],
        classDistribution: [{ classId: 1, className: 'person', count: 800 }],
        lowConfidenceFrames: [
          { srcSn: 7, frameNo: 5, confidence: 0.55, thumbnailUrl: '/t/5.jpg' },
        ],
      },
      message: null,
      errorCode: null,
    });

    const r = await getAutoLabelSummary(42);
    expect(r.videoId).toBe(42);
    expect(r.buckets).toHaveLength(3);
    expect(r.lowConfidenceFrames?.[0].confidence).toBe(0.55);
  });

  it('메타_조회시_GET_frames_id_meta_호출', async () => {
    mock.onGet('/frames/100/meta').reply(200, {
      success: true,
      data: {
        srcSn: 100,
        frameNo: 5,
        imageUrl: '/img/100.jpg',
        imageWidth: 1920,
        imageHeight: 1080,
        envMeta: { weather: 'CLEAR', timeOfDay: 'DAY', illumination: 'HIGH' },
        eventMeta: { eventTypeCd: 'FIRE', intensity: 'HIGH', description: '소화전 인근 화재' },
        stateChanges: [],
      },
      message: null,
      errorCode: null,
    });

    const r = await getMeta(100);
    expect(r.srcSn).toBe(100);
    expect(r.envMeta.weather).toBe('CLEAR');
  });

  it('메타_저장시_PUT_frames_id_meta_호출', async () => {
    mock.onPut('/frames/100/meta').reply((config) => {
      const body = JSON.parse(config.data);
      expect(body.envMeta.weather).toBe('RAIN');
      expect(body.eventMeta.intensity).toBe('LOW');
      return [
        200,
        {
          success: true,
          data: {
            srcSn: 100,
            frameNo: 5,
            imageUrl: '/img/100.jpg',
            imageWidth: 1920,
            imageHeight: 1080,
                envMeta: { weather: 'RAIN', timeOfDay: 'DAY', illumination: 'MID' },
            eventMeta: { eventTypeCd: null, intensity: 'LOW', description: null },
            stateChanges: [],
          },
          message: null,
          errorCode: null,
        },
      ];
    });

    const r = await updateMeta(100, {
      envMeta: { weather: 'RAIN', timeOfDay: 'DAY', illumination: 'MID' },
      eventMeta: { eventTypeCd: null, intensity: 'LOW', description: null },
    });
    expect(r.envMeta.weather).toBe('RAIN');
  });
});
