import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import MockAdapter from 'axios-mock-adapter';

import { apiClient } from '@/lib/api/client';

import { getExportStatus, listDatasets, prepareExport } from '../api';

describe('export api', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
  });

  afterEach(() => {
    mock.restore();
  });

  it('prepareExport_POST_exports_prepare_바디_전달_BE_alias_pjtId_포함', async () => {
    let body: { pjtId?: number; datasetId?: number; format?: string; nasPath?: string } = {};
    mock.onPost('/exports/prepare').reply((config) => {
      body = JSON.parse(config.data ?? '{}');
      return [
        200,
        {
          success: true,
          data: {
            exportId: 7,
            status: 'PREPARING',
            preview: { videoCount: 10, frameCount: 9000, labelCount: 15000 },
          },
          message: null,
          errorCode: null,
        },
      ];
    });

    const res = await prepareExport({
      datasetId: 1,
      format: 'COCO',
      nasPath: '/mnt/nas/exports/2026-05',
    });
    // BE 가 기대하는 pjtId 와 FE 호환 datasetId 둘 다 송신.
    expect(body.pjtId).toBe(1);
    expect(body.datasetId).toBe(1);
    expect(body.format).toBe('COCO');
    expect(body.nasPath).toBe('/mnt/nas/exports/2026-05');
    expect(res.exportId).toBe(7);
    expect(res.preview?.videoCount).toBe(10);
  });

  it('getExportStatus_GET_exports_id_status', async () => {
    mock.onGet('/exports/7/status').reply(200, {
      success: true,
      data: {
        exportId: 7,
        status: 'READY',
        completedAt: '2026-05-07T12:00:00Z',
      },
      message: null,
      errorCode: null,
    });

    const res = await getExportStatus(7);
    expect(res.status).toBe('READY');
  });

  it('listDatasets_GET_exports_datasets', async () => {
    mock.onGet('/exports/datasets').reply(200, {
      success: true,
      data: [
        { id: 1, name: '교통 사고 2026-05', videoCount: 100 },
        { id: 2, name: '화재 2026-04', videoCount: 50 },
      ],
      message: null,
      errorCode: null,
    });

    const res = await listDatasets();
    expect(res).toHaveLength(2);
    expect(res[0].id).toBe(1);
  });
});
