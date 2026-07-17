// Phase 5 — 포털 업로드 API 모듈. apiClient(baseURL /api/v1, 인증 자동첨부) 계약 단언.
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import MockAdapter from 'axios-mock-adapter';

import { apiClient } from '@/lib/api/client';

import { deleteUpload, listUploads, uploadImages } from '../api';

function imgFile(name: string): File {
  return new File([new Uint8Array([1, 2, 3])], name, { type: 'image/jpeg' });
}

describe('portal uploads api', () => {
  let mock: MockAdapter;
  beforeEach(() => {
    mock = new MockAdapter(apiClient);
  });
  afterEach(() => mock.restore());

  it('이미지_업로드는_multipart_files_필드로_POST', async () => {
    mock.onPost('/portal/uploads/images').reply((config) => {
      // FormData 로 전송되어야 한다 (multipart).
      expect(config.data).toBeInstanceOf(FormData);
      const fd = config.data as FormData;
      const files = fd.getAll('files');
      expect(files).toHaveLength(2);
      return [
        201,
        {
          success: true,
          data: [
            { uldSn: 1, uldTypeCd: 'IMAGE', orgnlFileNm: 'a.jpg', fileSz: 3, mimeTypeNm: 'image/jpeg', uldSttsCd: 'READY', frmeCnt: 1, frmeSn: 10, regDt: '2026-07-17T00:00:00' },
            { uldSn: 2, uldTypeCd: 'IMAGE', orgnlFileNm: 'b.jpg', fileSz: 3, mimeTypeNm: 'image/jpeg', uldSttsCd: 'READY', frmeCnt: 1, frmeSn: 11, regDt: '2026-07-17T00:00:01' },
          ],
          message: null,
          errorCode: null,
        },
      ];
    });

    const res = await uploadImages([imgFile('a.jpg'), imgFile('b.jpg')]);
    expect(res).toHaveLength(2);
    expect(res[0].uldSn).toBe(1);
  });

  it('목록조회는_type_page_size_쿼리로_GET', async () => {
    mock.onGet('/portal/uploads').reply((config) => {
      expect(config.params).toMatchObject({ type: 'IMAGE', page: 0, size: 20 });
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

    const page = await listUploads({ type: 'IMAGE', page: 0, size: 20 });
    expect(page.content).toHaveLength(0);
  });

  it('삭제는_DELETE_uldSn', async () => {
    mock.onDelete('/portal/uploads/7').reply(204);
    await expect(deleteUpload(7)).resolves.toBeUndefined();
    expect(mock.history.delete).toHaveLength(1);
  });
});
