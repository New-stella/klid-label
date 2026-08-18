// 온디맨드 자동 추적 결과 변환 — 트랙 단위 묶음 · 마스터 식별자 통과 · 미연결 제외 가드.
//
// 이 파일이 고정하는 계약:
//  - 수락·제외의 단위는 **트랙**이다(검출 하나하나가 아니다).
//  - 라벨 마스터 식별자(labelId)는 **응답값 그대로** 실린다 — 검출 클래스명으로 다시 찾지 않는다.
//    저장 직렬화까지 그대로 흘러가는지(사슬의 마지막 고리)도 함께 본다.
//  - 식별자가 비어 있는 검출은 반영하지 않고 제외 건수로 센다(값을 지어내지 않는다).
//  - 트래커 ID 가 없는 검출은 트랙 없는 단발 라벨이다(임의 발급하지 않는다).
//
// @design API-123, SCREEN-005, UC-034

import { describe, expect, it } from 'vitest';

import { serializeLabel } from '@/features/label/api';
import type { AutoTrackResponse } from '@/features/label/api/autoTrack';
import {
  buildAutoTrackReview,
  isEmptyReview,
  mergeGroupsBySrcSn,
} from '@/features/label/utils/autoTrackResult';

const FRAME_NOS = new Map([
  [300, 0],
  [301, 1],
  [302, 2],
]);

function res(frames: AutoTrackResponse['frames']): AutoTrackResponse {
  return { frames };
}

describe('자동 추적 결과 — 트랙 단위 묶음', () => {
  it('같은_트래커_객체는_여러_프레임에_걸쳐_한_묶음이_된다', () => {
    // given: 같은 객체(trackId=7)가 세 프레임에 검출됐다
    const response = res([
      { srcSn: 300, frameIndex: 0, detections: [det({ trackId: 7 })] },
      { srcSn: 301, frameIndex: 1, detections: [det({ trackId: 7 })] },
      { srcSn: 302, frameIndex: 2, detections: [det({ trackId: 7 })] },
    ]);

    // when
    const review = buildAutoTrackReview(response, FRAME_NOS);

    // then: 묶음은 1건이고 3프레임 3건을 담는다(검출 3건이 각각 고를 항목이 되지 않는다)
    expect(review.groups).toHaveLength(1);
    expect(review.groups[0].trackId).toBe('7');
    expect(review.groups[0].frameCount).toBe(3);
    expect(review.groups[0].labelCount).toBe(3);
  });

  it('서로_다른_트래커_객체는_각각의_묶음이_된다', () => {
    // given
    const response = res([
      { srcSn: 300, frameIndex: 0, detections: [det({ trackId: 7 }), det({ trackId: 8 })] },
      { srcSn: 301, frameIndex: 1, detections: [det({ trackId: 8 })] },
    ]);

    // when
    const review = buildAutoTrackReview(response, FRAME_NOS);

    // then
    expect(review.groups.map((g) => g.trackId)).toEqual(['7', '8']);
    expect(review.groups[1].labelCount).toBe(2);
  });

  it('트래커_ID가_없으면_트랙_없는_단발_라벨로_둔다_ID를_발급하지_않는다', () => {
    // given: trackId 미부여 검출 2건
    const response = res([
      { srcSn: 300, frameIndex: 0, detections: [det({ trackId: null }), det({ trackId: null })] },
    ]);

    // when
    const review = buildAutoTrackReview(response, FRAME_NOS);

    // then: 각각 자기 묶음이고 트랙 ID 는 비어 있다(0·'1' 같은 값을 지어내지 않는다)
    expect(review.groups).toHaveLength(2);
    expect(review.groups.map((g) => g.trackId)).toEqual([null, null]);
    const labels = Object.values(review.groups[0].bySrcSn).flat();
    expect(labels[0].trackId).toBeNull();
  });

  it('프레임_번호는_요청에_실어_보낸_프레임_사전에서_붙인다', () => {
    // given
    const response = res([{ srcSn: 302, frameIndex: 2, detections: [det({ trackId: 3 })] }]);

    // when
    const review = buildAutoTrackReview(response, FRAME_NOS);

    // then
    expect(Object.values(review.groups[0].bySrcSn).flat()[0].frameNo).toBe(2);
  });

  it('응답이_비어도_빈_결과를_돌려준다', () => {
    expect(isEmptyReview(buildAutoTrackReview(null, FRAME_NOS))).toBe(true);
    expect(isEmptyReview(buildAutoTrackReview(res([]), FRAME_NOS))).toBe(true);
    expect(isEmptyReview(buildAutoTrackReview(res(null), FRAME_NOS))).toBe(true);
  });
});

