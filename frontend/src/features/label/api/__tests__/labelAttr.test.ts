// Phase 2 — 라벨별 속성 정의 CRUD API 클라이언트 테스트.
//
// BE 계약:
//   GET    /v1/manage/labels/{labelId}/attrs           (List<LabelAttrResponse>)
//   POST   /v1/manage/labels/{labelId}/attrs           (201)
//   PUT    /v1/manage/labels/{labelId}/attrs/{attrId}   (200)
//   DELETE /v1/manage/labels/{labelId}/attrs/{attrId}   (204)
// 동일 이름 중복 시 409, 검증 실패 시 400.

import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import MockAdapter from 'axios-mock-adapter';

import { apiClient } from '@/lib/api/client';

import {
  createLabelAttr,
  deleteLabelAttr,
  fetchLabelAttrs,
  updateLabelAttr,
  type LabelAttrUpsert,
} from '../labelAttr';

const UPSERT: LabelAttrUpsert = {
  name: '색상',
  inputType: 'SELECT',
  valuesJson: '["빨강","파랑"]',
  defaultVal: '빨강',
  mutable: 'Y',
  sortNo: 1,
};

describe('labelAttr api', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
  });

  afterEach(() => {
    mock.restore();
  });

  it('fetchLabelAttrs가_GET_manage_labels_id_attrs를_호출하고_배열을_반환한다', async () => {
    // given
    mock.onGet('/manage/labels/3/attrs').reply(200, {
      success: true,
      data: [
        {
          attrId: 10,
          labelId: 3,
          name: '색상',
          inputType: 'SELECT',
          valuesJson: '["빨강","파랑"]',
          defaultVal: '빨강',
          mutable: 'Y',
          sortNo: 1,
          useYn: 'Y',
        },
        {
          attrId: 11,
          labelId: 3,
          name: '메모',
          inputType: 'TEXT',
          valuesJson: null,
          defaultVal: null,
          mutable: 'N',
          sortNo: 2,
          useYn: 'Y',
        },
      ],
      message: null,
      errorCode: null,
    });

    // when
    const res = await fetchLabelAttrs(3);

    // then
    expect(res).toHaveLength(2);
    expect(res[0].attrId).toBe(10);
    expect(res[0].inputType).toBe('SELECT');
    expect(res[1].valuesJson).toBeNull();
    expect(res[1].mutable).toBe('N');
    expect(mock.history.get[0].url).toBe('/manage/labels/3/attrs');
  });

  it('fetchLabelAttrs_비배열_응답이면_빈배열_반환', async () => {
    // given — 방어: BE 가 비정상 응답을 줘도 빈 배열
    mock.onGet('/manage/labels/4/attrs').reply(200, {
      success: true,
      data: null,
      message: null,
      errorCode: null,
    });

    // when
    const res = await fetchLabelAttrs(4);

    // then
    expect(res).toEqual([]);
  });

  it('createLabelAttr가_labelId경로로_POST하고_생성결과를_반환한다', async () => {
    // given
    let capturedBody: unknown;
    mock.onPost('/manage/labels/3/attrs').reply((config) => {
      capturedBody = JSON.parse(config.data as string);
      return [
        201,
        {
          success: true,
          data: {
            attrId: 20,
            labelId: 3,
            name: '색상',
            inputType: 'SELECT',
            valuesJson: '["빨강","파랑"]',
            defaultVal: '빨강',
            mutable: 'Y',
            sortNo: 1,
            useYn: 'Y',
          },
          message: null,
          errorCode: null,
        },
      ];
    });

    // when
    const res = await createLabelAttr(3, UPSERT);

    // then
    expect(capturedBody).toEqual(UPSERT);
    expect(res.attrId).toBe(20);
    expect(res.labelId).toBe(3);
  });

  it('updateLabelAttr가_PUT_manage_labels_id_attrs_attrId로_요청한다', async () => {
    // given
    let capturedUrl = '';
    mock.onPut('/manage/labels/3/attrs/20').reply((config) => {
      capturedUrl = config.url ?? '';
      return [
        200,
        {
          success: true,
          data: {
            attrId: 20,
            labelId: 3,
            name: '색상',
            inputType: 'RADIO',
            valuesJson: '["빨강"]',
            defaultVal: null,
            mutable: 'Y',
            sortNo: 1,
            useYn: 'Y',
          },
          message: null,
          errorCode: null,
        },
      ];
    });

    // when
    const res = await updateLabelAttr(3, 20, { ...UPSERT, inputType: 'RADIO' });

    // then
    expect(capturedUrl).toBe('/manage/labels/3/attrs/20');
    expect(res.inputType).toBe('RADIO');
  });

  it('deleteLabelAttr가_DELETE_요청하고_204를_에러없이_처리한다', async () => {
    // given
    mock.onDelete('/manage/labels/3/attrs/20').reply(204);

    // when / then
    await expect(deleteLabelAttr(3, 20)).resolves.toBeUndefined();
    expect(mock.history.delete[0].url).toBe('/manage/labels/3/attrs/20');
  });

  it('createLabelAttr_동일이름_중복이면_409_에러_throw', async () => {
    // given
    mock.onPost('/manage/labels/3/attrs').reply(409, {
      success: false,
      data: null,
      message: '이미 존재하는 속성명',
      errorCode: 'DUPLICATE_ATTR',
    });

    // when / then
    await expect(createLabelAttr(3, UPSERT)).rejects.toThrow();
  });
});
