// 비식별 누락 신고 불가 사유 판정 — 단일 판정 지점의 순수 함수 테스트.
//
// 화면(라벨링·마킹)이 각자 조건을 이어붙이지 않고 이 함수에 위임하므로, 사유가 늘거나 문구가 바뀔 때
// 여기 하나만 고치면 두 화면이 함께 따라온다. 그 계약을 이 테스트가 고정한다.

import { describe, expect, it } from 'vitest';

import { resolveDeidentReportUnsupportedReason } from '../utils/deidentReportEligibility';

describe('resolveDeidentReportUnsupportedReason', () => {
  it('검수가_승인된_영상은_사유를_돌려준다', () => {
    expect(resolveDeidentReportUnsupportedReason({ reviewSttsCd: 'APPROVED' })).toBe(
      '검수가 완료된 영상은 비식별 누락을 신고할 수 없습니다',
    );
  });

  it('파생영상은_사유를_돌려준다', () => {
    expect(resolveDeidentReportUnsupportedReason({ derivative: true })).toBe(
      '증강·해상도 변환으로 만든 파생영상이라 이 화면에서는 비식별 재처리를 요청할 수 없습니다.',
    );
  });

  it('두_사유가_겹치면_파생영상_사유가_우선한다', () => {
    // 어느 쪽이든 결과(비활성)는 같지만, 문구가 실행마다 흔들리지 않도록 순서를 못박는다.
    expect(
      resolveDeidentReportUnsupportedReason({ derivative: true, reviewSttsCd: 'APPROVED' }),
    ).toContain('파생영상');
  });

  it('미승인_비파생_영상은_막지_않는다', () => {
    expect(
      resolveDeidentReportUnsupportedReason({ derivative: false, reviewSttsCd: 'PENDING' }),
    ).toBeUndefined();
  });

  it('영상_정보를_아직_모르면_막지_않는다 — 로딩_구간마다_버튼이_잠기지_않게', () => {
    // 이 창은 BE 412 + 컴포넌트 안내(안전망)가 받는다.
    expect(resolveDeidentReportUnsupportedReason(undefined)).toBeUndefined();
    expect(resolveDeidentReportUnsupportedReason(null)).toBeUndefined();
    expect(resolveDeidentReportUnsupportedReason({})).toBeUndefined();
  });
});