describe('자동 추적 결과 — 라벨 마스터 식별자', () => {
  it('응답의_labelId가_그대로_라벨에_실린다', () => {
    // given: 서버가 검출 클래스 축으로 해석해 실어 준 마스터 PK
    const response = res([
      { srcSn: 300, frameIndex: 0, detections: [det({ trackId: 7, labelId: 42 })] },
    ]);

    // when
    const review = buildAutoTrackReview(response, FRAME_NOS);

    // then
    expect(review.groups[0].labelId).toBe(42);
    expect(Object.values(review.groups[0].bySrcSn).flat()[0].labelId).toBe(42);
  });

  it('저장_직렬화까지_labelId가_그대로_전달된다', () => {
    // given: 사슬의 마지막 고리 — 저장 payload 에서 마스터 연결이 끊기면 색·라벨명·속성이
    //        함께 끊기고, 저장 전에는 정상으로 보이다가 재조회 후에야 드러난다.
    const review = buildAutoTrackReview(
      res([{ srcSn: 300, frameIndex: 0, detections: [det({ trackId: 7, labelId: 42 })] }]),
      FRAME_NOS,
    );
    const label = Object.values(review.groups[0].bySrcSn).flat()[0];

    // when
    const payload = serializeLabel(label) as { labelId: number | null; label: string };

    // then
    expect(payload.labelId).toBe(42);
    expect(payload.label).toBe('person');
  });

  it('labelId가_비어있는_검출은_반영하지_않고_제외_건수로_센다', () => {
    // given: 대응 마스터 없음 · 검출 클래스 매핑 미지정
    const response = res([
      {
        srcSn: 300,
        frameIndex: 0,
        detections: [det({ trackId: 7, labelId: null }), det({ trackId: 8, labelId: 5 })],
      },
    ]);

    // when
    const review = buildAutoTrackReview(response, FRAME_NOS);

    // then: 값을 지어내 라벨을 만들지 않는다
    expect(review.groups).toHaveLength(1);
    expect(review.groups[0].labelId).toBe(5);
    expect(review.unlinkedCount).toBe(1);
    expect(review.detectionCount).toBe(2);
  });

  it('labelId가_수가_아니면_미연결로_본다', () => {
    // given: 구버전·손상 응답 방어
    const response = res([
      {
        srcSn: 300,
        frameIndex: 0,
        // 서버 계약 밖 값이 와도 마스터를 추측하지 않는다.
        detections: [{ label: 'person', points: [0, 0, 1, 1], score: 0.5, trackId: 1, labelId: undefined as unknown as number }],
      },
    ]);

    // when
    const review = buildAutoTrackReview(response, FRAME_NOS);

    // then
    expect(review.groups).toHaveLength(0);
    expect(review.unlinkedCount).toBe(1);
  });
});

describe('자동 추적 결과 — 수락·제외', () => {
  it('수락한_묶음만_프레임별_라벨로_합쳐진다', () => {
    // given: 두 트랙
    const review = buildAutoTrackReview(
      res([
        { srcSn: 300, frameIndex: 0, detections: [det({ trackId: 7 }), det({ trackId: 8 })] },
        { srcSn: 301, frameIndex: 1, detections: [det({ trackId: 8 })] },
      ]),
      FRAME_NOS,
    );

    // when: 트랙 8 만 수락(트랙 7 은 제외)
    const merged = mergeGroupsBySrcSn(review.groups, new Set(['track:8']));

    // then: 제외한 묶음의 라벨은 어디에도 들어가지 않는다
    expect(Object.keys(merged).map(Number).sort()).toEqual([300, 301]);
    expect(merged[300]).toHaveLength(1);
    expect(merged[300][0].trackId).toBe('8');
    expect(merged[301]).toHaveLength(1);
  });

  it('아무것도_수락하지_않으면_빈_결과다', () => {
    const review = buildAutoTrackReview(
      res([{ srcSn: 300, frameIndex: 0, detections: [det({ trackId: 7 })] }]),
      FRAME_NOS,
    );

    expect(mergeGroupsBySrcSn(review.groups, new Set())).toEqual({});
  });
});

/** 검출 1건 — 기본은 마스터 연결된 person 박스. */
function det(over: { trackId: number | null; labelId?: number | null }) {
  return {
    label: 'person',
    points: [10, 20, 30, 40],
    score: 0.9,
    trackId: over.trackId,
    labelId: over.labelId === undefined ? 11 : over.labelId,
  };
}
