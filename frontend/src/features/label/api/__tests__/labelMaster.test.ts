// Phase 7 — 라벨 마스터 API 클라이언트 테스트.
//
// GET /v1/manage/labels → 활성 라벨 마스터 13건 응답을 정규화하여 반환.

import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import MockAdapter from 'axios-mock-adapter';

import { apiClient } from '@/lib/api/client';

import { fetchLabelMasters } from '../labelMaster';

describe('labelMaster api', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
  });

  afterEach(() => {
    mock.restore();
  });

  it('GET_/manage/labels_응답을_LabelMaster[]로_매핑', async () => {
    mock.onGet('/manage/labels').reply(200, {
      success: true,
      data: [
        {
          labelId: 1,
          name: '사람',
          color: '#EF4444',
          type: 'BBOX',
          sortNo: 1,
          useYn: 'Y',
        },
        {
          labelId: 2,
          name: '차량',
          color: '#3B82F6',
          type: 'BBOX',
          sortNo: 2,
          useYn: 'Y',
        },
      ],
      message: null,
      errorCode: null,
    });

    const list = await fetchLabelMasters();
    expect(list).toHaveLength(2);
    expect(list[0].labelId).toBe(1);
    expect(list[0].name).toBe('사람');
    expect(list[0].color).toBe('#EF4444');
    expect(list[0].type).toBe('BBOX');
    expect(list[0].sortNo).toBe(1);
    expect(list[0].useYn).toBe('Y');
  });

  it('SKELETON_타입_라벨마스터_수용', async () => {
    mock.onGet('/manage/labels').reply(200, {
      success: true,
      data: [
        {
          labelId: 9,
          name: '보행자포즈',
          color: '#22C55E',
          type: 'SKELETON',
          sortNo: 9,
          useYn: 'Y',
        },
      ],
      message: null,
      errorCode: null,
    });
    const list = await fetchLabelMasters();
    expect(list).toHaveLength(1);
    expect(list[0].type).toBe('SKELETON');
  });

  it('알수없는_타입은_BBOX로_폴백', async () => {
    mock.onGet('/manage/labels').reply(200, {
      success: true,
      data: [
        {
          labelId: 10,
          name: '이상',
          color: '#000000',
          type: 'UNKNOWN_XYZ',
          sortNo: 10,
          useYn: 'Y',
        },
      ],
      message: null,
      errorCode: null,
    });
    const list = await fetchLabelMasters();
    expect(list[0].type).toBe('BBOX');
  });

  it('빈_배열_응답도_정상_처리', async () => {
    mock.onGet('/manage/labels').reply(200, {
      success: true,
      data: [],
      message: null,
      errorCode: null,
    });
    const list = await fetchLabelMasters();
    expect(list).toEqual([]);
  });
});
