// 데이터마트 작업 데이터 다운로드 — 요청 제한시간 · 응답이 오지 않는 실패의 안내. @design SCREEN-028, API-203
//
// 이 파일이 고정하는 계약:
//  - 이 요청은 **공용 기본 제한시간(30초)을 쓰지 않는다.** 응답이 GB 급이라 30초로는 구조적으로
//    끊기고, 끊긴 시점엔 서버가 이미 전량을 흘려보낸 뒤라 재시도가 서버 부하만 늘린다.
//  - 제한시간은 **유한**하다(0=무제한 아님). 취소 수단이 없는 화면에서 무제한은 '내려받는 중…' 에
//    영구히 갇히는 상태를 만든다.
//  - 응답 자체가 오지 않은 실패(중단·네트워크·제한시간 초과)는 서버가 준 네 가지(429/412/410/403)와
//    **다른 문구**로 안내하며, 그 문구는 «잠시 후 다시 시도» 를 권하지 않는다.

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import MockAdapter from 'axios-mock-adapter';

import { apiClient } from '@/lib/api/client';
import { ApiError } from '@/lib/api/errors';

import { DATAMART_DOWNLOAD_TIMEOUT_MS, downloadDatamartVideoData } from '../api';
import {
  DOWNLOAD_ERROR_DEIDENT,
  DOWNLOAD_ERROR_FORBIDDEN,
  DOWNLOAD_ERROR_GENERIC,
  DOWNLOAD_ERROR_INTERRUPTED,
  DOWNLOAD_ERROR_NO_LABEL,
  DOWNLOAD_ERROR_RATE_LIMIT,
  datamartDownloadErrorMessage,
} from '../downloadError';

describe('데이터마트 다운로드 — 요청 제한시간', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
    // 브라우저 다운로드 트리거는 이 테스트의 관심사가 아니다 — jsdom 은 두 API 를 아예 갖고 있지
    // 않아 spyOn 이 성립하지 않으므로 정의해 준다(다른 테스트도 같은 방식을 쓴다).
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

  it('요청에_공용_기본값이_아닌_전용_제한시간이_실린다', async () => {
    // given: 실제로 나가는 요청 설정을 들여다본다
    let seenTimeout: number | undefined;
    mock.onGet('/portal/datamart/videos/7/download').reply((config) => {
      seenTimeout = config.timeout;
      return [200, 'zip-bytes'];
    });

    // when
    await downloadDatamartVideoData(7);

    // then: 상수가 그대로 실린다 — 공용 기본값(30초)이면 GB 급 전송이 구조적으로 끊긴다
    expect(seenTimeout).toBe(DATAMART_DOWNLOAD_TIMEOUT_MS);
    expect(seenTimeout).not.toBe(apiClient.defaults.timeout);
  });

  /*
   * ★ 값의 범위까지 단언한다 — «전용 값을 실었다» 만 보면 30초를 31초로 바꿔도 통과한다.
   *   하한: GB 급 전송에 최소 몇 분은 필요하다. 상한 0(무제한): 취소 수단이 없어 금지.
   */
  it('제한시간은_유한하고_대용량_전송에_쓸_만큼_길다', () => {
    // then: 0(무제한)이 아니다 — 멈춘 요청을 끝내 주는 장치가 이것뿐이다
    expect(DATAMART_DOWNLOAD_TIMEOUT_MS).toBeGreaterThan(0);
    expect(Number.isFinite(DATAMART_DOWNLOAD_TIMEOUT_MS)).toBe(true);
    // then: 분 단위 이상 — 공용 기본값(30초)의 잔재가 아니다
    expect(DATAMART_DOWNLOAD_TIMEOUT_MS).toBeGreaterThanOrEqual(10 * 60 * 1000);
  });

  it('제한시간을_넘기면_상태코드_없는_실패로_올라와_전송_중단으로_안내된다', async () => {
    // given: 서버가 응답을 끝내지 못한다(axios timeout — 응답 없음)
    mock.onGet('/portal/datamart/videos/7/download').timeout();

    // when
    const error = await downloadDatamartVideoData(7).then(
      () => null,
      (e: unknown) => e,
    );

    // then: 응답이 없으므로 상태코드가 없다(0)
    expect(error).toBeInstanceOf(ApiError);
    expect((error as ApiError).status).toBe(0);
    // then: 서버가 거부한 네 가지와 구분해 안내한다
    expect(datamartDownloadErrorMessage(error)).toBe(DOWNLOAD_ERROR_INTERRUPTED);
  });

  it('네트워크_단절도_같은_경로로_전송_중단으로_안내된다', async () => {
    // given
    mock.onGet('/portal/datamart/videos/7/download').networkError();

    // when
    const error = await downloadDatamartVideoData(7).then(
      () => null,
      (e: unknown) => e,
    );

    // then
    expect(datamartDownloadErrorMessage(error)).toBe(DOWNLOAD_ERROR_INTERRUPTED);
  });
});

describe('데이터마트 다운로드 — 실패 안내 분기', () => {
  it('전송_중단_문구는_다른_모든_문구와_다르다_일반_실패와도_같지_않다', () => {
    // given / when: 일반 실패까지 포함해 센다 — 일반 실패와 같으면 «따로 안내한다» 가 이름뿐이다
    const messages = [
      DOWNLOAD_ERROR_RATE_LIMIT,
      DOWNLOAD_ERROR_DEIDENT,
      DOWNLOAD_ERROR_NO_LABEL,
      DOWNLOAD_ERROR_FORBIDDEN,
      DOWNLOAD_ERROR_INTERRUPTED,
      DOWNLOAD_ERROR_GENERIC,
    ];

    // then: 하나로 합치면 사용자가 해야 할 다음 행동이 뭉개진다
    expect(new Set(messages).size).toBe(6);
  });

  /*
   * ★ «다르기만 하면 된다» 가 아니다 — 전송이 끊긴 상황에 «잠시 후 다시 시도» 를 권하면
   *   기다려도 달라지지 않는 일을 반복시키고, 그 재시도마다 서버는 GB 급 전송을 다시 하고 버린다.
   */
  it('전송_중단_안내는_잠시_후_재시도를_권하지_않는다', () => {
    expect(DOWNLOAD_ERROR_INTERRUPTED).not.toContain('잠시 후');
    // 대조군 — 서버 사유(요청량 초과·일반 실패)에서는 기다림이 실제로 답이라 그 문구가 맞다
    expect(DOWNLOAD_ERROR_RATE_LIMIT).toContain('잠시 후');
    expect(DOWNLOAD_ERROR_GENERIC).toContain('잠시 후');
  });

  it('서버_응답에서_비롯되지_않은_실패는_전송_중단으로_오인되지_않는다', () => {
    // given: 브라우저 다운로드 트리거 실패 같은 로컬 오류
    const local = new TypeError('createObjectURL is not a function');

    // then: 상태코드가 없다고 해서 «전송이 끊겼다» 로 안내하면 거짓말이 된다
    expect(datamartDownloadErrorMessage(local)).toBe(DOWNLOAD_ERROR_GENERIC);
  });
});
