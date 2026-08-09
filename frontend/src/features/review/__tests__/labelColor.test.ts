// UI-064 회귀 가드 — 검수 화면 라벨 색상은 **라벨 마스터**가 단일 진실원이고 판정기는
// `features/label/utils/labelColor.getLabelDisplayColor` 한 곳이다.
//
// ⚠ 구 테스트는 하드코딩 색상표(`FIXED_COLORS`: 사람=#ef4444 등)를 **정답으로 박제**하고 있었다.
//   그 표는 확정 정책("하드코딩 색상표를 되살리지 말 것")을 정면으로 위반하는 두 번째 진실원이라
//   폐기됐고, 그 표를 검증하던 케이스도 함께 폐기한다(정답이 바뀐 것이지 커버리지가 준 것이 아니다).

import { describe, expect, it } from 'vitest';

import type { LabelMaster } from '@/features/label/api/labelMaster';

import type { LabelItem } from '../types';
import {
  reviewLabelColor,
  reviewTrackBarColor,
  toColorLabel,
  withAlpha,
} from '../utils/labelColor';

/**
 * 색상 판정 입력은 `LabelItem` **그대로**다 — 구 구조 타입(`ReviewColorLabel`) 우회는
 * `LabelItem` 이 마스터/트랙 연결값을 선언하면서 폐지됐다. 여기 팩토리는 색 판정과 무관한
 * 필드(id/points/…)를 채워 주는 용도이며, 축소된 구조 타입을 다시 만드는 것이 아니다.
 */
const labelItem = (over: Partial<LabelItem>): LabelItem => ({
  id: 1,
  lblTypeCd: 'BBOX',
  label: '사람',
  points: [
    [0, 0],
    [10, 10],
  ],
  autoLblYn: 'N',
  confScore: null,
  ...over,
});

const master = (labelId: number, name: string, color: string): LabelMaster => ({
  labelId,
  name,
  color,
  type: 'BBOX',
  sortNo: labelId,
  useYn: 'Y',
  dtctTypeCd: null,
});

const MASTERS: LabelMaster[] = [master(11, '사람', '#123456'), master(12, '차량', '#ABCDEF')];

