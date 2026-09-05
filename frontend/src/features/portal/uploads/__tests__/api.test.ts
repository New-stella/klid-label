// Phase 5 — 포털 업로드 API 모듈. apiClient(baseURL /api/v1, 인증 자동첨부) 계약 단언.
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import MockAdapter from 'axios-mock-adapter';

import { apiClient } from '@/lib/api/client';

import { deleteUpload, listUploads } from '../api';

describe('portal uploads api', () => {
  let mock: MockAdapter;
  beforeEach(() => {
    mock = new MockAdapter(apiClient);
  });
  afterEach(() => mock.restore());

  it('목록조회는_type_page_size_쿼리로_GET', async () => {
    mock.onGet('/portal/uploads').reply((config) => {
      expect(config.params).toMatchObject({ type: 'VIDEO', page: 0, size: 20 });
      return [
        200,
        {
          success: true,
          data: { content: [], totalElements: 0, totalPages: 0, number: 0, size: 20 },
          message: null,
          errorCode: null,
        },
      ];
    });

    const page = await listUploads({ type: 'VIDEO', page: 0, size: 20 });
    expect(page.content).toHaveLength(0);
  });

  it('삭제는_DELETE_uldSn', async () => {
    mock.onDelete('/portal/uploads/7').reply(204);
    await expect(deleteUpload(7)).resolves.toBeUndefined();
    expect(mock.history.delete).toHaveLength(1);
  });
});
