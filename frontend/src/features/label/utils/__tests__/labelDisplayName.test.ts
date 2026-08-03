// 2026-08-03 사용자 확정 — 라벨명 한글 우선 표시 공용 함수.
//
// 표시명 규칙(라벨명이 나오는 모든 화면이 이 함수 하나만 쓴다):
//   1) 라벨명에 한글(비-ASCII)이 포함 → 그대로
//   2) 아니면 COCO 매핑(dtctTypeCd, 없으면 라벨명 자체)을 키로 한글 사전 조회 → 있으면 한글
//   3) 사전에도 없으면 원문 그대로
//
// ★ 이 함수는 **표시 전용**이다 — 저장/전송되는 라벨 식별자·이름 값에는 절대 적용하지 않는다.

import { describe, expect, it } from 'vitest';

import { resolveLabelDisplayName } from '../labelDisplayName';

describe('resolveLabelDisplayName — 라벨명 한글 우선 표시', () => {
  it('규칙1_라벨명에_한글이_있으면_그대로_반환', () => {
    // given / when / then — 한글 마스터 라벨명은 사전을 타지 않는다.
    expect(resolveLabelDisplayName('사람')).toBe('사람');
    expect(resolveLabelDisplayName('보행자(성인)')).toBe('보행자(성인)');
    // dtctTypeCd 가 있어도 한글 원문이 이긴다.
    expect(resolveLabelDisplayName('자동차', 'car')).toBe('자동차');
  });

  it('규칙2_영문_라벨명은_COCO_한글사전으로_치환', () => {
    // given — 영문 라벨명 자체가 COCO 클래스명
    expect(resolveLabelDisplayName('person')).toBe('사람');
    expect(resolveLabelDisplayName('traffic light')).toBe('신호등');
  });

  it('규칙2_dtctTypeCd가_있으면_그_값을_사전_키로_사용', () => {
    // given — 라벨명은 사전에 없지만 COCO 매핑이 있는 경우
    expect(resolveLabelDisplayName('ped', 'person')).toBe('사람');
    expect(resolveLabelDisplayName('veh01', 'bus')).toBe('버스');
  });

  it('규칙3_사전에_없으면_원문_그대로', () => {
    // 비-COCO 커스텀 라벨은 영문 그대로 남는 것이 정상 동작(사전 확장은 별건).
    expect(resolveLabelDisplayName('water')).toBe('water');
    expect(resolveLabelDisplayName('airplane')).toBe('airplane');
    expect(resolveLabelDisplayName('unknown-thing', 'not-a-coco-class')).toBe('unknown-thing');
  });

  it('빈값은_대시로_표시', () => {
    expect(resolveLabelDisplayName(null)).toBe('-');
    expect(resolveLabelDisplayName(undefined)).toBe('-');
    expect(resolveLabelDisplayName('   ')).toBe('-');
  });

  it('프로토타입_오염_키는_사전_적중으로_취급하지_않는다', () => {
    // 사용자/BE 제공 문자열이 Object.prototype 속성명이어도 함수/객체가 새어나오면 안 된다.
    expect(resolveLabelDisplayName('constructor')).toBe('constructor');
    expect(resolveLabelDisplayName('__proto__')).toBe('__proto__');
    expect(resolveLabelDisplayName('toString')).toBe('toString');
    expect(resolveLabelDisplayName('x', 'constructor')).toBe('x');
  });

  it('레거시_className_코드도_동일_함수로_한글_표시', () => {
    // ObjectClassTree 가 쓰던 기존 사전(LABEL_CLASS_DEFS)도 같은 함수 안에서 처리한다 —
    // 화면마다 같은 라벨이 다르게 보이지 않게 하는 것이 이 함수의 존재 이유다.
    expect(resolveLabelDisplayName('VEHICLE')).toBe('차량');
    expect(resolveLabelDisplayName('PERSON')).toBe('사람');
  });
});
