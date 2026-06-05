import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import MockAdapter from 'axios-mock-adapter';

import { apiClient } from '@/lib/api/client';

import {
  createNotice,
  listNotices,
  publishNotice,
  uploadAttachment,
} from '../api';

describe('notice api', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
  });

  afterEach(() => {
    mock.restore();
  });

  it('listNotices_검색_파라미터_전달', async () => {
    mock.onGet('/notices').reply((config) => {
      expect(config.params).toMatchObject({
        page: 0,
        size: 20,
        field: 'TITLE',
        keyword: '점검',
      });
      return [
        200,
        {
          success: true,
          data: {
            content: [],
            totalElements: 0,
            totalPages: 0,
            number: 0,
            size: 20,
          },
          message: null,
          errorCode: null,
        },
      ];
    });

    const page = await listNotices({
      page: 0,
      size: 20,
      field: 'TITLE',
      keyword: '점검',
    });
    expect(page.totalElements).toBe(0);
  });

  it('createNotice_zod_검증_통과시_POST_notices', async () => {
    mock.onPost('/notices').reply((config) => {
      expect(JSON.parse(config.data)).toMatchObject({
        title: '점검 공지',
        content: '본문',
        pinned: true,
      });
      return [
        201,
        {
          success: true,
          data: {
            id: 1,
            title: '점검 공지',
            content: '본문',
            pinned: true,
            pubStatus: 'DRAFT',
            pubDt: null,
            regId: 'r1',
            regDt: '2026-06-01T00:00:00',
            mdfcnDt: null,
            attachments: [],
          },
          message: null,
          errorCode: null,
        },
      ];
    });

    const created = await createNotice({
      title: '점검 공지',
      content: '본문',
      pinned: true,
    });
    expect(created.id).toBe(1);
    expect(created.pubStatus).toBe('DRAFT');
  });

  it('createNotice_제목_미입력시_zod_거부', async () => {
    await expect(
      createNotice({ title: '', content: '본문', pinned: false }),
    ).rejects.toThrow();
  });

  it('publishNotice_POST_publish', async () => {
    mock.onPost('/notices/3/publish').reply(200, {
      success: true,
      data: {
        id: 3,
        title: 't',
        content: 'c',
        pinned: false,
        pubStatus: 'PUBLISHED',
        pubDt: '2026-06-01T00:00:00',
        regId: 'r1',
        regDt: '2026-06-01T00:00:00',
        mdfcnDt: null,
        attachments: [],
      },
      message: null,
      errorCode: null,
    });

    const result = await publishNotice(3);
    expect(result.pubStatus).toBe('PUBLISHED');
  });

  it('uploadAttachment_multipart_FormData_전송', async () => {
    mock.onPost('/notices/2/attachments').reply((config) => {
      expect(config.data).toBeInstanceOf(FormData);
      return [
        201,
        {
          success: true,
          data: {
            attachSn: 9,
            fileName: 'a.pdf',
            fileSize: 100,
            regDt: '2026-06-01T00:00:00',
          },
          message: null,
          errorCode: null,
        },
      ];
    });

    const file = new File(['x'], 'a.pdf', { type: 'application/pdf' });
    const result = await uploadAttachment(2, file);
    expect(result.attachSn).toBe(9);
  });
});
