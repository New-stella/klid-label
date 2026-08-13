import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import MockAdapter from 'axios-mock-adapter';

import { apiClient } from '@/lib/api/client';

import { getUser, listUsers, listWorkers } from '../api';

describe('user api', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
  });

  afterEach(() => {
    mock.restore();
  });

  it('listWorkers_GET_users_workers', async () => {
    mock.onGet('/users/workers').reply(200, {
      success: true,
      data: [
        { id: 7, name: '홍길동', active: true },
        { id: 8, name: '김작업', active: true },
      ],
      message: null,
      errorCode: null,
    });

    const workers = await listWorkers();
    expect(workers).toHaveLength(2);
    expect(workers[0].name).toBe('홍길동');
  });

  it('listUsers_검색_파라미터_전달', async () => {
    mock.onGet('/users').reply((config) => {
      expect(config.params).toMatchObject({ keyword: '홍', role: 'WORKER' });
      return [
        200,
        {
          success: true,
          data: {
            content: [
              {
                id: 7,
                loginId: 'hong',
                name: '홍길동',
                role: 'WORKER',
                active: true,
                createdAt: '2026-05-01T00:00:00Z',
              },
            ],
            totalElements: 1,
            totalPages: 1,
            number: 0,
            size: 20,
          },
          message: null,
          errorCode: null,
        },
      ];
    });

    const res = await listUsers({ keyword: '홍', role: 'WORKER' });
    expect(res.totalElements).toBe(1);
  });

  // 단건 조회 응답은 BE `UserProfileResponse` 다 — 목록(`UserSummaryResponse`)이 함께 내려주는
  // FE alias(id/loginId/name/email)가 **없고** 원본 컬럼명만 온다. 이 테스트가 alias 형태의
  // 목 데이터를 쓰던 동안 FE 타입도 목록 타입(`User`)으로 선언돼 있어, 실제 응답에는 없는
  // 필드를 읽어도 컴파일이 통과하는 상태였다(값은 조용히 undefined).
  it('getUser_상세_정상_파싱', async () => {
    mock.onGet('/users/7').reply(200, {
      success: true,
      data: {
        userNo: 7,
        userId: 'hong',
        userNm: '홍길동',
        userEmail: 'hong@example.com',
        role: 'WORKER',
        // 관리 경로는 피조회 사용자의 요청 컨텍스트가 없어 BE 가 빈 문자열을 넣는다.
        channel: '',
      },
      message: null,
      errorCode: null,
    });

    const u = await getUser(7);
    expect(u.userNo).toBe(7);
    expect(u.userNm).toBe('홍길동');
    expect(u.channel).toBe('');
  });

  it('getUser_역할_미배정이면_null_이다', async () => {
    // 자동등록 사용자는 LS_USER_ROLE 행이 없어 role 이 null 로 온다(기본값 부여 안 함).
    mock.onGet('/users/8').reply(200, {
      success: true,
      data: {
        userNo: 8,
        userId: 'newbie',
        userNm: '신규',
        userEmail: null,
        role: null,
        channel: '',
      },
      message: null,
      errorCode: null,
    });

    const u = await getUser(8);
    expect(u.role).toBeNull();
  });
});
