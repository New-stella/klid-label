// 포털 업로드 **원본 파일** 다운로드 — 요청 제한시간 · 취소 신호 배선. @design SCREEN-034
//
// 이 파일이 고정하는 계약:
//  - 원본 파일은 **최대 5GB** 다(포털 자산 업로드 상한). 그런데 이 요청은 공용 기본 제한시간(30초)을
//    그대로 쓰고 있어 **대용량은 구조적으로 끊겼다** — 데이터마트 다운로드에서 이미 고친 것과 같은
//    결함이 이 경로에 그대로 남아 있었다. 전용 상한을 싣는다.
//  - 제한시간은 **유한**하다(0=무제한 아님). 연결이 조용히 멈췄을 때 버튼이 영구히 잠기는 것을
//    끝내 주는 최후 장치이며, 취소 버튼이 생겼다고 없어도 되는 것이 아니다(사용자가 화면을 보고
//    있지 않을 수 있다).
//  - 취소 신호가 **실제로 나가는 요청에 실린다** — 싣지 않으면 화면의 '취소' 는 전송을 멈추지 못한다.
//  - **라벨 내보내기(JSON)는 대상이 아니다** — 작아서 전용 상한도 취소도 두지 않는다(사양).

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import MockAdapter from 'axios-mock-adapter';

import { apiClient } from '@/lib/api/client';
import { DATAMART_DOWNLOAD_TIMEOUT_MS } from '@/features/portal/api';

import {
  UPLOAD_FILE_DOWNLOAD_TIMEOUT_MS,
  downloadUploadExport,
  downloadUploadFile,
} from '../api';

describe('포털 업로드 원본 다운로드 — 요청 제한시간', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
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

  it('원본_다운로드_요청에_공용_기본값이_아닌_전용_제한시간이_실린다', async () => {
    // given
    let seenTimeout: number | undefined;
    mock.onGet('/portal/uploads/1/file').reply((config) => {
      seenTimeout = config.timeout;
      return [200, 'bytes'];
    });

    // when
    await downloadUploadFile(1, 'photo.jpg');

    // then: 기본값(30초)이면 5GB 전송이 구조적으로 끊긴다
    expect(seenTimeout).toBe(UPLOAD_FILE_DOWNLOAD_TIMEOUT_MS);
    expect(seenTimeout).not.toBe(apiClient.defaults.timeout);
  });

  /*
   * ★ 값의 범위까지 단언한다 — «전용 값을 실었다» 만 보면 30초를 31초로 바꿔도 통과한다.
   *   기준은 형제 경로다: 데이터마트 묶음(약 1.1GB 를 상정한 상한)보다 **이 경로의 상한(5GB)이 더 크므로**
   *   제한시간이 그보다 짧으면 앞뒤가 맞지 않는다.
   */
  it('제한시간은_유한하고_5GB_전송에_쓸_만큼_길다', () => {
    expect(UPLOAD_FILE_DOWNLOAD_TIMEOUT_MS).toBeGreaterThan(0);
    expect(Number.isFinite(UPLOAD_FILE_DOWNLOAD_TIMEOUT_MS)).toBe(true);
    // 형제 경로(데이터마트)보다 상한이 큰 파일을 받는다 — 더 짧을 수 없다
    expect(UPLOAD_FILE_DOWNLOAD_TIMEOUT_MS).toBeGreaterThanOrEqual(DATAMART_DOWNLOAD_TIMEOUT_MS);
  });

  /*
   * ★ 라벨 내보내기(JSON)는 대상이 아니다. 작아서 전용 상한이 필요 없고, 여기에도 상한을 늘려 두면
   *   «작아서 취소·전용 상한을 두지 않는다» 는 사양 판단이 코드에서 지워진다.
   */
  it('라벨_내보내기_JSON_은_전용_제한시간_대상이_아니다', async () => {
    // given
    let seenTimeout: number | undefined;
    mock.onGet('/portal/uploads/1/export').reply((config) => {
      seenTimeout = config.timeout;
      return [200, '{"ok":true}'];
    });

    // when
    await downloadUploadExport(1);

    // then: 공용 기본값을 그대로 쓴다(요청에 별도 timeout 을 싣지 않는다).
    // ⚠ «전용 값과 다르다» 로 쓰지 않는다 — 전용 값이 우연히 기본값과 같아지는 순간 그 단언은
    //   조용히 참이 되어 아무것도 지키지 않는다(실제로 뮤테이션 중 그 상태가 나왔다).
    expect(seenTimeout).toBe(apiClient.defaults.timeout);
  });
});

describe('포털 업로드 원본 다운로드 — 취소 신호 배선', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
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
    // given
    const controller = new AbortController();
    let seenSignal: unknown;
    mock.onGet('/portal/uploads/1/file').reply((config) => {
      seenSignal = config.signal;
      return [200, 'bytes'];
    });

    // when
    await downloadUploadFile(1, 'photo.jpg', controller.signal);

    // then
    expect(seenSignal).toBe(controller.signal);
  });

  it('취소_신호를_넘기지_않아도_그대로_동작한다', async () => {
    // given
    let seenSignal: unknown = 'not-touched';
    mock.onGet('/portal/uploads/1/file').reply((config) => {
      seenSignal = config.signal;
      return [200, 'bytes'];
    });

    // when: 기존 호출부(인자 2개)
    await downloadUploadFile(1, 'photo.jpg');

    // then
    expect(seenSignal).toBeUndefined();
    expect(mock.history.get).toHaveLength(1);
  });
});
