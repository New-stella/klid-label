// R6 / API-195·API-196 — 「불러온 회차 세트」의 변환 계약.
//
// 이 세트는 확정 저장 요청 본문의 원천이다. 여기서 필드가 빠지면 저장은 200 으로 성공하는데 재조회에서
// 내용이 어긋나므로(특히 labelId — 저장 전에는 정상으로 보인다) 순수 함수 단위로 고정한다.

import { describe, expect, it } from 'vitest';

import type { Label } from '@/features/label/types';

import {
  applyDiscardToDraft,
  captureFrameIntoDraft,
  toLoadedDraft,
  toVideoSavePayload,
  unresolvedFrameCount,
} from '../loadedVersionDraft';
import type { VersionLabelsResponse } from '../types';

const loaded: VersionLabelsResponse = {
  rawSn: 9,
  version: 2,
  frames: [
    {
      srcSn: 51,
      frmNo: 0,
      dscdYn: 'N',
      lblVer: 7,
      resolved: true,
      items: [
        {
          id: 9001,
          lblTypeCd: 'BBOX',
          label: '사람',
          labelId: 12,
          points: [
            [10, 10],
            [50, 50],
          ],
          trackId: '7',
        },
      ],
    },
    { srcSn: 52, frmNo: 1, dscdYn: 'Y', lblVer: 3, resolved: false, items: [] },
  ],
};

function bboxLabel(overrides: Partial<Label> = {}): Label {
  return {
    id: '9001',
    serverId: 9001,
    frameNo: 0,
    classId: 12,
    labelId: 12,
    color: '#EF4444',
    className: '사람',
    source: 'MANUAL',
    shape: { type: 'BBOX', left: 1, top: 2, right: 3, bottom: 4 },
    trackId: null,
    lblSrcCd: null,
    ...overrides,
  } as Label;
}

describe('불러온 회차 세트', () => {
  it('응답을_세트로_옮기며_서버_순서를_바꾸지_않는다', () => {
    const draft = toLoadedDraft(loaded);

    expect(draft.version).toBe(2);
    expect(draft.frames.map((f) => f.srcSn)).toEqual([51, 52]);
    expect(draft.frames[1].resolved).toBe(false);
  });

  it('해석하지_못한_프레임_수를_숨기지_않는다', () => {
    expect(unresolvedFrameCount(toLoadedDraft(loaded))).toBe(1);
    expect(unresolvedFrameCount(null)).toBe(0);
  });

  it('API_196_요청은_labelId_를_보존한다', () => {
    // ★ labelId 가 빠지면 재조회 응답의 color/label 이 null 이 되어 트랙 해시색으로 낙하하고
    //   라벨명·속성 정의까지 끊긴다(저장 전에는 정상으로 보여 발견이 늦다).
    const payload = toVideoSavePayload(toLoadedDraft(loaded), undefined, []);

    expect(payload.frames[0].items[0]).toMatchObject({ id: 9001, labelId: 12, lblTypeCd: 'BBOX' });
    expect(payload.loadedVersion).toBe('2');
  });

  it('API_196_요청은_편집하지_않은_프레임까지_판번호와_함께_보낸다', () => {
    // 전수 검증이 성립하려면 편집하지 않은 프레임도 판번호를 실어야 한다(AC-008 ⑥).
    const payload = toVideoSavePayload(toLoadedDraft(loaded), undefined, []);

    expect(payload.frames.map((f) => [f.srcSn, f.lblVer])).toEqual([
      [51, 7],
      [52, 3],
    ]);
  });

  it('API_196_요청은_폐기상태를_함께_싣는다', () => {
    const payload = toVideoSavePayload(toLoadedDraft(loaded), undefined, []);

    expect(payload.frames.map((f) => f.dscdYn)).toEqual(['N', 'Y']);
  });

  it('현재_프레임의_캔버스_편집이_세트를_덮는다', () => {
    // 불러온 뒤 그 프레임을 고친 사용자의 편집이 저장에서 사라지면 안 된다.
    const payload = toVideoSavePayload(toLoadedDraft(loaded), 51, [
      bboxLabel({ serverId: undefined, id: 'tmp-1', labelId: 33, className: '차량' }),
    ]);

    expect(payload.frames[0].items).toHaveLength(1);
    expect(payload.frames[0].items[0]).toMatchObject({ id: null, labelId: 33, label: '차량' });
    // 판번호·폐기여부는 세트 값을 유지한다(캔버스가 들고 있지 않은 축이다).
    expect(payload.frames[0].lblVer).toBe(7);
  });

  it('세트에_없는_프레임은_저장_목록에_끼워_넣지_않는다', () => {
    // 판번호를 모르는 프레임을 넣으면 전수 검증의 근거가 없어진다.
    const draft = captureFrameIntoDraft(toLoadedDraft(loaded), 999, [bboxLabel()]);

    expect(draft.frames.map((f) => f.srcSn)).toEqual([51, 52]);
  });

  it('폐기_전환은_세트에_반영된다', () => {
    const draft = applyDiscardToDraft(toLoadedDraft(loaded), 51, 'Y');

    expect(draft.frames[0].dscdYn).toBe('Y');
    // 다른 프레임은 건드리지 않는다.
    expect(draft.frames[1].dscdYn).toBe('Y');
  });

  it('세트는_불변으로_다뤄진다 — 원본을_제자리에서_고치지_않는다', () => {
    const draft = toLoadedDraft(loaded);
    const next = applyDiscardToDraft(draft, 51, 'Y');

    expect(draft.frames[0].dscdYn).toBe('N');
    expect(next).not.toBe(draft);
  });
});
