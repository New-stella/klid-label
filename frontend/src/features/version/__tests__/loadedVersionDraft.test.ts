// R6 / API-195·API-196(v4) — 「불러온 회차 세트」의 변환 계약.
//
// 확정 저장에 보내는 것은 **회차 번호 + 전 프레임 판번호 + 고친 프레임**뿐이다. 본문 전량을 되보내지
// 않는 이유는 서버가 회차 스냅샷을 직접 읽어 적용하기 때문이며, 그 덕분에 생산이력과 trackId 가 회차에
// 적힌 대로 살아남는다. 여기서는 그 페이로드 조립과 "고쳤는가" 판정을 순수 함수 단위로 고정한다.

import { describe, expect, it } from 'vitest';

import type { Label } from '@/features/label/types';

import {
  captureFrameIntoDraft,
  editedFrameCount,
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

/** 회차 본문(51번 프레임)과 **같은** 라벨 — 직렬화 결과가 일치해야 "고치지 않음"으로 판정된다. */
function unchangedLabel(): Label {
  return {
    id: '9001',
    serverId: 9001,
    frameNo: 0,
    classId: 12,
    labelId: 12,
    color: '#EF4444',
    className: '사람',
    source: 'MANUAL',
    shape: { type: 'BBOX', left: 10, top: 10, right: 50, bottom: 50 },
    trackId: '7',
    lblSrcCd: null,
  } as Label;
}

function movedLabel(): Label {
  return { ...unchangedLabel(), shape: { type: 'BBOX', left: 11, top: 10, right: 50, bottom: 50 } } as Label;
}

describe('불러온 회차 세트', () => {
  it('응답을_세트로_옮기며_서버_순서를_바꾸지_않는다', () => {
    const draft = toLoadedDraft(loaded);

    expect(draft.version).toBe(2);
    expect(draft.frames.map((f) => f.srcSn)).toEqual([51, 52]);
    expect(draft.frames[1].resolved).toBe(false);
    // 불러온 직후에는 고친 것이 없다.
    expect(editedFrameCount(draft)).toBe(0);
  });

  it('해석하지_못한_프레임_수를_숨기지_않는다', () => {
    expect(unresolvedFrameCount(toLoadedDraft(loaded))).toBe(1);
    expect(unresolvedFrameCount(null)).toBe(0);
  });

  it('확정_저장은_회차_번호와_전_프레임_판번호를_보낸다', () => {
    const payload = toVideoSavePayload(toLoadedDraft(loaded), undefined, []);

    expect(payload.loadedVersion).toBe(2);
    // 전 프레임을 덮지 않으면 서버가 400 이다 — 편집하지 않은 프레임도 판번호를 실어야 한다.
    expect(payload.frameVersions).toEqual([
      { srcSn: 51, lblVer: 7 },
      { srcSn: 52, lblVer: 3 },
    ]);
  });

  it('본문_전량을_되보내지_않는다 — 고친_것이_없으면_edits_가_빈다', () => {
    // 서버가 회차 스냅샷을 직접 읽으므로 클라이언트가 본문을 주장하지 않는다(생산이력 보존의 근거).
    const payload = toVideoSavePayload(toLoadedDraft(loaded), undefined, []);

    expect(payload.edits).toEqual([]);
  });

  it('고친_프레임만_edits_에_실린다', () => {
    const draft = captureFrameIntoDraft(toLoadedDraft(loaded), 51, [movedLabel()]);

    const payload = toVideoSavePayload(draft, undefined, []);
    expect(payload.edits).toHaveLength(1);
    expect(payload.edits[0].srcSn).toBe(51);
    // ★ labelId 를 잃으면 저장 후 라벨 마스터 조인이 끊겨 색·라벨명·속성 정의가 함께 끊긴다.
    expect(payload.edits[0].items[0]).toMatchObject({ id: 9001, labelId: 12, trackId: '7' });
  });

  it('값이_그대로면_edits_에_실리지_않는다 — 방문만_한_프레임을_수정으로_기록하지_않는다', () => {
    const draft = captureFrameIntoDraft(toLoadedDraft(loaded), 51, [unchangedLabel()]);

    expect(editedFrameCount(draft)).toBe(0);
    expect(toVideoSavePayload(draft, undefined, []).edits).toEqual([]);
  });

  it('고쳤다가_되돌리면_edits_에서_빠진다', () => {
    let draft = captureFrameIntoDraft(toLoadedDraft(loaded), 51, [movedLabel()]);
    expect(editedFrameCount(draft)).toBe(1);

    draft = captureFrameIntoDraft(draft, 51, [unchangedLabel()]);

    expect(editedFrameCount(draft)).toBe(0);
  });

  it('폐기만_토글한_프레임도_edits_에_실린다', () => {
    // ⚠ 본문이 그대로여도 폐기 축만 바뀌면 편집이다 — 안 실으면 화면에는 바뀐 것처럼 보이는데
    //   저장되지 않는다.
    const draft = captureFrameIntoDraft(toLoadedDraft(loaded), 51, [unchangedLabel()], 'Y');

    const payload = toVideoSavePayload(draft, undefined, []);
    expect(payload.edits).toHaveLength(1);
    expect(payload.edits[0].dscdYn).toBe('Y');
  });

  it('라벨을_모두_지운_프레임도_편집으로_실린다 — 빈_배열은_전량_삭제다', () => {
    const draft = captureFrameIntoDraft(toLoadedDraft(loaded), 51, []);

    const payload = toVideoSavePayload(draft, undefined, []);
    expect(payload.edits).toHaveLength(1);
    expect(payload.edits[0].items).toEqual([]);
  });

  it('현재_프레임의_편집은_저장_직전에_판정된다', () => {
    // 프레임을 떠나지 않고 바로 저장한 경우 — 캔버스 내용이 payload 에 들어가야 한다.
    const payload = toVideoSavePayload(toLoadedDraft(loaded), 51, [movedLabel()]);

    expect(payload.edits).toHaveLength(1);
    expect(payload.edits[0].srcSn).toBe(51);
  });

  it('세트에_없는_프레임은_편집으로_기록하지_않는다', () => {
    // 판번호를 모르는 프레임을 편집 목록에 넣으면 전수 검증의 근거가 없어진다(서버도 404).
    const draft = captureFrameIntoDraft(toLoadedDraft(loaded), 999, [movedLabel()]);

    expect(editedFrameCount(draft)).toBe(0);
  });

  it('세트는_불변으로_다뤄진다 — 원본을_제자리에서_고치지_않는다', () => {
    const draft = toLoadedDraft(loaded);
    const next = captureFrameIntoDraft(draft, 51, [movedLabel()]);

    expect(editedFrameCount(draft)).toBe(0);
    expect(next).not.toBe(draft);
    // 회차 본문(기준선)은 그대로 남아야 한다 — 덮어쓰면 이후 비교가 항상 "변경 없음"이 된다.
    expect(next.frames[0].items[0].id).toBe(9001);
  });
});
