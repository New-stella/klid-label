// 요약 카드가 보여줄 값 도출 — 순수 함수 시험. [@design UI-157] [@design UI-107]

import { describe, expect, it } from 'vitest';

import {
  candidateName,
  candidateNumber,
  descriptionFirstLine,
  eventTypeNameOf,
  sortCandidateKeys,
  unfilledItems,
} from '../annotationSummary';

const TYPES = [
  { vrfcEvntTypeCd: 'car_accident', vrfcEvntTypeNm: '교통사고' },
  { vrfcEvntTypeCd: 'fire', vrfcEvntTypeNm: '화재' },
];

describe('annotationSummary', () => {
  describe('후보 이름', () => {
    it('★번호는_저장_키의_숫자다_목록_순번이_아니다', () => {
      // c1 을 지운 뒤 남은 c2 는 그대로 「근거 2」다 — 순번으로 다시 매기면 사람이 가리키는 것과
      // 저장된 것이 달라진다.
      expect(candidateName('근거', 'c2')).toBe('근거 2');
      expect(candidateName('캡션', 'c10')).toBe('캡션 10');
      expect(candidateNumber('c7')).toBe(7);
    });

    it('숫자_형태가_아닌_키는_그대로_보이고_번호는_없다', () => {
      // 지어낸 번호를 붙이지 않는다 — 키가 곧 이름이다.
      expect(candidateName('근거', 'custom')).toBe('근거 custom');
      expect(candidateNumber('custom')).toBeNull();
    });

    it('후보_정렬은_문자열이_아니라_숫자순이다', () => {
      // 문자열 정렬이면 c10 이 c2 앞으로 온다.
      expect(sortCandidateKeys(['c10', 'c2', 'c1'])).toEqual(['c1', 'c2', 'c10']);
      // 숫자 형태가 아닌 키는 뒤에 사전순으로 둔다(정렬 결과가 실행마다 흔들리지 않는다).
      expect(sortCandidateKeys(['zz', 'c2', 'aa'])).toEqual(['c2', 'aa', 'zz']);
    });
  });

  describe('이벤트 분류 이름', () => {
    it('목록에_있으면_이름을_준다', () => {
      expect(eventTypeNameOf(TYPES, 'car_accident')).toBe('교통사고');
    });

    it('★모르는_코드에는_이름을_지어내지_않는다', () => {
      // 코드를 이름 자리에 채우면 사업자 내부 코드가 이름인 것처럼 보인다.
      expect(eventTypeNameOf(TYPES, 'unknown_kind')).toBeUndefined();
      expect(eventTypeNameOf(undefined, 'car_accident')).toBeUndefined();
      expect(eventTypeNameOf(TYPES, null)).toBeUndefined();
      expect(eventTypeNameOf(TYPES, '  ')).toBeUndefined();
    });
  });

  describe('영상 분석 설명 첫 줄', () => {
    it('값이_있는_첫_슬롯의_첫_줄을_준다', () => {
      expect(descriptionFirstLine(['', '첫 줄\n둘째 줄'])).toBe('첫 줄');
    });

    it('앞이_빈_줄이면_건너뛴다', () => {
      expect(descriptionFirstLine(['\n\n  실제 첫 줄\n다음'])).toBe('실제 첫 줄');
    });

    it('전부_비면_null_이다_빈_문자열을_그리지_않는다', () => {
      expect(descriptionFirstLine([])).toBeNull();
      expect(descriptionFirstLine(['', '   '])).toBeNull();
    });
  });

  describe('아직 채우지 않은 항목', () => {
    it('아무것도_없으면_전_항목을_센다', () => {
      expect(unfilledItems({ descriptions: [], payload: undefined })).toEqual([
        '영상 분석 설명',
        '이벤트 분류',
        '질의',
        '답변',
        '캡션 문장',
        '사고 1·2·3단계',
        '근거',
      ]);
    });

    it('★사고_단계는_묶어서_적는다', () => {
      // 단계마다 한 줄씩 적으면 목록이 길어져 정작 무엇이 비었는지가 묻힌다.
      const items = unfilledItems({
        descriptions: ['도로에서 충돌'],
        payload: {
          event_class: 'car_accident',
          question: '무슨 일인가?',
          caption: { c1: { caption_text: '차량이 충돌했다', cot: ['상황 관찰'] } },
        },
      });
      expect(items).toEqual(['답변', '사고 2·3단계', '근거']);
    });

    it('근거는_문장이_없어도_프레임이나_객체가_있으면_채운_것으로_본다', () => {
      const items = unfilledItems({
        descriptions: ['설명'],
        payload: {
          event_class: 'fire',
          question: 'Q',
          answer: 'A',
          caption: { c1: { caption_text: 'C', cot: ['1', '2', '3'] } },
          evidence: { c1: { frame_id: [102] } },
        },
      });
      expect(items).toEqual([]);
    });

    it('빈_껍데기_근거_후보는_채운_것이_아니다', () => {
      const items = unfilledItems({
        descriptions: ['설명'],
        payload: {
          event_class: 'fire',
          question: 'Q',
          answer: 'A',
          caption: { c1: { caption_text: 'C', cot: ['1', '2', '3'] } },
          evidence: { c1: {} },
        },
      });
      expect(items).toEqual(['근거']);
    });

    it('공백만_있는_값은_채운_것이_아니다', () => {
      const items = unfilledItems({
        descriptions: ['   '],
        payload: { event_class: '  ', question: '', answer: '\n' },
      });
      expect(items).toContain('영상 분석 설명');
      expect(items).toContain('이벤트 분류');
      expect(items).toContain('질의');
      expect(items).toContain('답변');
    });

    it('사고_단계_판정은_첫_번째_캡션_후보를_기준으로_한다', () => {
      // 후보가 여럿이면 저장 키 숫자순 첫 번째가 기준이다(목록 순서가 아니라).
      const items = unfilledItems({
        descriptions: ['설명'],
        payload: {
          event_class: 'fire',
          question: 'Q',
          answer: 'A',
          caption: {
            c2: { caption_text: 'B', cot: ['1', '2', '3'] },
            c1: { caption_text: 'A', cot: ['1'] },
          },
          evidence: { c1: { evidence_text: 'E' } },
        },
      });
      expect(items).toEqual(['사고 2·3단계']);
    });
  });
});
