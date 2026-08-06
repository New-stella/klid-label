import { describe, expect, it } from 'vitest';

import {
  ACCURACY_META_KEY,
  DESCRIPTION_META_KEY,
  EDITABLE_META_KEYS,
  MANUAL_TIMESERIES_META_KEY,
  compareByStartSec,
  editableMetaLabel,
  formatReadOnlyMetaValue,
  isEditableMetaKey,
  readOnlyMetaLabel,
  startSecOf,
} from '../metaKeys';

/**
 * `features/auto/metaKeys` 는 라벨링 편집 패널·검수 읽기 패널·어댑터(api.ts) 세 곳이 공유하는
 * **판정 단일 원천**이다. 지금까지는 세 소비자의 컴포넌트 테스트로 간접 커버될 뿐이라
 * 판정 자체의 경계(비구간 키 혼재·동률·범위 밖 값·미지 키)가 고정돼 있지 않았다.
 *
 * 이 파일은 그 판정을 **직접** 고정한다. 소비자 쪽 간접 테스트는 화면 계약을 보는 것이라
 * 중복이 아니며 그대로 유지한다.
 */
describe('metaKeys — 시계열 메타 키 판정 단일 원천', () => {
  describe('startSecOf — 레거시 구간 키의 start_sec 추출', () => {
    it('구간형_키에서_start_sec를_숫자로_읽는다', () => {
      // given/when/then — "{start}-{end}" 및 순번형 단독 숫자
      expect(startSecOf('0-8')).toBe(0);
      expect(startSecOf('8-16')).toBe(8);
      expect(startSecOf('120-128')).toBe(120);
      expect(startSecOf('0001')).toBe(1);
    });

    it('앞뒤_공백은_무시한다', () => {
      // given — 저장 과정에서 공백이 섞일 수 있다
      // when/then
      expect(startSecOf('  10-18  ')).toBe(10);
    });

    it('구간형이_아닌_키는_null이다', () => {
      // given — 편집 슬롯 키·자동 생성 키·빈 문자열
      // when/then — 숫자로 오인해 정렬 앞으로 끌어오면 시간축이 깨진다
      expect(startSecOf(MANUAL_TIMESERIES_META_KEY)).toBeNull();
      expect(startSecOf(DESCRIPTION_META_KEY)).toBeNull();
      expect(startSecOf(ACCURACY_META_KEY)).toBeNull();
      expect(startSecOf('video.fps')).toBeNull();
      expect(startSecOf('')).toBeNull();
      expect(startSecOf('   ')).toBeNull();
    });

    it('음수_소수_비정형_구간은_null이다', () => {
      // given — 정규식은 "숫자[-숫자]" 만 인정한다(fail-closed)
      // when/then
      expect(startSecOf('-5')).toBeNull();
      expect(startSecOf('-5-3')).toBeNull();
      expect(startSecOf('1.5-2.5')).toBeNull();
      expect(startSecOf('8-')).toBeNull();
      expect(startSecOf('8-16-24')).toBeNull();
      expect(startSecOf('8s-16s')).toBeNull();
    });

    it('null_undefined_비문자열_입력에도_예외를_던지지_않는다', () => {
      // given — 어댑터가 BE 응답을 그대로 실어 나르므로 방어적 입력이 들어올 수 있다
      // when/then
      expect(startSecOf(null)).toBeNull();
      expect(startSecOf(undefined)).toBeNull();
      expect(startSecOf(123 as unknown as string)).toBeNull();
    });
  });

  describe('compareByStartSec — 레거시 구간 정렬 비교자', () => {
    it('start_sec_숫자_오름차순으로_정렬한다_문자열정렬_아님', () => {
      // given — 문자열 정렬이면 "10-18" 이 "8-16" 앞으로 온다(구간 10개 초과 시 시간축 붕괴)
      const keys = ['80-88', '8-16', '16-24', '0-8', '10-18'];

      // when
      const sorted = [...keys].sort(compareByStartSec);

      // then
      expect(sorted).toEqual(['0-8', '8-16', '10-18', '16-24', '80-88']);
    });

    it('구간형이_아닌_키가_섞여도_숫자키가_앞_나머지는_사전순_뒤', () => {
      // given — 편집 슬롯 키가 목록에 섞여 있는 실제 응답 형태
      const keys = [MANUAL_TIMESERIES_META_KEY, '8-16', DESCRIPTION_META_KEY, '0-8'];

      // when
      const sorted = [...keys].sort(compareByStartSec);

      // then — 숫자 키 먼저(시간축), 비구간 키는 뒤에 사전순
      expect(sorted).toEqual([
        '0-8',
        '8-16',
        MANUAL_TIMESERIES_META_KEY,
        DESCRIPTION_META_KEY,
      ]);
    });

    it('start_sec가_동률이면_키_사전순으로_결정한다_전순서', () => {
      // given — end_sec 만 다른 두 구간(정렬 결과가 실행마다 흔들리면 안 된다)
      // when/then
      expect(compareByStartSec('8-16', '8-20')).toBeLessThan(0);
      expect(compareByStartSec('8-20', '8-16')).toBeGreaterThan(0);
      expect(compareByStartSec('8-16', '8-16')).toBe(0);
    });

    it('비구간_키끼리는_사전순이고_반대칭이다', () => {
      // given/when/then
      expect(compareByStartSec('a', 'b')).toBeLessThan(0);
      expect(compareByStartSec('b', 'a')).toBeGreaterThan(0);
      expect(compareByStartSec('a', 'a')).toBe(0);
    });

    it('숫자키와_비구간키의_비교는_방향이_대칭이다', () => {
      // given — 한쪽만 구간형인 경우 -1/1 고정
      // when/then
      expect(compareByStartSec('0-8', MANUAL_TIMESERIES_META_KEY)).toBeLessThan(0);
      expect(compareByStartSec(MANUAL_TIMESERIES_META_KEY, '0-8')).toBeGreaterThan(0);
    });

    it('정렬_결과가_입력_순서에_의존하지_않는다', () => {
      // given — 같은 집합을 다른 순서로 두 번 정렬
      const a = ['16-24', '0-8', MANUAL_TIMESERIES_META_KEY, '8-16', DESCRIPTION_META_KEY];
      const b = [DESCRIPTION_META_KEY, '8-16', MANUAL_TIMESERIES_META_KEY, '0-8', '16-24'];

      // when
      const sortedA = [...a].sort(compareByStartSec);
      const sortedB = [...b].sort(compareByStartSec);

      // then — 전순서라 결과가 같다
      expect(sortedA).toEqual(sortedB);
    });
  });

  describe('isEditableMetaKey — 편집 허용 화이트리스트(fail-closed)', () => {
    it('화이트리스트_2키만_편집_가능하다', () => {
      // given/when/then
      expect(EDITABLE_META_KEYS).toEqual([DESCRIPTION_META_KEY, MANUAL_TIMESERIES_META_KEY]);
      expect(isEditableMetaKey(DESCRIPTION_META_KEY)).toBe(true);
      expect(isEditableMetaKey(MANUAL_TIMESERIES_META_KEY)).toBe(true);
    });

    it('일치도_기술메타_레거시구간은_편집_대상이_아니다', () => {
      // given — 여집합은 전부 읽기 전용(BE 가 읽기전용 키를 늘려도 슬롯으로 새지 않는다)
      // when/then
      expect(isEditableMetaKey(ACCURACY_META_KEY)).toBe(false);
      expect(isEditableMetaKey('video.fps')).toBe(false);
      expect(isEditableMetaKey('0-8')).toBe(false);
      expect(isEditableMetaKey('0001')).toBe(false);
    });

    it('유사_키를_과대허용하지_않고_정확일치만_통과한다', () => {
      // given — 접두 파싱이 아니라 정확 일치라야 한다
      // when/then
      expect(isEditableMetaKey('vlm.description2')).toBe(false);
      expect(isEditableMetaKey('vlm.descriptio')).toBe(false);
      expect(isEditableMetaKey(' vlm.description')).toBe(false);
      expect(isEditableMetaKey('vlm.description ')).toBe(false);
      expect(isEditableMetaKey('VLM.DESCRIPTION')).toBe(false);
      expect(isEditableMetaKey('manual-timeseries-2')).toBe(false);
    });

    it('null_undefined_비문자열은_편집_대상이_아니다', () => {
      // given/when/then — fail-closed
      expect(isEditableMetaKey(null)).toBe(false);
      expect(isEditableMetaKey(undefined)).toBe(false);
      expect(isEditableMetaKey('')).toBe(false);
      expect(isEditableMetaKey(1 as unknown as string)).toBe(false);
    });
  });

  describe('editableMetaLabel — 편집 슬롯 화면 라벨', () => {
    it('서술_전문은_시계열_서술_나머지는_시계열_메타', () => {
      // given/when/then — 화면 문구에 모델명·기술 용어를 노출하지 않는다
      expect(editableMetaLabel(DESCRIPTION_META_KEY)).toBe('시계열 서술');
      expect(editableMetaLabel(MANUAL_TIMESERIES_META_KEY)).toBe('시계열 메타');
    });
  });

  describe('readOnlyMetaLabel — 읽기 전용 메타 화면 라벨', () => {
    it('일치도_키는_한국어_라벨로_매핑한다', () => {
      // given/when/then
      expect(readOnlyMetaLabel(ACCURACY_META_KEY)).toBe('일치도');
    });

    it('미지의_vlm_키는_내부_네임스페이스_접두만_떼고_보여준다', () => {
      // given — BE 가 fail-closed 로 readOnlyMeta 를 늘려도 화면이 죽지 않아야 한다
      // when/then
      expect(readOnlyMetaLabel('vlm.confidence')).toBe('confidence');
      expect(readOnlyMetaLabel('vlm.foo.bar')).toBe('foo.bar');
    });

    it('접두가_없는_미지_키는_원문_그대로_보여준다', () => {
      // given/when/then — 값·의미를 재해석하지 않는다
      expect(readOnlyMetaLabel('someUnknownKey')).toBe('someUnknownKey');
      expect(readOnlyMetaLabel('video.fps')).toBe('video.fps');
    });

    it('접두를_떼면_비는_키는_원문_키로_폴백한다', () => {
      // given — "vlm." 자체 / "vlm.   " 는 떼고 나면 라벨이 사라진다
      // when/then — 빈 라벨 대신 원문(무엇인지 알 수 있게)
      expect(readOnlyMetaLabel('vlm.')).toBe('vlm.');
      expect(readOnlyMetaLabel('vlm.   ')).toBe('vlm.   ');
    });
  });

  describe('formatReadOnlyMetaValue — 읽기 전용 메타 값 표시', () => {
    it('일치도는_0~1을_백분율로_환산한다_소수1자리', () => {
      // given/when/then
      expect(formatReadOnlyMetaValue(ACCURACY_META_KEY, '0.923')).toBe('92.3%');
      expect(formatReadOnlyMetaValue(ACCURACY_META_KEY, '0.92')).toBe('92%');
      expect(formatReadOnlyMetaValue(ACCURACY_META_KEY, '0.9235')).toBe('92.4%');
    });

    it('경계값_0과_1도_환산한다', () => {
      // given — 벤더 규격상 0~1 경계 포함
      // when/then
      expect(formatReadOnlyMetaValue(ACCURACY_META_KEY, '0')).toBe('0%');
      expect(formatReadOnlyMetaValue(ACCURACY_META_KEY, '1')).toBe('100%');
      expect(formatReadOnlyMetaValue(ACCURACY_META_KEY, ' 1 ')).toBe('100%');
    });

    it('숫자가_아니면_지어내지_않고_원문을_그대로_보여준다', () => {
      // given — 벤더가 예상 밖 값을 보내도 화면이 값을 창작하면 안 된다
      // when/then
      expect(formatReadOnlyMetaValue(ACCURACY_META_KEY, 'N/A')).toBe('N/A');
      expect(formatReadOnlyMetaValue(ACCURACY_META_KEY, '')).toBe('');
      expect(formatReadOnlyMetaValue(ACCURACY_META_KEY, '   ')).toBe('   ');
      expect(formatReadOnlyMetaValue(ACCURACY_META_KEY, 'NaN')).toBe('NaN');
      expect(formatReadOnlyMetaValue(ACCURACY_META_KEY, 'Infinity')).toBe('Infinity');
    });

    it('0~1_범위를_벗어나면_원문을_그대로_보여준다', () => {
      // given — 백분율로 환산하면 "150%" 처럼 잘못된 사실을 단언하게 된다
      // when/then
      expect(formatReadOnlyMetaValue(ACCURACY_META_KEY, '1.5')).toBe('1.5');
      expect(formatReadOnlyMetaValue(ACCURACY_META_KEY, '-0.1')).toBe('-0.1');
    });

    it('일치도가_아닌_키는_숫자여도_변환하지_않는다', () => {
      // given — 환산 규칙은 일치도 전용이다(다른 읽기 전용 키의 값을 왜곡하지 않는다)
      // when/then
      expect(formatReadOnlyMetaValue('vlm.confidence', '0.5')).toBe('0.5');
      expect(formatReadOnlyMetaValue('video.fps', '30')).toBe('30');
    });
  });
});
