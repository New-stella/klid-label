// AI 추론 취소 — **서버까지 실제로 멈추게 하는 왕복**.
//
// ★ 왜 연결을 끊는 것만으로는 부족한가 (서버 담당이 실험으로 확인한 제약)
//   "클라이언트가 연결을 끊으면 서버도 끊는다" 는 이 스택에서 **성립하지 않는다** — 비동기 처리
//   중 유휴 구간의 클라이언트 종료는 통지되지 않고, 동기 처리 중에는 관측 수단 자체가 없다.
//   그래서 화면이 추론 요청에 **취소 식별자를 헤더로 실어 보내고**, 취소할 때 **같은 식별자로
//   취소 API 를 부른다**. 브라우저측 중단(AbortSignal)은 «우리가 더 안 기다린다» 를 뜻할 뿐이라
//   둘 다 필요하다 — 하나만 하면 서버가 계속 돌거나(헤더만), 화면이 계속 기다린다(취소만).
//
// ⚠ 식별자 형식은 서버가 받는 문자 집합(영문·숫자·밑줄·붙임표, 1~64자)을 지켜야 한다. 벗어나면
//   서버가 «없음» 과 같게 처리해 **취소가 조용히 무시**된다.

import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import MockAdapter from 'axios-mock-adapter';

import { apiClient } from '@/lib/api/client';

import { resetAiWaitBudgets } from '../aiBudget';
import {
  AI_REQUEST_ID_HEADER,
  cancelAiRequest,
  newAiRequestId,
  requestAutolabel,
  requestSam2Segment,
  requestSam2Track,
} from '../api';
import { requestAutoTrack } from '../api/autoTrack';

/** 서버 `AiCallCancellationRegistry.REQUEST_ID` 와 같은 형식. */
const SERVER_ACCEPTED = /^[A-Za-z0-9_-]{1,64}$/;

let mock: MockAdapter;

beforeEach(() => {
  mock = new MockAdapter(apiClient);
});

afterEach(() => {
  mock.restore();
  resetAiWaitBudgets();
});

describe('AI 취소 식별자', () => {
  it('서버가_받는_형식으로_만든다', () => {
    for (let i = 0; i < 50; i += 1) {
      expect(newAiRequestId()).toMatch(SERVER_ACCEPTED);
    }
  });

  it('실행마다_다른_식별자를_만든다', () => {
    // 겹치면 취소 요청이 **남의 추론**을 끊는다.
    const ids = new Set(Array.from({ length: 200 }, () => newAiRequestId()));
    expect(ids.size).toBe(200);
  });
});

describe('AI 추론 요청 — 취소 식별자 헤더', () => {
  it('네_경로_모두_받은_식별자를_헤더로_싣는다', async () => {
    // 헤더가 빠지면 서버가 그 요청을 취소 대상으로 등록하지 못해, 취소 버튼이 눌려도 추론이 계속 돈다.
    const requestId = 'req-abc123';
    const seen: Record<string, string | undefined> = {};
    const header = (h: unknown) => (h as Record<string, string>)?.[AI_REQUEST_ID_HEADER];

    mock.onPost('/frames/7/autolabel').reply((c) => {
      seen.autolabel = header(c.headers);
      return [200, { success: true, data: { srcSn: 7, savedCount: 0, labels: [] }, errorCode: null }];
    });
    mock.onPost('/frames/7/sam2-segment').reply((c) => {
      seen.segment = header(c.headers);
      return [200, { success: true, data: { polygon: [], score: 0 }, errorCode: null }];
    });
    mock.onPost('/frames/7/sam2-track').reply((c) => {
      seen.sam2Track = header(c.headers);
      return [200, { success: true, data: { tracked: [] }, errorCode: null }];
    });
    mock.onPost('/frames/7/yolo-track').reply((c) => {
      seen.autoTrack = header(c.headers);
      return [200, { success: true, data: { frames: [] }, errorCode: null }];
    });

    await requestAutolabel(7, [], undefined, undefined, undefined, requestId);
    await requestSam2Segment(7, { box: [1, 2, 3, 4] }, undefined, requestId);
    await requestSam2Track(
      7,
      {
        trackId: 't1',
        prevPolygon: [
          [0, 0],
          [1, 0],
          [1, 1],
        ],
        label: '사람',
        nextSrcSns: [8],
      },
      undefined,
      requestId,
    );
    await requestAutoTrack(7, [8], undefined, requestId);

    expect(seen.autolabel).toBe(requestId);
    expect(seen.segment).toBe(requestId);
    expect(seen.sam2Track).toBe(requestId);
    expect(seen.autoTrack).toBe(requestId);
  });

  it('식별자가_없으면_헤더를_붙이지_않는다', async () => {
    // 빈 값을 보내면 서버가 그것을 식별자로 여겨 «형식 위반» 으로 버린다 — 아예 안 붙이는 편이 맞다.
    let hasHeader = true;
    mock.onPost('/frames/7/autolabel').reply((c) => {
      hasHeader = AI_REQUEST_ID_HEADER in ((c.headers ?? {}) as Record<string, unknown>);
      return [200, { success: true, data: { srcSn: 7, savedCount: 0, labels: [] }, errorCode: null }];
    });

    await requestAutolabel(7);

    expect(hasHeader).toBe(false);
  });
});

describe('AI 취소 요청', () => {
  it('같은_식별자로_취소_API_를_부른다', async () => {
    let calledUrl: string | undefined;
    mock.onPost(/\/ai-requests\/.+\/cancel/).reply((c) => {
      calledUrl = c.url;
      return [200, { success: true, data: { cancelled: true }, errorCode: null }];
    });

    await cancelAiRequest('req-abc123');

    expect(calledUrl).toBe('/ai-requests/req-abc123/cancel');
  });

  it('식별자를_경로에_안전하게_싣는다', async () => {
    // 경로 조작(CWE-22) 방어 — 사용자 입력이 아니지만 경로 조립은 언제나 인코딩한다.
    let calledUrl: string | undefined;
    mock.onPost(/\/ai-requests\/.+\/cancel/).reply((c) => {
      calledUrl = c.url;
      return [200, { success: true, data: { cancelled: true }, errorCode: null }];
    });

    await cancelAiRequest('../../admin');

    expect(calledUrl).not.toContain('../');
  });

  it('취소_요청이_실패해도_예외를_올리지_않는다', async () => {
    // ★ 취소는 «더 안 기다린다» 는 화면의 결정이고, 그 결정은 서버 응답과 무관하게 이미 유효하다.
    //   여기서 예외가 올라가면 **취소했는데 오류 안내가 뜨는** 화면이 된다.
    mock.onPost(/\/ai-requests\/.+\/cancel/).reply(500);

    await expect(cancelAiRequest('req-abc123')).resolves.toBeUndefined();
  });

  it('식별자가_없으면_요청하지_않는다', async () => {
    let called = 0;
    mock.onPost(/\/ai-requests\/.+\/cancel/).reply(() => {
      called += 1;
      return [200, { success: true, data: { cancelled: true }, errorCode: null }];
    });

    await cancelAiRequest(undefined);

    expect(called).toBe(0);
  });
});
