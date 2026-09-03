// 회귀 가드 — 포털 증강 **요청 접수** 창구(API-231)를 계약대로 부르는가.
//
// ★ 백엔드가 같은 라운드에서 이 창구를 만들고 있어 **라이브로 확인하지 못했다.** 그래서 계약이
//   못박은 경로·본문 모양을 목으로 고정한다 — 어긋나면 여기가 먼저 빨개진다.
//
// ★★ **요청 본문에 이벤트 유형·세부 유형·증강 종류가 없다.** 요청자가 고르지 않고 서버가 중립
//   값을 고정으로 싣는다. 이 축은 최근에 뒤집혔고, **BE 는 모르는 필드를 400 이 아니라 조용히
//   무시**하므로 되살려 보내도 아무 신호가 오지 않는다 — 그래서 여기서 못박는다.
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import MockAdapter from 'axios-mock-adapter';

import { apiClient } from '@/lib/api/client';

import { requestUploadAugment } from '../api';

const OK = (data: unknown) => [201, { success: true, data, message: null, errorCode: null }];

const CONDITION = {
  time: 'NIGHT',
  season: 'WINTER',
  weather: 'SNOW',
  terrain: 'ROAD',
  severity: 'HIGH',
} as const;

describe('포털 증강 요청 접수 API', () => {
  let mock: MockAdapter;
  beforeEach(() => {
    mock = new MockAdapter(apiClient);
  });
  afterEach(() => mock.restore());

  it('★자산_경로_아래로_POST_한다', async () => {
    mock
      .onPost('/portal/uploads/501/augments')
      .reply(() => OK({ augSn: 9001, uldSn: 501, requestedAt: '2026-09-03T10:00:00' }));

    const accepted = await requestUploadAugment(501, { generationCondition: CONDITION });

    expect(mock.history.post).toHaveLength(1);
    expect(mock.history.post[0].url).toBe('/portal/uploads/501/augments');
    // 응답은 **접수 사실**이지 결과가 아니다.
    expect(accepted).toEqual({ augSn: 9001, uldSn: 501, requestedAt: '2026-09-03T10:00:00' });
  });

  it('★생성_조건은_generationCondition_래퍼에_다섯_항목_전부_담긴다', async () => {
    mock.onPost('/portal/uploads/501/augments').reply(() => OK({ augSn: 1, uldSn: 501, requestedAt: 'x' }));

    await requestUploadAugment(501, { generationCondition: CONDITION });

    const body = JSON.parse(mock.history.post[0].data as string) as Record<string, unknown>;
    // 래퍼 이름은 이 창구가 쓰는 이름이다 — 「일관성」을 이유로 다른 채널의 벤더 계약 이름으로
    // 바꾸지 말 것.
    expect(body.generationCondition).toEqual(CONDITION);
  });

  it('★자유_지시문은_최상위_문자열이다_객체를_실으면_400_이다', async () => {
    mock.onPost('/portal/uploads/501/augments').reply(() => OK({ augSn: 1, uldSn: 501, requestedAt: 'x' }));

    await requestUploadAugment(501, { generationCondition: CONDITION, prompt: '눈 내리는 밤으로' });

    const body = JSON.parse(mock.history.post[0].data as string) as Record<string, unknown>;
    expect(typeof body.prompt).toBe('string');
    expect(body.prompt).toBe('눈 내리는 밤으로');
  });

  it('자유_지시문이_없으면_키_자체를_싣지_않는다_빈_문자열을_지어_보내지_않는다', async () => {
    mock.onPost('/portal/uploads/501/augments').reply(() => OK({ augSn: 1, uldSn: 501, requestedAt: 'x' }));

    await requestUploadAugment(501, { generationCondition: CONDITION });

    const body = JSON.parse(mock.history.post[0].data as string) as Record<string, unknown>;
    expect('prompt' in body).toBe(false);
  });

  it('★요청_본문에_이벤트_유형과_증강_종류가_없다', async () => {
    mock.onPost('/portal/uploads/501/augments').reply(() => OK({ augSn: 1, uldSn: 501, requestedAt: 'x' }));

    await requestUploadAugment(501, { generationCondition: CONDITION, prompt: '지시문' });

    const body = JSON.parse(mock.history.post[0].data as string) as Record<string, unknown>;
    expect(Object.keys(body).sort()).toEqual(['generationCondition', 'prompt']);
    for (const forbidden of ['eventType', 'eventSubType', 'augTypes', 'types', 'augTypeCd', 'mtdt']) {
      expect(forbidden in body, forbidden).toBe(false);
      expect(forbidden in (body.generationCondition as Record<string, unknown>), forbidden).toBe(
        false,
      );
    }
  });

  it('거부는_상태코드_그대로_올라온다_화면이_구분을_스스로_만들지_않는다', async () => {
    mock.onPost('/portal/uploads/501/augments').reply(409, {
      success: false,
      data: null,
      message: '준비가 끝나지 않은 영상은 증강을 요청할 수 없습니다. 완료 후 다시 시도하세요.',
      errorCode: 'CONFLICT',
    });

    await expect(
      requestUploadAugment(501, { generationCondition: CONDITION }),
    ).rejects.toMatchObject({ status: 409, errorCode: 'CONFLICT' });
  });
});
