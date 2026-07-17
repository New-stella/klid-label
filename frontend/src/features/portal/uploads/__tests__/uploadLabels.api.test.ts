// Phase 6 — 포털 업로드 라벨 API/직렬화 계약 단언.
// - PUT body 는 최상위 raw 배열이고 points 는 배열(문자열 직렬화 금지).
// - GET 라벨 → FE Label 매핑. 다운로드는 export/file 엔드포인트를 blob 으로 호출.
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import MockAdapter from 'axios-mock-adapter';

import { apiClient } from '@/lib/api/client';
import type { Label } from '@/features/label/types';

import {
  downloadUploadExport,
  downloadUploadFile,
  getUploadFrameLabels,
  replaceUploadFrameLabels,
  type UploadFrameLabel,
} from '../api';
import { serializeUploadLabels } from '../hooks/useSaveUploadLabels';
import { mapUploadLabels } from '../hooks/useUploadFrameLabels';

function bbox(id: string): Label {
  return {
    id,
    frameNo: 0,
    classId: 1,
    className: 'car',
    source: 'MANUAL',
    shape: { type: 'BBOX', left: 10, top: 20, right: 110, bottom: 220 },
  };
}
function polygon(id: string): Label {
  return {
    id,
    frameNo: 0,
    classId: 2,
    className: 'road',
    source: 'MANUAL',
    shape: { type: 'POLYGON', points: [0, 0, 100, 0, 100, 100] },
  };
}

describe('portal upload label api', () => {
  let mock: MockAdapter;
  beforeEach(() => {
    mock = new MockAdapter(apiClient);
  });
  afterEach(() => {
    mock.restore();
    vi.restoreAllMocks();
  });

  it('PUT_은_최상위_raw배열이고_points는_문자열이_아닌_배열', async () => {
    mock.onPut('/portal/uploads/frames/5/labels').reply((config) => {
      const parsed = JSON.parse(config.data as string);
      // 최상위가 배열이어야 한다 ({items:[...]} 래퍼 금지).
      expect(Array.isArray(parsed)).toBe(true);
      expect(parsed).toHaveLength(2);
      // points 는 number[][] — 문자열(JSON.stringify) 이 아니어야 한다.
      expect(Array.isArray(parsed[0].points)).toBe(true);
      expect(typeof parsed[0].points).not.toBe('string');
      expect(parsed[0]).toMatchObject({ lblTypeCd: 'BBOX', label: 'car' });
      expect(parsed[0].points).toEqual([
        [10, 20],
        [110, 220],
      ]);
      expect(parsed[1]).toMatchObject({ lblTypeCd: 'POLYGON', label: 'road' });
      expect(parsed[1].points).toEqual([
        [0, 0],
        [100, 0],
        [100, 100],
      ]);
      return [200, { success: true, data: [], message: null, errorCode: null }];
    });

    await replaceUploadFrameLabels(5, serializeUploadLabels([bbox('a'), polygon('b')]));
    expect(mock.history.put).toHaveLength(1);
  });

  it('빈_배열_저장은_전체삭제로_빈_배열_PUT', async () => {
    mock.onPut('/portal/uploads/frames/5/labels').reply((config) => {
      expect(JSON.parse(config.data as string)).toEqual([]);
      return [200, { success: true, data: [], message: null, errorCode: null }];
    });
    await replaceUploadFrameLabels(5, serializeUploadLabels([]));
    expect(mock.history.put).toHaveLength(1);
  });

  it('GET_라벨은_FE_Label로_매핑된다', async () => {
    const raw: UploadFrameLabel[] = [
      {
        uldLblSn: 1,
        uldFrmeSn: 5,
        lblTypeCd: 'BBOX',
        label: 'car',
        points: [
          [10, 20],
          [110, 220],
        ],
        regDt: '2026-07-17T00:00:00',
        mdfcnDt: null,
      },
    ];
    mock
      .onGet('/portal/uploads/frames/5/labels')
      .reply(200, { success: true, data: raw, message: null, errorCode: null });

    const got = await getUploadFrameLabels(5);
    const mapped = mapUploadLabels(got, 3);
    expect(mapped).toHaveLength(1);
    expect(mapped[0].shape).toEqual({ type: 'BBOX', left: 10, top: 20, right: 110, bottom: 220 });
    expect(mapped[0].className).toBe('car');
    expect(mapped[0].frameNo).toBe(3);
    expect(mapped[0].serverId).toBe(1);
  });

  it('다운로드_내보내기는_export_엔드포인트를_blob으로_호출', async () => {
    vi.stubGlobal('URL', {
      createObjectURL: vi.fn(() => 'blob:x'),
      revokeObjectURL: vi.fn(),
    });
    mock.onGet('/portal/uploads/9/export').reply(200, '{"ok":true}', {
      'content-disposition': 'attachment; filename="upload-9.json"',
    });
    await downloadUploadExport(9);
    expect(mock.history.get.some((r) => r.url === '/portal/uploads/9/export')).toBe(true);
    expect(mock.history.get[0].responseType).toBe('blob');
  });

  it('다운로드_원본은_file_엔드포인트를_blob으로_호출', async () => {
    vi.stubGlobal('URL', {
      createObjectURL: vi.fn(() => 'blob:x'),
      revokeObjectURL: vi.fn(),
    });
    mock.onGet('/portal/uploads/9/file').reply(200, 'binary', {
      'content-disposition': 'attachment; filename="a.jpg"',
    });
    await downloadUploadFile(9, 'a.jpg');
    expect(mock.history.get.some((r) => r.url === '/portal/uploads/9/file')).toBe(true);
  });
});

describe('serializeUploadLabels', () => {
  it('MASK_KEYPOINT_는_제외하고_BBOX_POLYGON_만_직렬화', () => {
    const kpt: Label = {
      id: 'k',
      frameNo: 0,
      classId: 3,
      className: 'person',
      source: 'MANUAL',
      shape: { type: 'KEYPOINT', keypoints: Array.from({ length: 17 }, () => ({ x: 1, y: 1, v: 2 })) },
    };
    const out = serializeUploadLabels([bbox('a'), kpt]);
    expect(out).toHaveLength(1);
    expect(out[0].lblTypeCd).toBe('BBOX');
  });

  it('label은_80자로_절단', () => {
    const long = { ...bbox('a'), className: 'x'.repeat(200) };
    const out = serializeUploadLabels([long]);
    expect(out[0].label).toHaveLength(80);
  });
});
