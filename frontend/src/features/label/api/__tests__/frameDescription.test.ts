// blocker#2 Phase 3 — 프레임 설명(NIA image.description) API 클라이언트 테스트.
//
// GET/PUT /v1/frames/{srcSn}/description → ApiResponse<{srcSn, description}> 언랩.

import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import MockAdapter from 'axios-mock-adapter';

import { apiClient } from '@/lib/api/client';

import { getFrameDescription, putFrameDescription } from '../frameDescription';

describe('frameDescription api', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
  });

  afterEach(() => {
    mock.restore();
  });

  it('GET_설명_조회_언랩', async () => {
    // given
    mock.onGet('/frames/42/description').reply(200, {
      success: true,
      data: { srcSn: 42, description: '횡단보도를 건너는 보행자' },
      message: null,
      errorCode: null,
    });

    // when
    const result = await getFrameDescription(42);

    // then
    expect(result.srcSn).toBe(42);
    expect(result.description).toBe('횡단보도를 건너는 보행자');
  });

  it('null_description_처리', async () => {
    // given — 설명 미입력 프레임
    mock.onGet('/frames/7/description').reply(200, {
      success: true,
      data: { srcSn: 7, description: null },
      message: null,
      errorCode: null,
    });

    // when
    const result = await getFrameDescription(7);

    // then
    expect(result.description).toBeNull();
  });

  it('PUT_설명_저장_언랩', async () => {
    // given
    mock.onPut('/frames/42/description').reply((config) => {
      // path·body srcSn 일치 확인 (BE 계약)
      const body = JSON.parse(config.data as string) as {
        srcSn: number;
        description: string;
      };
      expect(body.srcSn).toBe(42);
      expect(body.description).toBe('수정된 설명');
      return [
        200,
        {
          success: true,
          data: { srcSn: 42, description: '수정된 설명' },
          message: null,
          errorCode: null,
        },
      ];
    });

    // when
    const result = await putFrameDescription(42, '수정된 설명');

    // then
    expect(result.srcSn).toBe(42);
    expect(result.description).toBe('수정된 설명');
  });
});
