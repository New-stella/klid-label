// 데이터마트 작업 데이터 다운로드 — 사용자 취소 배선. @design SCREEN-028, API-203
//
// 이 파일이 고정하는 계약:
//  - 호출자가 준 중단 신호(AbortSignal)가 **실제로 나가는 요청에 실린다.** 신호를 받아만 두고
//    요청에 싣지 않으면 화면의 '취소' 는 버튼만 있고 전송은 계속되는 거짓 조작이 된다.
//  - 신호는 **선택적**이다 — 넘기지 않는 기존 호출은 그대로 동작한다(하위호환).

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import MockAdapter from 'axios-mock-adapter';

import { apiClient } from '@/lib/api/client';

import { DATAMART_DOWNLOAD_TIMEOUT_MS, downloadDatamartVideoData } from '../api';

describe('데이터마트 다운로드 — 취소 신호 배선', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
    // jsdom 은 두 API 를 갖고 있지 않아 정의해 준다(형제 테스트와 같은 방식).
    Object.defineProperty(URL, 'createObjectURL', {
      writable: true,
      value: vi.fn(() => 'blob:stub'),
    });
    Object.defineProperty(URL, 'revokeObjectURL', { writable: true, value: vi.fn() });
  });

  afterEach(() => {
    mock.restore();
    vi.restoreAllMocks();
  });

  it('취소_신호가_실제_요청에_실린다', async () => {
    // given: 화면이 쥐고 있는 중단 컨트롤러
    const controller = new AbortController();
    let seenSignal: unknown;
    mock.onGet('/portal/datamart/videos/7/download').reply((config) => {
      seenSignal = config.signal;
      return [200, 'zip-bytes'];
    });

    // when
    await downloadDatamartVideoData(7, controller.signal);

    // then: 신호가 요청 설정에 그대로 실린다 — 실리지 않으면 '취소' 는 전송을 멈추지 못한다
    expect(seenSignal).toBe(controller.signal);
    // then: 취소를 붙였다고 제한시간이 사라지지는 않는다(연결이 조용히 멈춘 경우의 최후 장치)
    expect(mock.history.get[0]?.timeout).toBe(DATAMART_DOWNLOAD_TIMEOUT_MS);
  });

  it('취소_신호를_넘기지_않아도_그대로_동작한다', async () => {
    // given
    let seenSignal: unknown = 'not-touched';
    mock.onGet('/portal/datamart/videos/7/download').reply((config) => {
      seenSignal = config.signal;
      return [200, 'zip-bytes'];
    });

    // when: 기존 호출부(인자 1개)
    await downloadDatamartVideoData(7);

    // then: 신호 없이도 요청은 정상적으로 나간다
    expect(seenSignal).toBeUndefined();
    expect(mock.history.get).toHaveLength(1);
  });
});
