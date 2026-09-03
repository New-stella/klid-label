// 회귀 가드 — 증강 요청의 **귀결 판정**. 화면의 모든 분기(배지·후속 진입·내려받기)가 이 한
// 함수로 갈리므로, 여기가 틀리면 「대기 중인데 내려받기가 뜬다」 같은 결함이 통째로 열린다.
//
// ★ 계약이 못박은 것을 값 축으로 고정한다 — *"화면은 요청 상태 값(`augSttsCd`) 자체로 분기하지
//   말고 `resultReady` 를 쓴다"*. 형식 단언(「셋 중 하나를 돌려준다」)만 두면 두 값이 서로
//   뒤바뀌어도 통과한다.
//
// ⚠ mutation 확인: `resolvePortalAugmentOutcome` 이 `augSttsCd` 를 보게 만들거나 실패 판정을
//   fail-open 으로 바꾸면 아래 케이스가 FAIL 해야 한다. (실제로 확인함 — 보고 참조)
import { describe, expect, it } from 'vitest';

import {
  isPortalAugmentResultReady,
  PORTAL_AUGMENT_OUTCOME_BADGE,
  resolvePortalAugmentOutcome,
} from '../outcome';

describe('증강 요청 귀결 판정', () => {
  it('결과가_도착했으면_ready_다', () => {
    expect(resolvePortalAugmentOutcome({ resultReady: true })).toBe('ready');
    expect(isPortalAugmentResultReady({ resultReady: true })).toBe(true);
  });

  it('실패_사유가_실려_왔고_아직_도착하지_않았으면_failed_다', () => {
    expect(resolvePortalAugmentOutcome({ resultReady: false, failRsnCn: '외부 위탁 거절' })).toBe(
      'failed',
    );
  });

  it('도착도_실패도_아니면_waiting_이다_fail_closed', () => {
    expect(resolvePortalAugmentOutcome({ resultReady: false })).toBe('waiting');
    // 목록 계약에는 실패 사유가 없어 undefined 로 들어온다 — 없는 값으로 실패를 지어내지 않는다.
    expect(resolvePortalAugmentOutcome({ resultReady: false, failRsnCn: undefined })).toBe(
      'waiting',
    );
    expect(resolvePortalAugmentOutcome({ resultReady: false, failRsnCn: null })).toBe('waiting');
    // 빈 문자열·공백만 있는 사유는 사유가 아니다(그것으로 실패라고 말하면 근거 없는 단정이다).
    expect(resolvePortalAugmentOutcome({ resultReady: false, failRsnCn: '   ' })).toBe('waiting');
    // 응답에 아무 축도 없을 때도 대기다 — 모르는 상태를 도착으로 읽으면 게이트가 열린다.
    expect(resolvePortalAugmentOutcome({})).toBe('waiting');
  });

  it('★도착이_실패보다_앞선다_이미_도착한_요청은_사유가_남아_있어도_ready_다', () => {
    expect(resolvePortalAugmentOutcome({ resultReady: true, failRsnCn: '옛 실패' })).toBe('ready');
  });

  it('★요청_상태_원문은_판정에_쓰이지_않는다_값역이_열려_있다', () => {
    // 같은 도착 여부라면 상태 원문이 무엇이든 결과가 같아야 한다. 하나라도 갈리면 화면이
    // 서버 값역에 묶인 것이고, 서버가 값을 넓히는 순간 정상 값을 화면이 먼저 막는다.
    const states = ['PENDING', 'ACCEPTED', 'REJECTED', 'CANCELED', '무엇이든', ''];
    for (const augSttsCd of states) {
      const row = { augSttsCd, resultReady: false } as Record<string, unknown>;
      expect(resolvePortalAugmentOutcome(row)).toBe('waiting');
      expect(resolvePortalAugmentOutcome({ ...row, resultReady: true })).toBe('ready');
    }
  });

  it('★배지_표기가_확정값으로_고정된다_톤_키와_한글_라벨_쌍', () => {
    // 형식(「셋 다 라벨이 있다」)만 보면 값이 서로 뒤바뀌어도 통과한다 — 쌍을 그대로 못박는다.
    expect(PORTAL_AUGMENT_OUTCOME_BADGE.waiting).toEqual({
      badgeStatus: 'PENDING',
      label: '결과 대기 중',
    });
    expect(PORTAL_AUGMENT_OUTCOME_BADGE.ready).toEqual({
      badgeStatus: 'COMPLETED',
      label: '결과 도착',
    });
    expect(PORTAL_AUGMENT_OUTCOME_BADGE.failed).toEqual({
      badgeStatus: 'FAILED',
      label: '실패',
    });
  });
});
