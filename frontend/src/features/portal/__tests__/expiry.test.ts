// 포털 만료 예정일 표기 판정 — 단일 판정 지점 회귀 가드. @design SCREEN-028, SCREEN-033
//
// ★ 만료가 **없는 상태**와 **값이 있는 상태**를 구분하는 것이 이 함수의 전부다. 없는 것을 있는 것처럼
//   (또는 그 반대로) 그리면 사용자는 삭제 예정을 잘못 안다.

import { describe, expect, it } from 'vitest';

import { formatExpiryDate } from '../expiry';

describe('formatExpiryDate', () => {
  it('날짜까지만_남기고_시각은_버린다', () => {
    // given / when / then: 사양이 날짜 표기까지만 요구한다
    expect(formatExpiryDate('2026-06-08T10:30:45')).toBe('2026-06-08');
  });

  it('만료가_없으면_null_이다', () => {
    // given / when / then: 호출측이 표기를 통째로 생략할 수 있게 null 로 돌려준다
    expect(formatExpiryDate(null)).toBeNull();
    expect(formatExpiryDate(undefined)).toBeNull();
  });

  it('날짜로_읽히지_않는_값은_지어내지_않고_null_이다', () => {
    // given / when / then: 깨진 값을 그럴듯한 날짜로 보정하면 없는 사실을 만들어낸다
    expect(formatExpiryDate('')).toBeNull();
    expect(formatExpiryDate('not-a-date')).toBeNull();
    expect(formatExpiryDate('2026/06/08')).toBeNull();
  });

  it('시간대_변환을_하지_않는다', () => {
    // given: 자정 직후 값 — Date 로 파싱해 지역 시간대로 옮기면 하루가 밀린다
    // when / then: 서버가 준 로컬 일시의 날짜 부분을 그대로 쓴다
    expect(formatExpiryDate('2026-06-08T00:00:00')).toBe('2026-06-08');
    expect(formatExpiryDate('2026-06-08T23:59:59')).toBe('2026-06-08');
  });
});
