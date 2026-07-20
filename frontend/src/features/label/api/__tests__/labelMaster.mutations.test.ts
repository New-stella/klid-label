// Phase 2 — 라벨 마스터 CRUD(생성/수정/삭제) API 클라이언트 테스트.
//
// BE 계약:
//   POST   /v1/manage/labels          (201, 생성 결과)
//   PUT    /v1/manage/labels/{id}      (200, 수정 결과)
//   DELETE /v1/manage/labels/{id}      (204, 본문 없음)
// 중복 시 409, 검증 실패 시 400.

import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import MockAdapter from 'axios-mock-adapter';

import { apiClient } from '@/lib/api/client';

import {
  createLabelMaster,
  deleteLabelMaster,
  updateLabelMaster,
  type LabelMasterUpsert,
} from '../labelMaster';

const UPSERT: LabelMasterUpsert = {
  name: '자전거',
  color: '#22C55E',
  type: 'BBOX',
  sortNo: 5,
};

describe('labelMaster CRUD api', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
  });

  afterEach(() => {
    mock.restore();
  });

  it('createLabelMaster가_POST_manage_labels로_요청하고_생성결과를_반환한다', async () => {
    // given
    let capturedBody: unknown;
    mock.onPost('/manage/labels').reply((config) => {
      capturedBody = JSON.parse(config.data as string);
      return [
        201,
        {
          success: true,
          data: { labelId: 99, name: '자전거', color: '#22C55E', type: 'BBOX', sortNo: 5, useYn: 'Y' },
          message: null,
          errorCode: null,
        },
      ];
    });

    // when
    const res = await createLabelMaster(UPSERT);

    // then
    expect(capturedBody).toEqual({ name: '자전거', color: '#22C55E', type: 'BBOX', sortNo: 5 });
    expect(res.labelId).toBe(99);
    expect(res.type).toBe('BBOX');
    expect(res.useYn).toBe('Y');
    expect(mock.history.post).toHaveLength(1);
  });

  it('updateLabelMaster가_PUT_manage_labels_id로_요청한다', async () => {
    // given
    let capturedUrl = '';
    let capturedBody: unknown;
    mock.onPut('/manage/labels/7').reply((config) => {
      capturedUrl = config.url ?? '';
      capturedBody = JSON.parse(config.data as string);
      return [
        200,
        {
          success: true,
          data: { labelId: 7, name: '자전거', color: '#22C55E', type: 'BBOX', sortNo: 5, useYn: 'Y' },
          message: null,
          errorCode: null,
        },
      ];
    });

    // when
    const res = await updateLabelMaster(7, UPSERT);

    // then
    expect(capturedUrl).toBe('/manage/labels/7');
    expect(capturedBody).toEqual({ name: '자전거', color: '#22C55E', type: 'BBOX', sortNo: 5 });
    expect(res.labelId).toBe(7);
  });

  it('deleteLabelMaster가_DELETE_요청하고_204를_에러없이_처리한다', async () => {
    // given — 204 No Content (본문 없음)
    mock.onDelete('/manage/labels/7').reply(204);

    // when / then — 예외 없이 void 반환
    await expect(deleteLabelMaster(7)).resolves.toBeUndefined();
    expect(mock.history.delete).toHaveLength(1);
    expect(mock.history.delete[0].url).toBe('/manage/labels/7');
  });

  it('createLabelMaster_중복이면_409_에러_throw', async () => {
    // given
    mock.onPost('/manage/labels').reply(409, {
      success: false,
      data: null,
      message: '이미 존재하는 라벨명',
      errorCode: 'DUPLICATE_LABEL',
    });

    // when / then
    await expect(createLabelMaster(UPSERT)).rejects.toThrow();
  });

  it('createLabelMaster_검증실패면_400_에러_throw', async () => {
    // given
    mock.onPost('/manage/labels').reply(400, {
      success: false,
      data: null,
      message: '입력값 검증 실패',
      errorCode: 'INVALID_INPUT',
    });

    // when / then
    await expect(createLabelMaster({ ...UPSERT, name: '' })).rejects.toThrow();
  });
});
