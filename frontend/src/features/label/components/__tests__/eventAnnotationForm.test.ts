// cot 와이어 양형(배열 / 단계명 키 객체) 정규화 회귀 테스트.
// 실서버 VLM 원본이 {"1단계": "...", ...} 객체형으로 도착해 검수 화면이
// (cand.cot ?? []).filter 에서 크래시했던 결함(2026-07-27)의 재발 방지.

import { describe, expect, it } from 'vitest';

import { normalizeCot } from '../../api/eventAnnotation';
import { toCaptionRows } from '../eventAnnotationForm';

describe('normalizeCot', () => {
  it('배열형_cot_그대로_반환', () => {
    expect(normalizeCot(['관찰', '추론', '결론'])).toEqual(['관찰', '추론', '결론']);
  });

  it('객체형_cot_값을_키순서대로_배열화', () => {
    expect(
      normalizeCot({ '1단계': '옷을 입고있다', '2단계': '걸어가고있다', '3단계': '쓰러져있다' }),
    ).toEqual(['옷을 입고있다', '걸어가고있다', '쓰러져있다']);
  });

  it('undefined_는_빈_배열', () => {
    expect(normalizeCot(undefined)).toEqual([]);
  });
});

describe('toCaptionRows', () => {
  it('객체형_cot_도_3단계_폼행으로_정규화', () => {
    const rows = toCaptionRows({
      c1: {
        caption_text: '쓰러진 사람이 누구야',
        cot: { '1단계': '옷을 입고있다', '2단계': '걸어가고있다', '3단계': '쓰러져있다' },
      },
    });
    expect(rows).toEqual([
      {
        key: 'c1',
        captionText: '쓰러진 사람이 누구야',
        cot: ['옷을 입고있다', '걸어가고있다', '쓰러져있다'],
      },
    ]);
  });
});
