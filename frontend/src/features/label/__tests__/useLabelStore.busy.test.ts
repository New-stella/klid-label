// Phase 1 — busy 상태 단일화 + 취소 세대 토큰 (store 액션 단위).
//
// 라벨링 화면의 장시간 작업(AI 탐지/분할/추적, 라벨 저장·불러오기)은 store 의 busy 하나로만
// 표현된다. 이 파일은 store 액션(beginBusy/endBusy/cancelBusy/isTokenAlive)의 계약을 고정한다.
import { beforeEach, describe, expect, it } from 'vitest';

import { useLabelStore } from '@/stores/useLabelStore';
import { LabelSource, type Label } from '@/features/label/types';

function makeLabel(id: string): Label {
  return {
    id,
    frameNo: 0,
    classId: 1,
    className: '사람',
    source: LabelSource.MANUAL,
    shape: { type: 'BBOX', left: 0, top: 0, right: 10, bottom: 10 },
  };
}

beforeEach(() => {
  // 모듈 싱글턴 store — 세대 카운터까지 결정론적으로 초기화한다(토큰 0 케이스 검증용).
  useLabelStore.getState().reset();
  useLabelStore.setState({ busy: null, busyGeneration: 0 });
});

describe('useLabelStore busy slice', () => {
  it('첫_토큰이_0이어도_유효한_시작으로_처리된다', () => {
    // given: 세션 첫 작업(세대 0)
    // when
    const begun = useLabelStore.getState().beginBusy('AI_DETECT', { srcSn: 11 });

    // then: falsy(0) 토큰이어도 판별 유니온의 ok 로 성공을 판정할 수 있다.
    expect(begun.ok).toBe(true);
    if (!begun.ok) throw new Error('unreachable');
    expect(begun.token).toBe(0);
    expect(useLabelStore.getState().isTokenAlive(begun.token)).toBe(true);
  });

  it('busy_진행중_다른_작업_시작_요청은_거부된다', () => {
    // given
    const first = useLabelStore.getState().beginBusy('AI_DETECT', { srcSn: 11 });
    expect(first.ok).toBe(true);

    // when
    const second = useLabelStore.getState().beginBusy('SAVE', { srcSn: 11 });

    // then
    expect(second.ok).toBe(false);
    expect(useLabelStore.getState().busy?.kind).toBe('AI_DETECT');
  });

  it('busy에_실제로_읽히는_필드만_담긴다', () => {
    // when
    useLabelStore.getState().beginBusy('AI_TRACK', { srcSn: 77, maxDurationMs: 90_000 });

    // then: 소비처가 실재하는 필드만 보관한다 — 아무도 보지 않는 필드를 남기면 «영상 경계까지
    //   판정한다» 같은 착각을 만든다(죽은 필드 금지).
    //   · srcSn        → 프레임 스코프 판정(busyAppliesTo)
    //   · maxDurationMs → 진행 오버레이의 «최대 N초» 표시(BusyOverlay 의 limitMs).
    //     ⚠ 화면이 이 값을 다시 계산하지 않는 것이 요점이다 — 상한은 작업 종류·전송 방식(한 번에
    //     보내는가, 나눠 보내는가)마다 실행 시점에만 확정되므로, 잠금을 건 주체가 기록한 값을
    //     그대로 읽어야 «적용된 상한» 과 «표시된 상한» 이 갈리지 않는다.
    const busy = useLabelStore.getState().busy;
    expect(busy?.srcSn).toBe(77);
    expect(busy?.maxDurationMs).toBe(90_000);
    expect(Object.keys(busy ?? {}).sort()).toEqual(
      ['kind', 'srcSn', 'startedAt', 'maxDurationMs', 'token'].sort(),
    );
    expect(typeof busy?.startedAt).toBe('number');
  });

  it('endBusy는_다른_토큰으로_호출하면_해제하지_않는다', () => {
    // given
    const begun = useLabelStore.getState().beginBusy('SAVE', { srcSn: 11 });
    if (!begun.ok) throw new Error('unreachable');

    // when: 이미 죽은(다른) 토큰으로 해제 시도
    useLabelStore.getState().endBusy(begun.token + 999);

    // then: 현재 busy 는 그대로 유지
    expect(useLabelStore.getState().busy).not.toBeNull();
    expect(useLabelStore.getState().isTokenAlive(begun.token)).toBe(true);
  });

  it('endBusy는_같은_토큰으로_여러번_호출해도_안전하다', () => {
    // given
    const begun = useLabelStore.getState().beginBusy('SAVE', { srcSn: 11 });
    if (!begun.ok) throw new Error('unreachable');

    // when
    useLabelStore.getState().endBusy(begun.token);
    useLabelStore.getState().endBusy(begun.token);

    // then
    expect(useLabelStore.getState().busy).toBeNull();
    expect(useLabelStore.getState().isTokenAlive(begun.token)).toBe(false);
  });

  it('cancelBusy는_busy가_없을_때_호출해도_안전하다', () => {
    // given: busy 없음
    expect(useLabelStore.getState().busy).toBeNull();

    // when / then: 예외 없이 no-op
    expect(() => useLabelStore.getState().cancelBusy()).not.toThrow();
    expect(useLabelStore.getState().busy).toBeNull();
  });

  it('취소하면_이전_토큰은_죽고_새_작업을_즉시_시작할_수_있다', () => {
    // given
    const first = useLabelStore.getState().beginBusy('AI_SEGMENT', { srcSn: 11 });
    if (!first.ok) throw new Error('unreachable');

    // when
    useLabelStore.getState().cancelBusy();

    // then: 이전 토큰은 죽고(결과 폐기), 즉시 재시작 가능(유령 잠금 없음)
    expect(useLabelStore.getState().isTokenAlive(first.token)).toBe(false);
    const second = useLabelStore.getState().beginBusy('AI_SEGMENT', { srcSn: 11 });
    expect(second.ok).toBe(true);
    if (!second.ok) throw new Error('unreachable');
    expect(second.token).not.toBe(first.token);
  });

  it('reset_호출시_진행중_busy가_취소되고_토큰이_죽는다', () => {
    // given
    const begun = useLabelStore.getState().beginBusy('AI_DETECT', { srcSn: 11 });
    if (!begun.ok) throw new Error('unreachable');

    // when: 언마운트/비식별 신고 성공 등으로 전체 초기화
    useLabelStore.getState().reset();

    // then: 이후 도착한 응답은 폐기 대상
    expect(useLabelStore.getState().busy).toBeNull();
    expect(useLabelStore.getState().isTokenAlive(begun.token)).toBe(false);
  });

  it('setLabels는_같은_프레임_재조회에서_busy를_건드리지_않는다', () => {
    // given
    const begun = useLabelStore.getState().beginBusy('SAVE', { srcSn: 11 });
    if (!begun.ok) throw new Error('unreachable');

    // when: 같은 프레임 백그라운드 재조회
    useLabelStore.getState().setLabels([makeLabel('a')]);

    // then: 진행 중 저장은 유지된다
    expect(useLabelStore.getState().isTokenAlive(begun.token)).toBe(true);
  });
});
