import { describe, expect, it } from 'vitest';

import { formatEventTypeDisplay } from '@/features/preset/utils/eventTypeDisplay';

/**
 * 프리셋 화면의 이벤트유형 표기 — `이벤트명 (유형코드)`. [@design SCREEN-026]
 *
 * 카드 제목·삭제 확인·편집 모달 옵션이 같은 형태로 읽혀야 해서 한 곳에서 만든다.
 */
describe('formatEventTypeDisplay', () => {
  it('이벤트명과_유형코드를_함께_보인다', () => {
    expect(formatEventTypeDisplay('배회', 'EV08000101')).toBe('배회 (EV08000101)');
  });

  it('★같은_표시명을_가진_유형은_코드로_구분된다', () => {
    // 이름만으로는 어느 유형인지 특정되지 않는다(화재 EV02000101 / 일반화재 EV02000102).
    expect(formatEventTypeDisplay('화재', 'EV02000101')).toBe('화재 (EV02000101)');
    expect(formatEventTypeDisplay('화재', 'EV02000102')).toBe('화재 (EV02000102)');
  });

  it('표시명이_코드와_같으면_코드만_보인다', () => {
    // 서버 4단 폴백의 최종 단계가 유형코드라, 이름 없는 유형은 코드가 표시명으로 온다.
    // 그대로 조립하면 `EV09000101 (EV09000101)` 이 된다.
    expect(formatEventTypeDisplay('EV09000101', 'EV09000101')).toBe('EV09000101');
  });

  it('표시명이_없으면_코드만_보인다', () => {
    expect(formatEventTypeDisplay(null, 'EV09000101')).toBe('EV09000101');
    expect(formatEventTypeDisplay('', 'EV09000101')).toBe('EV09000101');
    expect(formatEventTypeDisplay('   ', 'EV09000101')).toBe('EV09000101');
  });

  it('코드가_없으면_표시명만_보인다', () => {
    expect(formatEventTypeDisplay('배회', null)).toBe('배회');
  });

  it('둘_다_없으면_빈_문자열', () => {
    expect(formatEventTypeDisplay(null, null)).toBe('');
  });
});
