import { describe, expect, it } from 'vitest';

import {
  EVENT_TYPES,
  EVENT_TYPE_CODES,
  EVENT_TYPE_LABEL,
  eventTypeLabel,
} from '@/constants/eventTypes';

describe('constants/eventTypes (SoT)', () => {
  it('EVENT_TYPES_는_6_종_운영', () => {
    expect(EVENT_TYPES).toHaveLength(6);
  });

  it('정규_코드_6_종_포함_EVT_TRASH_없음', () => {
    const codes = EVENT_TYPES.map((e) => e.code);
    expect(codes).toEqual([
      'EVT_FALL',
      'EVT_VIOLENCE',
      'EVT_ACCIDENT',
      'EVT_ABNORMAL',
      'EVT_FLOOD',
      'EVT_FIRE',
    ]);
    expect(codes).not.toContain('EVT_TRASH');
  });

  it('SoT_라벨_매핑_정확', () => {
    expect(EVENT_TYPE_LABEL.EVT_FALL).toBe('쓰러짐');
    expect(EVENT_TYPE_LABEL.EVT_VIOLENCE).toBe('폭력');
    expect(EVENT_TYPE_LABEL.EVT_ACCIDENT).toBe('교통사고');
    expect(EVENT_TYPE_LABEL.EVT_ABNORMAL).toBe('이상행동(유괴)');
    expect(EVENT_TYPE_LABEL.EVT_FLOOD).toBe('침수');
    expect(EVENT_TYPE_LABEL.EVT_FIRE).toBe('산불');
  });

  it('EVENT_TYPE_CODES_는_6_개', () => {
    expect(EVENT_TYPE_CODES).toHaveLength(6);
  });

  describe('eventTypeLabel()', () => {
    it('정규_EVT_코드_라벨_반환', () => {
      expect(eventTypeLabel('EVT_FALL')).toBe('쓰러짐');
      expect(eventTypeLabel('EVT_FIRE')).toBe('산불');
      expect(eventTypeLabel('EVT_ABNORMAL')).toBe('이상행동(유괴)');
      expect(eventTypeLabel('EVT_FLOOD')).toBe('침수');
    });

    it('약어_코드_폴백_라벨_반환', () => {
      expect(eventTypeLabel('FALL')).toBe('쓰러짐');
      expect(eventTypeLabel('VIOLENCE')).toBe('폭력');
      expect(eventTypeLabel('TRAFFIC_ACCIDENT')).toBe('교통사고');
      expect(eventTypeLabel('ABNORMAL_BEHAVIOR')).toBe('이상행동(유괴)');
      expect(eventTypeLabel('FLOOD')).toBe('침수');
      expect(eventTypeLabel('WILDFIRE')).toBe('산불');
    });

    it('null_undefined_빈_문자열은_dash_반환', () => {
      expect(eventTypeLabel(null)).toBe('-');
      expect(eventTypeLabel(undefined)).toBe('-');
      expect(eventTypeLabel('')).toBe('-');
    });

    it('매핑_없는_코드는_입력_그대로', () => {
      expect(eventTypeLabel('UNKNOWN_CODE')).toBe('UNKNOWN_CODE');
    });
  });
});
