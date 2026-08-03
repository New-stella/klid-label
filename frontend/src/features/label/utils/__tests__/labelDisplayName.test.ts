// 2026-08-03 사용자 재확정 — **라벨명은 라벨 마스터에 등록된 이름을 그대로 표시한다.**
//
// 직전 커밋(d8a7a2cc)의 "한글 우선 사전 치환"(COCO_LABEL_KO + LABEL_CLASS_DEFS)은 폐기됐다.
// 코드 사전은 라벨 마스터(LS_LABEL)와 어긋나는 두 번째 진실원이 되고, 사전에 있는 라벨만
// 한글이라 화면이 오히려 뒤섞이기 때문. 한글로 보이길 원하면 마스터에 한글로 등록한다.
//
// 아래 테스트는 그 결정의 **회귀 가드**다 — 사전 치환이 되살아나면 여기서 깨진다.
// ★ 이 함수는 표시 전용이다 — 저장/전송 값에는 적용하지 않는다(labelDisplayNameNoPayloadLeak).

import { describe, expect, it } from 'vitest';

import { resolveLabelDisplayName } from '../labelDisplayName';

describe('resolveLabelDisplayName — 마스터 등록명 그대로 표시', () => {
  it('마스터에_한글로_등록된_이름은_그대로', () => {
    // given / when / then
    expect(resolveLabelDisplayName('사람')).toBe('사람');
    expect(resolveLabelDisplayName('보행자(성인)')).toBe('보행자(성인)');
  });

  it('마스터에_영문으로_등록된_이름도_그대로 — 한글로_치환하지_않는다', () => {
    // 회귀 가드(핵심): COCO 한글 사전에 있는 이름이어도 치환되지 않는다.
    expect(resolveLabelDisplayName('car')).toBe('car');
    expect(resolveLabelDisplayName('person')).toBe('person');
    expect(resolveLabelDisplayName('bus')).toBe('bus');
    expect(resolveLabelDisplayName('traffic light')).toBe('traffic light');
  });

  it('레거시_className_코드도_치환하지_않는다', () => {
    // 구 LABEL_CLASS_DEFS 사전('VEHICLE'→'차량')도 폐기 — 원문 표기가 의도된 결과다.
    expect(resolveLabelDisplayName('VEHICLE')).toBe('VEHICLE');
    expect(resolveLabelDisplayName('PERSON')).toBe('PERSON');
  });

  it('마스터_미연결_라벨의_원문도_임의로_대체하지_않는다', () => {
    // '미연결' 같은 문구를 새로 만들지 않는다 — 값이 있으면 그 값이 곧 표시명이다.
    expect(resolveLabelDisplayName('water')).toBe('water');
    expect(resolveLabelDisplayName('unknown-thing')).toBe('unknown-thing');
  });

  it('앞뒤_공백은_다듬어_표시한다', () => {
    expect(resolveLabelDisplayName('  car  ')).toBe('car');
  });

  it('빈값은_대시로_표시', () => {
    expect(resolveLabelDisplayName(null)).toBe('-');
    expect(resolveLabelDisplayName(undefined)).toBe('-');
    expect(resolveLabelDisplayName('   ')).toBe('-');
  });

  it('프로토타입_속성명이_이름이어도_원문_문자열만_반환한다', () => {
    // 사전 조회가 없어 구조적으로 오염이 불가능하지만, 사전이 되살아나면 여기서 깨진다.
    expect(resolveLabelDisplayName('constructor')).toBe('constructor');
    expect(resolveLabelDisplayName('__proto__')).toBe('__proto__');
    expect(resolveLabelDisplayName('toString')).toBe('toString');
  });
});
