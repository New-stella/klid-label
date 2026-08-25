import { describe, expect, it } from 'vitest';

import { presetSchema } from '@/features/preset/schemas';

/**
 * 프리셋 스키마 — 입력 축은 <b>이벤트유형 + 라벨</b> 둘뿐이다(V17).
 *
 * 구 스키마의 `name`(필수 1~64자)·`description`(0~500자) 검증은 폐기됐고, `eventTypeCd` 는
 * '선택(빈 문자열=미매핑)' 에서 <b>필수</b>로 반전됐다.
 */
describe('preset schemas (이벤트 + 라벨)', () => {
  const base = { eventTypeCd: 'EV02000101', labelIds: [1, 2, 3] };

  it('이벤트유형과_라벨만_있으면_통과한다', () => {
    expect(presetSchema.safeParse(base).success).toBe(true);
  });

  it('★이름과_설명은_더_이상_검증하지_않고_결과에도_남지_않는다', () => {
    // given: 구 계약의 필드를 실어 보내도
    const withLegacy = { ...base, name: '화재 기본', description: '설명' };

    // when
    const result = presetSchema.safeParse(withLegacy);

    // then: 파싱은 통과하고(이름 필수 검증 폐기) 결과 객체에는 두 필드가 남지 않는다
    //   — 남으면 api.toPayload 를 우회해 서버로 새어 나갈 여지가 생긴다.
    expect(result.success).toBe(true);
    if (result.success) {
      expect(result.data).not.toHaveProperty('name');
      expect(result.data).not.toHaveProperty('description');
    }
  });

  it('★이름이_없어도_통과한다_구_필수검증_폐기', () => {
    expect(presetSchema.safeParse(base).success).toBe(true);
  });

  // ── 이벤트유형(필수) ────────────────────────────────────────────────

  it('★이벤트유형_빈_문자열_거부_구_미매핑_허용_폐기', () => {
    // 이벤트에 걸리지 않은 프리셋은 어느 영상에도 매칭되지 않는 죽은 행이다.
    const bad = { ...base, eventTypeCd: '' };
    const result = presetSchema.safeParse(bad);
    expect(result.success).toBe(false);
    if (!result.success) {
      expect(result.error.issues.some((iss) => /이벤트유형을 선택/.test(iss.message))).toBe(true);
    }
  });

  it('이벤트유형_누락_거부', () => {
    expect(presetSchema.safeParse({ labelIds: [1] }).success).toBe(false);
  });

  it('이벤트유형_잘못된_형식_거부', () => {
    expect(presetSchema.safeParse({ ...base, eventTypeCd: 'invalid-event' }).success).toBe(false);
  });

  it('이벤트유형_20자는_허용_21자는_거부_컬럼폭_정합', () => {
    // 진실원: 코드값 표준도메인 VARCHAR(20) = 컬럼 LS_LABEL_PRESET.EVNT_TYPE_CD.
    // FE 상한이 32 였던 동안 21~32자는 FE 를 통과해 전송된 뒤 BE 400 으로 되돌아왔다.
    expect(presetSchema.safeParse({ ...base, eventTypeCd: 'A'.repeat(20) }).success).toBe(true);
    expect(presetSchema.safeParse({ ...base, eventTypeCd: 'A'.repeat(21) }).success).toBe(false);
  });

  // ── 라벨(1~20) ─────────────────────────────────────────────────────

  it('labelId_1개_이상_요구', () => {
    const result = presetSchema.safeParse({ ...base, labelIds: [] });
    expect(result.success).toBe(false);
    if (!result.success) {
      expect(result.error.issues.some((iss) => /1개 이상/.test(iss.message))).toBe(true);
    }
  });

  it('labelId_최대_20개_제한', () => {
    const result = presetSchema.safeParse({
      ...base,
      labelIds: Array.from({ length: 21 }, (_, i) => i + 1),
    });
    expect(result.success).toBe(false);
    if (!result.success) {
      expect(result.error.issues.some((iss) => /최대 20/.test(iss.message))).toBe(true);
    }
  });

  it('labelId_음수_거부', () => {
    expect(presetSchema.safeParse({ ...base, labelIds: [-1] }).success).toBe(false);
  });

  it('labelId_0_거부', () => {
    expect(presetSchema.safeParse({ ...base, labelIds: [0] }).success).toBe(false);
  });

  it('labelId_소수_거부', () => {
    expect(presetSchema.safeParse({ ...base, labelIds: [1.5] }).success).toBe(false);
  });
});
