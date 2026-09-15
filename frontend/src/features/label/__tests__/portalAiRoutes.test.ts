// 포털 채널 AI 보조 창구 경로 — 호출 함수가 채널 인자에 따라 **실제로 어느 주소로 나가는가**.
//
// ★ 본문이 아니라 **주소**를 단언한다. 요청 모의는 등록되지 않은 주소로 나간 요청도 이력에 남기므로,
//   본문만 보는 시험은 «포털 창구 대신 내부 창구로 나갔다» 는 변이를 통과시킨다(본문은 같다).
// ★ 포털 인자가 없으면 내부 주소 그대로다 — 관제향 경로 무회귀를 같은 파일에서 짝으로 고정한다.
//
// @design API-255, API-257, API-254, API-256, API-258, SCREEN-029

import MockAdapter from 'axios-mock-adapter';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';

import { aiCancelPath, aiDefaultsPath, aiFramePath } from '@/lib/api/aiRoutes';
import { apiClient } from '@/lib/api/client';
import { SYSCONFIG_KEYS } from '@/lib/queryKeys';
import { getAiDefaults } from '@/features/sysconfig/api';

import { resetAiWaitBudgets } from '../aiBudget';
import { cancelAiRequest, requestAutolabel, requestSam2Segment } from '../api';
import { requestAutoTrack, requestAutoTrackAll } from '../api/autoTrack';

let mock: MockAdapter;

const ok = <T,>(data: T) => ({ success: true, data, message: null, errorCode: null });

beforeEach(() => {
  mock = new MockAdapter(apiClient);
});

afterEach(() => {
  mock.restore();
  resetAiWaitBudgets();
});

const postUrls = () => mock.history.post.map((r) => r.url);
const getUrls = () => mock.history.get.map((r) => r.url);

describe('aiRoutes — 경로 조립 단일 지점', () => {
  it('채널이_다르면_주소가_다르다_팩토리가_인자를_무시하지_않는다', () => {
    // 시험과 구현이 같은 팩토리를 쓰면 팩토리가 채널 인자를 버리는 변이를 잡지 못한다 → 리터럴로 고정.
    expect(aiFramePath(true, 7, 'autolabel')).toBe('/portal/frames/7/autolabel');
    expect(aiFramePath(false, 7, 'autolabel')).toBe('/frames/7/autolabel');
    expect(aiFramePath(true, 7, 'sam2-segment')).toBe('/portal/frames/7/sam2-segment');
    expect(aiFramePath(true, 7, 'yolo-track')).toBe('/portal/frames/7/yolo-track');
    expect(aiDefaultsPath(true)).toBe('/portal/ai-defaults');
    expect(aiDefaultsPath(false)).toBe('/ai-defaults');
    expect(aiCancelPath(true, 'r-1')).toBe('/portal/ai-requests/r-1/cancel');
    expect(aiCancelPath(false, 'r-1')).toBe('/ai-requests/r-1/cancel');
  });

  it('취소_식별자는_경로에_넣기_전에_인코딩한다', () => {
    expect(aiCancelPath(true, 'a/../b')).toBe('/portal/ai-requests/a%2F..%2Fb/cancel');
  });
});

describe('포털 채널 — AI 실행 창구 주소', () => {
  it('AI_탐지는_포털이면_포털_창구로_아니면_내부_창구로_나간다', async () => {
    mock.onPost().reply(200, ok({ srcSn: 5, savedCount: 0, labels: [] }));
    await requestAutolabel(5, ['person'], 'BBOX', undefined, undefined, undefined, true);
    await requestAutolabel(5, ['person'], 'BBOX');
    expect(postUrls()).toEqual(['/portal/frames/5/autolabel', '/frames/5/autolabel']);
  });

  it('AI_탐지_본문이_비어도_포털_창구로_나간다', async () => {
    // 본문 유무로 호출 분기가 둘이다 — 한 분기만 경로를 갈라 두면 그 형태에서만 내부 창구로 샌다.
    mock.onPost().reply(200, ok({ srcSn: 5, savedCount: 0, labels: [] }));
    await requestAutolabel(5, undefined, undefined, undefined, undefined, undefined, true);
    expect(postUrls()).toEqual(['/portal/frames/5/autolabel']);
  });

  it('AI_분할은_포털이면_포털_창구로_나간다', async () => {
    mock.onPost().reply(200, ok({ polygon: [], score: 0 }));
    await requestSam2Segment(9, { points: [[1, 2]] }, undefined, undefined, true);
    await requestSam2Segment(9, { points: [[1, 2]] });
    expect(postUrls()).toEqual(['/portal/frames/9/sam2-segment', '/frames/9/sam2-segment']);
  });

  it('AI_자동_추적은_포털이면_포털_창구로_나간다', async () => {
    mock.onPost().reply(200, ok({ frames: [], truncated: false, resume: null }));
    await requestAutoTrack(11, [12], undefined, undefined, true);
    await requestAutoTrack(11, [12]);
    expect(postUrls()).toEqual(['/portal/frames/11/yolo-track', '/frames/11/yolo-track']);
  });

  it('AI_자동_추적_이어보내기의_모든_조각이_포털_창구로_나간다', async () => {
    // 첫 조각만 포털로 보내면 이어 보내기가 내부 창구에서 403 으로 끊긴다.
    mock
      .onPost('/portal/frames/20/yolo-track')
      .replyOnce(
        200,
        ok({
          frames: [{ srcSn: 20, frameIndex: 0, detections: [] }],
          truncated: true,
          resume: { srcSn: 21, nextSrcSns: [22] },
        }),
      )
      .onPost('/portal/frames/21/yolo-track')
      .replyOnce(200, ok({ frames: [], truncated: false, resume: null }));
    await requestAutoTrackAll(20, [21, 22], { portal: true });
    expect(postUrls()).toEqual(['/portal/frames/20/yolo-track', '/portal/frames/21/yolo-track']);
  });

  it('AI_취소는_포털이면_포털_취소_창구로_나간다', async () => {
    mock.onPost().reply(200, ok({ cancelled: true }));
    await cancelAiRequest('req-1', true);
    await cancelAiRequest('req-2');
    expect(postUrls()).toEqual(['/portal/ai-requests/req-1/cancel', '/ai-requests/req-2/cancel']);
  });

  it('AI_기본값_조회는_포털이면_포털_창구로_나간다', async () => {
    mock.onGet().reply(200, ok({ confThreshold: 30 }));
    await getAiDefaults(true);
    await getAiDefaults();
    expect(getUrls()).toEqual(['/portal/ai-defaults', '/ai-defaults']);
  });

  it('AI_기본값_캐시_키는_채널로_갈리고_내부_키는_종전과_같다', () => {
    expect(SYSCONFIG_KEYS.aiDefaults()).toEqual(['sysconfig', 'ai-defaults']);
    expect(SYSCONFIG_KEYS.aiDefaults(true)).toEqual(['sysconfig', 'ai-defaults', 'portal']);
  });
});
