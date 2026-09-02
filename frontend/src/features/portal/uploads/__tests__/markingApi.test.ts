/**
 * 포털 업로드 영상 마킹 창구 계약 가드.
 * [@design API-239] [@design API-240] [@design API-241]
 *
 * 이 파일이 고정하는 것은 **확정된 창구 계약 그 자체**다 — 경로·메서드·본문 모양과 응답 필드.
 * 화면이 계약을 스스로 지어내면 서버와 갈리는데, 그 어긋남은 컴파일에도 화면 시험에도 걸리지
 * 않고 실제 호출에서만 드러난다.
 */
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import MockAdapter from 'axios-mock-adapter';

import { apiClient } from '@/lib/api/client';

import { getUploadStreamUrl, listUploadMarkings, saveUploadMarking } from '../markingApi';

function ok(data: unknown) {
  return { success: true, data, message: null, errorCode: null };
}

describe('포털 업로드 마킹 창구', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
  });
  afterEach(() => mock.restore());

  it('서명_주소는_GET_stream_url_이고_주소와_만료를_돌려준다', async () => {
    mock
      .onGet('/portal/uploads/501/stream-url')
      .reply(200, ok({ url: '/api/v1/portal/uploads/501/stream?exp=1&sig=a', expiresAt: 1788000000, ttlSeconds: 120 }));

    const res = await getUploadStreamUrl(501);

    expect(res.url).toContain('/portal/uploads/501/stream');
    expect(res.expiresAt).toBe(1788000000);
    expect(res.ttlSeconds).toBe(120);
    expect(mock.history.get).toHaveLength(1);
  });

  it('마킹_조회는_GET_markings_이고_저장된_것이_없으면_빈_목록이다', async () => {
    mock.onGet('/portal/uploads/501/markings').reply(200, ok({ uldSn: 501, markings: [] }));

    const res = await listUploadMarkings(501);

    // 「없다」는 오류가 아니라 참인 답이다 — 화면이 알아야 하는 사실이다.
    expect(res.markings).toEqual([]);
  });

  it('★마킹_저장은_POST_markings_이고_본문을_손대지_않고_그대로_보낸다', async () => {
    let sent: unknown = null;
    mock.onPost('/portal/uploads/501/markings').reply((config) => {
      sent = JSON.parse(config.data as string);
      return [
        201,
        ok({
          markingSn: 7001,
          uldSn: 501,
          mode: 'AUTO',
          interval: 300,
          marks: [{ frameIndex: 0, timestamp: '00:00' }],
          markCount: 1,
          requestedMarkCount: 1,
          truncated: false,
          uldSttsCd: 'PROCESSING',
          regDt: '2026-09-02T10:00:00',
        }),
      ];
    });

    const res = await saveUploadMarking(501, { mode: 'AUTO', interval: 300 });

    expect(sent).toEqual({ mode: 'AUTO', interval: 300 });
    // 저장이 확정적으로 말하는 것 — 저장했고 추출이 시작되도록 상태를 넘겼다(준비 완료가 아니다).
    expect(res.uldSttsCd).toBe('PROCESSING');
    expect(res.markingSn).toBe(7001);
  });

  it('★저장_응답의_절단_사실은_그대로_읽힌다', async () => {
    mock.onPost('/portal/uploads/501/markings').reply(
      201,
      ok({
        markingSn: 7002,
        uldSn: 501,
        mode: 'MANUAL',
        interval: null,
        marks: [],
        markCount: 2000,
        requestedMarkCount: 2500,
        truncated: true,
        uldSttsCd: 'PROCESSING',
        regDt: '2026-09-02T10:00:00',
      }),
    );

    const res = await saveUploadMarking(501, { mode: 'MANUAL', marks: [] });

    // 장수만 읽으면 그 수가 보낸 수인지 잘린 수인지 구분할 수 없다 — 세 값이 함께 있어야 한다.
    expect(res.truncated).toBe(true);
    expect(res.requestedMarkCount).toBe(2500);
    expect(res.markCount).toBe(2000);
  });
});
