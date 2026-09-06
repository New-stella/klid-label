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
//  - **라벨 내보내기(JSON)는 전용 제한시간의 대상이 아니다** — 작아서 공용 기본값 안에 끝난다(사양).
//    ⚠ 구 서술 폐기 — *"전용 상한도 **취소도** 두지 않는다"*. 취소 축은 2026-09-06 에 열렸다:
//      「내 작업」 목록이 한 목록에서 두 출처의 내려받기를 함께 다루는데, 데이터마트 축에만 취소가
//      있으면 **행에 따라 취소가 되기도 안 되기도 한다**(사양 SCREEN-028 은 행 구분 없이 요구한다).
//      **제한시간 축과 취소 축은 별개다** — 아래 두 describe 가 각각을 고정한다.

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

/*
 * ★ 「함수 → HTTP」 홉 가드 — 화면 시험은 이 축을 **원리적으로 못 본다.**
 *
 * 화면(`PortalHomePage`)의 취소 시험은 `downloadUploadExport` 를 **모의**하므로 「화면 → 함수」 홉만
 * 덮는다. 신호를 인자로 받아 놓고 **요청 설정에 싣지 않는** 변이는 그 시험을 전부 통과한다
 * (실측: 그 줄을 지워도 전건 4,791 초록). 그 실패 모드가 정확히 이 함수의 주석이 경고하는 것 —
 * "신호를 받아만 두고 요청에 싣지 않으면 화면의 '취소' 는 버튼만 있고 전송은 계속되는 거짓 조작" —
 * 이라, **두 홉을 각각 고정해야** 그 경고가 검사 안으로 들어온다.
 *
 * 골격은 형제 축(원본 파일 · 데이터마트 묶음)의 배선 시험과 같다 — 새로 발명하지 않는다.
 */
describe('포털 업로드 내보내기(JSON) — 취소 신호 배선', () => {
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

  it('★취소_신호가_실제_요청에_실린다', async () => {
    // given: 화면이 쥐고 있는 중단 컨트롤러
    const controller = new AbortController();
    let seenSignal: unknown;
    mock.onGet('/portal/uploads/1/export').reply((config) => {
      seenSignal = config.signal;
      return [200, '{"ok":true}'];
    });

    // when: 세 번째 인자로 신호를 넘긴다(두 번째는 파일명 폴백)
    await downloadUploadExport(1, undefined, controller.signal);

    // then: 신호가 요청 설정에 **그대로** 실린다 — 실리지 않으면 '취소' 는 전송을 멈추지 못한다
    expect(seenSignal).toBe(controller.signal);
  });

  it('★중단시키면_그_요청이_실제로_취소된다', async () => {
    // given: 응답을 붙잡아 둔 채 신호를 끊는다 — 「같은 인스턴스인가」보다 한 겹 더 들어간
    //        관측이다(설정에 실렸어도 axios 가 그것을 쓰지 않으면 전송은 계속된다).
    const controller = new AbortController();
    mock.onGet('/portal/uploads/1/export').reply(() => new Promise(() => {}));

    // when
    const pending = downloadUploadExport(1, undefined, controller.signal);
    controller.abort();

    /*
     * ★ 결과를 **세 갈래로 관측**한다 — 그냥 `rejects` 로 기다리면 배선이 끊겼을 때 «타임아웃» 으로
     *   죽어 실패 메시지가 원인을 가린다(RED 는 나지만 무엇이 틀렸는지 읽히지 않는다).
     *
     * ⚠ 관측 창을 넉넉히 잡는다(1초). 정상 경로에서는 중단 거부가 **즉시** 도착해 이 타이머를
     *   기다리지 않으므로 초록일 때 비용이 0 이고, 전건 동시 실행으로 이벤트 루프가 밀려도
     *   창이 좁아 생기는 위양성이 나지 않는다. 그러면서 vitest 기본 제한(5초)보다는 짧아
     *   배선이 끊겼을 때 «타임아웃» 이 아니라 아래 메시지로 죽는다.
     */
    const outcome = await Promise.race([
      pending.then(
        () => 'resolved' as const,
        () => 'aborted' as const,
      ),
      new Promise<'still-running'>((resolve) => {
        setTimeout(() => resolve('still-running'), 1000);
      }),
    ]);

    // then: 전송이 실제로 끊긴다. 'still-running' 이면 신호가 요청에 실리지 않은 것이다.
    expect(
      outcome,
      "중단 신호를 걸었는데 요청이 그대로 살아 있다 — 신호가 요청 설정에 실리지 않았다('취소'가 거짓 조작이 된다)",
    ).toBe('aborted');
  });

  it('취소_신호를_넘기지_않아도_그대로_동작한다', async () => {
    // given: 기존 호출부(포털 업로드 목록 화면)는 인자 1개로 부른다 — 선택 인자라 동작 불변이다
    let seenSignal: unknown = 'not-touched';
    mock.onGet('/portal/uploads/1/export').reply((config) => {
      seenSignal = config.signal;
      return [200, '{"ok":true}'];
    });

    // when
    await downloadUploadExport(1);

    // then
    expect(seenSignal).toBeUndefined();
    expect(mock.history.get).toHaveLength(1);
  });
});
