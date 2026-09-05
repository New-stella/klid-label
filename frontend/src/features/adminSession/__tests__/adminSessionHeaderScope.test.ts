// 회귀 가드 — 관리자 유효창 헤더는 **쓰기에만** 실린다. 조회에는 절대 붙지 않는다.
// [@design API-194] [@design API-004] [@design API-158] [@design ADR-046]
//
// ★왜 이 가드가 필요한가
//   유효창이 「관리 기능 공통 진입」으로 넓어지면서, 뒤에 오는 사람이 «일관성» 을 이유로 조회
//   창구에까지 헤더를 얹을 유인이 생겼다. 그러면 관리 화면만 깨지는 것이 아니라
//   **작업 배정 흐름이 통째로 끊긴다** — 배정 화면이 `GET /v1/users/workers` 로 작업자 목록을
//   읽는데, 그 화면은 유효창을 열 통로조차 없다.
//
// ★TUS 의 비대칭도 함께 못 박는다
//   세션 생성(POST)에만 실리고 청크(PATCH)·offset(HEAD)·취소(DELETE)에는 실리지 않는다.
//   대용량 영상 업로드는 유효창(기본 10분)을 넘기기 마련이라, 청크마다 요구하면 업로드가 도중에
//   끊긴다. 그래도 안전한 이유는 서버의 세션 소유자 검증이 이미 완비돼 있기 때문이다.
//
// ⚠ 이 가드가 못 보는 것
//   화면이 토큰을 **넘기는지**는 여기서 보지 않는다(그건 각 화면 테스트의 몫이다). 여기서 보는
//   것은 「넘겼을 때 어디에 실리고 어디에 안 실리는가」라는 계약 하나다.

import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import MockAdapter from 'axios-mock-adapter';
import type { AxiosRequestHeaders } from 'axios';

import { ADMIN_SESSION_HEADER } from '@/features/adminSession/api';
import { uploadAutolabelTest } from '@/features/dev/api';
import { updateConfig } from '@/features/sysconfig/api';
import { cancelUpload, fetchOffset, uploadFile } from '@/features/upload/api/tusClient';
import { getUser, listUsers, listWorkers, updateUser } from '@/features/user/api';
import { apiClient } from '@/lib/api/client';

const WINDOW_TOKEN = 'issued-window-value';

/** 관측된 요청의 헤더에 유효창 헤더가 실렸는가. */
function hasAdminHeader(headers: unknown): boolean {
  if (!headers || typeof headers !== 'object') return false;
  const h = headers as Record<string, unknown>;
  // axios 는 헤더 이름의 대소문자를 보존하므로 두 표기를 모두 본다.
  return (
    h[ADMIN_SESSION_HEADER] !== undefined ||
    h[ADMIN_SESSION_HEADER.toLowerCase()] !== undefined
  );
}