describe('reviewLabelColor — 판정은 라벨 마스터에 위임한다', () => {
  it('마스터에_등록된_색상을_그대로_쓴다', () => {
    // given: 마스터(labelId=11)에 연결된 검수 라벨
    const item = labelItem({ label: '사람', labelId: 11, color: null, trackId: null });
    // when
    const color = reviewLabelColor(item, MASTERS);
    // then: 하드코딩 표(#ef4444)가 아니라 마스터 색이 나온다
    expect(color).toBe('#123456');
  });

  it('마스터에서_색을_바꾸면_즉시_반영된다_하드코딩표_부활_금지', () => {
    // given: 같은 라벨의 마스터 색을 운영자가 바꾼 상황
    const item = labelItem({ label: '사람', labelId: 11, color: null, trackId: null });
    // when
    const changed = reviewLabelColor(item, [master(11, '사람', '#00FF00')]);
    // then
    expect(changed).toBe('#00FF00');
  });

  it('BE_enrichment_color_가_있으면_최우선이다', () => {
    // given: 응답에 color 가 실려 온 경우(1순위)
    const item = labelItem({ label: '사람', labelId: 11, color: '#FFFFFF', trackId: null });
    // when / then
    expect(reviewLabelColor(item, MASTERS)).toBe('#FFFFFF');
  });

  it('마스터_미연결이어도_회색으로_죽지_않는다', () => {
    // given: 마스터에 없는 분류(INTRUSION 등) — labelId·trackId 모두 없음
    const item = labelItem({ label: 'INTRUSION', labelId: null, color: null, trackId: null });
    // when
    const color = reviewLabelColor(item, MASTERS);
    // then: 구 하드코딩 표의 알려진 결함(미등록 분류 회색 사멸)이 재현되지 않는다
    expect(color).not.toBe('#94A3B8');
    expect(color).toMatch(/^#[0-9A-Fa-f]{6}$/);
  });

  it('마스터_미연결이지만_트랙이_있으면_트랙_해시색으로_떨어진다', () => {
    // given
    const item = labelItem({ label: 'INTRUSION', labelId: null, color: null, trackId: '7' });
    // when / then
    expect(reviewLabelColor(item, MASTERS)).toMatch(/^hsl\(/);
  });

  it('분류_축은_useTrackFallback_false_로_트랙색_유입을_막는다', () => {
    // given: 트랙이 있는데 마스터에도 연결된 라벨
    const item = labelItem({ label: '차량', labelId: 12, color: null, trackId: '9' });
    // when: 그룹 헤더(분류 축) 판정
    const groupColor = reviewLabelColor(item, MASTERS, { useTrackFallback: false });
    // then: 마스터 색 — 트랙 해시색이 새어들지 않는다
    expect(groupColor).toBe('#ABCDEF');
  });

  it('마스터_목록이_아직_없으면_예외없이_폴백_색을_돌려준다', () => {
    // given: 마스터 쿼리 로딩 중(undefined)
    const item = labelItem({ label: '사람', labelId: 11, color: null, trackId: null });
    // when / then
    expect(reviewLabelColor(item, undefined)).toMatch(/^#[0-9A-Fa-f]{6}$/);
  });
});

describe('reviewTrackBarColor — 트랙 시각화 축(분류 축과 별개)', () => {
  it('같은_트랙은_항상_같은_색이다', () => {
    expect(reviewTrackBarColor(labelItem({ label: '사람', trackId: '3' }))).toBe(
      reviewTrackBarColor(labelItem({ label: '차량', trackId: '3' })),
    );
  });

  it('트랙이_없으면_중립_회색이다', () => {
    expect(reviewTrackBarColor(labelItem({ label: '사람', trackId: null }))).toBe('hsl(0, 0%, 60%)');
  });

  it('마스터_색과_섞이지_않는다_두_축_통일_금지', () => {
    // given: 마스터에 연결됐고 트랙도 있는 라벨
    const item = labelItem({ label: '사람', labelId: 11, color: null, trackId: '3' });
    // then: 분류 축(마스터 색)과 트랙 축(해시색)이 서로 다른 값을 낸다
    expect(reviewLabelColor(item, MASTERS, { useTrackFallback: false })).toBe('#123456');
    expect(reviewTrackBarColor(item)).not.toBe('#123456');
  });
});

describe('toColorLabel — 판정기 입력 어댑터', () => {
  it('마스터_연결값_3종을_그대로_옮긴다', () => {
    const adapted = toColorLabel(labelItem({ label: '사람', labelId: 11, color: '#111111', trackId: '5' }));
    expect(adapted.labelId).toBe(11);
    expect(adapted.color).toBe('#111111');
    expect(adapted.trackId).toBe('5');
    expect(adapted.className).toBe('사람');
  });

  it('classId_는_판정에_관여하지_않도록_0_이다', () => {
    // classId 폴백은 `> 0` 일 때만 동작하므로 0 은 "미사용" 을 뜻한다.
    expect(toColorLabel(labelItem({ label: '사람' })).classId).toBe(0);
  });
});

describe('withAlpha — 판정된 색에 알파만 더한다', () => {
  it('HEX_는_8자리_HEX_로_변환한다', () => {
    expect(withAlpha('#123456', 0.2)).toBe('#12345633');
  });

  it('HSL_은_hsla_로_변환한다', () => {
    expect(withAlpha('hsl(120, 70%, 50%)', 0.2)).toBe('hsla(120, 70%, 50%, 0.2)');
  });

  it('알파는_0_1_범위로_clamp_된다', () => {
    expect(withAlpha('#123456', 2)).toBe('#123456ff');
    expect(withAlpha('#123456', -1)).toBe('#12345600');
  });
});
