import { describe, expect, it } from 'vitest';

import { augTypeLabel } from '../augTypeLabel';

describe('augTypeLabel', () => {
  it('augTypeLabel_유틸_각값_라벨매핑', () => {
    // 신규 증강 위탁 단일값
    expect(augTypeLabel('AUGMENT')).toBe('증강 AI');
    // 그랜드퍼더링된 구 3종 — BE 가 백필하지 않아 기존 파생본에 그대로 남아 있다(ADR-059)
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

  it('구_3종은_신규_단일값과_구분되는_문구로_표시된다', () => {
    // 지우면 기존 이력·결과 화면에서 전부 '증강' 폴백으로 뭉개져 어떤 파생인지 알 수 없게 된다.
    const legacy = ['WINTER', 'NIGHT', 'RAIN'].map(augTypeLabel);
    expect(new Set(legacy).size).toBe(3);
    expect(legacy).not.toContain('증강');
    expect(legacy).not.toContain(augTypeLabel('AUGMENT'));
  });

  it('미지의_코드는_기술코드_노출없이_폴백', () => {
    // 기술코드가 화면에 새지 않도록 폴백만 노출
    expect(augTypeLabel('UNKNOWN_CODE')).toBe('증강');
    expect(augTypeLabel('RESL_FUTURE')).toBe('해상도 파생');
  });
});
