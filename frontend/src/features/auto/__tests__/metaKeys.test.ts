import { describe, expect, it } from 'vitest';

import {
  ACCURACY_META_KEY,
  DESCRIPTION_META_KEY,
  EDITABLE_META_KEYS,
  MANUAL_TIMESERIES_META_KEY,
  IMPORTED_META_LABELS,
  compareByStartSec,
  editableMetaLabel,
  importedMetaLabel,
  isEditableMetaKey,
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

  describe('importedMetaLabel — 이관 원문 화면 라벨 [design: API-066]', () => {
    /**
     * ★<b>값 고정</b> 축이다 — 형식(한글인가·비었는가)만 보는 검사는 두 항목의 값이 서로
     * 뒤바뀌어도 통과한다. 열쇠↔이름 쌍을 여기에 못박아 그 변이를 잡는다.
     *
     * 열쇠 문자열은 BE 상수(ImportMetaKeys · ExportPrivacyPolicy)의 미러이며, 어긋나면 그
     * 열쇠가 이름 없이 원문으로 떨어져 사람이 읽을 수 없게 된다.
     */
    const EXPECTED_PAIRS: ReadonlyArray<readonly [string, string]> = [
      ['import.video.coordinates', '좌표'],
      ['import.video.location', '위치'],
      ['import.video.cctv_height', '카메라 설치 높이'],
      ['import.video.cctv_azimuth', '카메라 설치 방위'],
      ['import.video.cctv_mng_no', '카메라 관리번호'],
      ['import.video.data_source', '데이터 출처'],
      ['import.video.event_log', '이벤트 기록'],
      ['import.video.event_level1_name', '이벤트 상위 계층 이름 1'],
      ['import.video.event_level2_name', '이벤트 상위 계층 이름 2'],
      ['import.video.event_level3_name', '이벤트 상위 계층 이름 3'],
      ['import.video.id', '외부 영상 식별자'],
      ['import.video.anonymity', '원천 익명여부(영상)'],
      ['import.video.pseudonymity', '원천 가명여부(영상)'],
      ['import.video.privacy_included', '원천 개인정보 포함여부(영상)'],
      ['import.image.anonymity', '원천 익명여부(프레임)'],
      ['import.image.pseudonymity', '원천 가명여부(프레임)'],
      ['import.image.privacy_included', '원천 개인정보 포함여부(프레임)'],
    ];

    it('열쇠별_한글_이름이_고정돼_있다', () => {
      for (const [key, label] of EXPECTED_PAIRS) {
        expect(importedMetaLabel(key)).toBe(label);
      }
    });

    it('매핑_전량이_기대_쌍과_정확히_같다_추가도_누락도_없다', () => {
      // 항목이 늘거나 줄면 이 단언이 먼저 깨진다 — 표를 고칠 때 시험도 함께 고치게 강제한다.
      expect(Object.entries(IMPORTED_META_LABELS).sort()).toEqual(
        EXPECTED_PAIRS.map(([k, v]) => [k, v]).sort(),
      );
    });

    it('★매핑에_없는_열쇠는_버리지_않고_원문_그대로_돌려준다', () => {
      // 조용한 손실 금지 — 이관이 새 열쇠를 늘려도 화면에서 값이 증발하면 안 된다.
      expect(importedMetaLabel('import.video.brand_new_key')).toBe(
        'import.video.brand_new_key',
      );
      expect(importedMetaLabel('')).toBe('');
    });
  });

  /*
   * ★ 폐기 — readOnlyMetaLabel · formatReadOnlyMetaValue 시험(2026-08-24).
   *   유일한 대상이던 일치도를 화면에서 빼기로 확정하면서 두 함수가 사라졌다. 그 대신
   *   "표시하지 않는다" 를 패널 시험(TimeseriesSidePanel · ReviewMetaPanel)이 고정한다 —
   *   포맷터를 검증하던 자리를 비워 두면 누가 되살려도 초록불이기 때문이다.
   */
});
