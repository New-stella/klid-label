// 회귀 가드 — 반려 사유 합성 형식은 바뀌지 않는다. [@design SCREEN-019]
//
// 왜 별도 가드인가: 검수 메모 패널에서 «서버 등록 이슈 목록»을 걷어냈다. 그 작업이 손대면 안 되는
//   것이 이 합성 함수다 — 여기서 만든 문자열이 **서버에 그대로 저장**되므로, 구분자·순서·괄호
//   표기가 조금이라도 달라지면 이번 변경 전후의 반려 기록이 서로 다른 모양으로 남는다.
//
// 기존 케이스(ReviewPage.test.tsx)는 `toContain` 이라 «순서가 뒤바뀌거나 빈 줄 수가 달라지는»
//   변형을 통과시킨다. 이 가드는 **완성된 문자열 전체를 정확히** 고정한다.

import { describe, expect, it } from 'vitest';

import { composeRejectReason } from '@/pages/ReviewPage';
import type { PendingIssue } from '@/features/review/store/useReviewSelectionStore';

const TS = '2026-08-27T10:00:00Z';

function issue(text: string, labelId: number | null): PendingIssue {
  return { labelId, text, ts: TS };
}

describe('composeRejectReason 형식 고정', () => {
  it('사용자입력_전체의견_이슈가_이_순서로_빈줄_하나씩_두고_이어붙는다', () => {
    const result = composeRejectReason('사유 본문', '전체 의견 본문', [
      issue('첫 지적', 11),
      issue('둘째 지적', null),
    ]);

    expect(result).toBe(
      [
        '사유 본문',
        '',
        '[전체 의견]',
        '전체 의견 본문',
        '',
        '[이슈 2건]',
        '- 첫 지적 (#11)',
        '- 둘째 지적',
      ].join('\n'),
    );
  });

  it('전체의견이_비면_그_블록_자체가_빠진다', () => {
    expect(composeRejectReason('사유 본문', '   ', [issue('지적', 7)])).toBe(
      ['사유 본문', '', '[이슈 1건]', '- 지적 (#7)'].join('\n'),
    );
  });

  it('이슈가_0건이면_이슈_블록이_빠진다', () => {
    expect(composeRejectReason('사유 본문', '의견', [])).toBe(
      ['사유 본문', '', '[전체 의견]', '의견'].join('\n'),
    );
  });

  it('사용자입력만_있으면_그_문자열만_남는다', () => {
    expect(composeRejectReason('  사유 본문  ', '', [])).toBe('사유 본문');
  });
});
