import { describe, expect, it } from 'vitest';

import { augTypeLabel } from '../augTypeLabel';

describe('augTypeLabel', () => {
  it('augTypeLabel_유틸_각값_라벨매핑', () => {
    // 외부 증강 3종
    expect(augTypeLabel('WINTER')).toBe('겨울');
    expect(augTypeLabel('NIGHT')).toBe('야간');
    expect(augTypeLabel('RAIN')).toBe('비');
    // 해상도 파생 3종 — resolutionDerivativeLabel 재사용
    expect(augTypeLabel('RESL_1080P')).toBe('해상도 1080p');
    expect(augTypeLabel('RESL_720P')).toBe('해상도 720p');
    expect(augTypeLabel('RESL_480P')).toBe('해상도 480p');
    // null/undefined → '증강' 폴백
    expect(augTypeLabel(null)).toBe('증강');
    expect(augTypeLabel(undefined)).toBe('증강');
  });

  it('미지의_코드는_기술코드_노출없이_폴백', () => {
    // 기술코드가 화면에 새지 않도록 폴백만 노출
    expect(augTypeLabel('UNKNOWN_CODE')).toBe('증강');
    expect(augTypeLabel('RESL_FUTURE')).toBe('해상도 파생');
  });
});