describe('관리자 유효창 헤더 적용 범위', () => {
  let mock: MockAdapter;
  /** 관측된 요청 전량 — TUS 는 한 번의 호출이 여러 요청을 낳으므로 마지막 하나만 보면 안 된다. */
  let requests: Array<{ method: string; headers: AxiosRequestHeaders }>;
  /** 마지막으로 관측된 요청 헤더. */
  let seen: AxiosRequestHeaders | undefined;
  /**
   * 청크(PATCH) 응답이 돌려줄 offset.
   *
   * ⚠ **이 값이 파일 크기 이상이어야 업로드 루프가 끝난다.** 0 을 돌려주면
   * `uploadFile` 의 `while (offset < file.size)` 가 영원히 돌아 테스트가 메모리를 소진한다
   * (실측으로 한 번 겪었다). 그래서 요청 메서드를 보고 청크에만 이 값을 싣는다.
   */
  let chunkOffset: string;

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
    requests = [];
    seen = undefined;
    chunkOffset = '0';
    mock.onAny().reply((config) => {
      const method = String(config.method ?? '').toLowerCase();
      seen = config.headers as AxiosRequestHeaders;
      requests.push({ method, headers: config.headers as AxiosRequestHeaders });
      // TUS 세션 생성은 Location 헤더에서 uploadId 를, 청크는 Upload-Offset 을 읽는다.
      return [
        200,
        { success: true, data: {}, message: null, errorCode: null },
        {
          location: '/v1/uploads/upload-1',
          'upload-offset': method === 'patch' ? chunkOffset : '0',
        },
      ];
    });
  });

  /** 특정 메서드로 나간 요청들. */
  const byMethod = (method: string) => requests.filter((r) => r.method === method);

  afterEach(() => {
    mock.restore();
  });

  describe('조회에는 붙지 않는다', () => {
    it('사용자_목록_조회에_유효창_헤더가_없다', async () => {
      await listUsers({ page: 0, size: 20 });
      expect(hasAdminHeader(seen)).toBe(false);
    });

    it('사용자_단건_조회에_유효창_헤더가_없다', async () => {
      await getUser(7);
      expect(hasAdminHeader(seen)).toBe(false);
    });

    it('★작업자_목록_조회에_유효창_헤더가_없다 — 배정_흐름이_끊긴다', async () => {
      // 이 창구는 관리 화면이 아니라 **작업 배정 화면**이 읽는다. 여기에 요건을 얹으면 배정을
      // 하려는 검수자가 유효창을 열 통로도 없이 막힌다.
      await listWorkers();
      expect(hasAdminHeader(seen)).toBe(false);
    });
  });

  describe('운영·관리 성격의 쓰기에는 붙는다', () => {
    it('역할_변경에_유효창_헤더가_실린다', async () => {
      await updateUser(7, { role: 'WORKER' }, WINDOW_TOKEN);
      expect(hasAdminHeader(seen)).toBe(true);
    });

    it('연동_주소_저장에_유효창_헤더가_실린다', async () => {
      await updateConfig({
        key: 'vlm.client.url',
        value: 'https://example.invalid',
        adminSessionToken: WINDOW_TOKEN,
      });
      expect(hasAdminHeader(seen)).toBe(true);
    });

    it('업로드_요청에_유효창_헤더가_실린다', async () => {
      await uploadAutolabelTest(
        new File(['x'], 'a.mp4', { type: 'video/mp4' }),
        {} as never,
        WINDOW_TOKEN,
      );
      expect(hasAdminHeader(seen)).toBe(true);
    });

    it('토큰을_넘기지_않으면_헤더_자체를_붙이지_않는다', async () => {
      // 빈 값 헤더를 보내면 서버 기록에 「헤더는 왔는데 값이 없다」가 쌓여 원인 추적이 흐려진다.
      await updateUser(7, { role: 'WORKER' });
      expect(hasAdminHeader(seen)).toBe(false);
    });
  });

  describe('TUS — 세션 생성에만 붙고 이어 보내기에는 붙지 않는다', () => {
    const file = new File(['0123456789'], 'v.mp4', { type: 'video/mp4' });

    it('★생성에는_실리고_청크에는_실리지_않는다 — 유효창을_넘겨도_업로드가_끊기지_않아야_한다', async () => {
      // 한 번의 업로드가 POST(생성) 1회 + PATCH(청크) 1회를 낳는다. 두 축을 **같은 실행에서**
      // 함께 본다 — 따로 보면 「생성에 붙는다」만 지키고 청크 축이 조용히 바뀌어도 통과한다.
      // 청크 한 번에 전량 올라간 것으로 응답한다 — 아니면 업로드 루프가 끝나지 않는다.
      chunkOffset = String(file.size);
      await uploadFile({
        file,
        metadata: { filename: 'v.mp4' },
        endpointBase: '/uploads',
        createPayload: {} as never,
        adminSessionToken: WINDOW_TOKEN,
      });

      const posts = byMethod('post');
      const patches = byMethod('patch');
      expect(posts.length, '세션 생성 요청이 나가지 않았다').toBe(1);
      expect(patches.length, '청크 요청이 나가지 않았다').toBeGreaterThanOrEqual(1);

      expect(hasAdminHeader(posts[0]!.headers)).toBe(true);
      for (const patch of patches) {
        expect(hasAdminHeader(patch.headers)).toBe(false);
      }
    });

    it('offset_조회에는_실리지_않는다', async () => {
      await fetchOffset('upload-1', '/uploads');
      expect(hasAdminHeader(seen)).toBe(false);
    });

    it('취소에는_실리지_않는다', async () => {
      await cancelUpload('upload-1', '/uploads');
      expect(hasAdminHeader(seen)).toBe(false);
    });
  });
});
