// 검수 라벨 타입(LabelItem)이 **BE 응답의 사실**을 말하는지 고정하는 회귀 가드.
//
// 배경: `LabelResponse.Item` 은 마스터 연결값(labelId/labelName/color)과 trackId 를 실제로 실어
// 보내는데 FE 타입 선언이 과소해서, 화면이 그 값을 직접 읽지 못하고 구조 타입 어댑터로 우회했다.
// 선언이 다시 줄어들면 같은 우회가 되살아나므로 **타입 축과 값 축을 함께** 고정한다.
//
// ⚠ 타입 단언(expectTypeOf)은 vitest 런타임이 아니라 `tsc --noEmit` 게이트가 잡는다
//   (tsconfig `include: ["src"]` 라 테스트 파일도 타입 검사 대상이다). 필드를 지우면
//   여기서 컴파일이 깨진다 — 런타임 assertion 만으로는 타입 축소를 잡을 수 없다.

import { describe, expect, expectTypeOf, it } from 'vitest';

import type { LabelMaster } from '@/features/label/api/labelMaster';

import type { LabelItem } from '../types';
import { reviewLabelColor, reviewTrackBarColor } from '../utils/labelColor';

const MASTERS: LabelMaster[] = [
  {
    labelId: 11,
    name: '사람',
    color: '#123456',
    type: 'BBOX',
    sortNo: 11,
    useYn: 'Y',
    dtctTypeCd: null,
  },
];

/** BE `LabelResponse.Item` 이 실제로 내려보내는 형태(검수 조회 경로 — color 는 항상 null). */
const item: LabelItem = {
  id: 1,
  lblTypeCd: 'BBOX',
  label: '사람',
  points: [
    [0, 0],
    [10, 10],
  ],
  autoLblYn: 'N',
  confScore: null,
  labelId: 11,
  labelName: '사람',
  color: null,
  trackId: 'T-7',
  lblSrcCd: null,
};

describe('LabelItem — BE LabelResponse.Item 과의 계약', () => {
  it('마스터 연결값·트랙 값을 타입이 선언한다', () => {
    // 필드가 사라지면 이 인덱스 접근 자체가 컴파일 에러가 된다.
    expectTypeOf<LabelItem['labelId']>().toEqualTypeOf<number | null | undefined>();
    expectTypeOf<LabelItem['labelName']>().toEqualTypeOf<string | null | undefined>();
    expectTypeOf<LabelItem['color']>().toEqualTypeOf<string | null | undefined>();
    expectTypeOf<LabelItem['trackId']>().toEqualTypeOf<string | null | undefined>();
    expectTypeOf<LabelItem['lblSrcCd']>().toEqualTypeOf<string | null | undefined>();
  });

  it('★labelId 는 nullable 이다 — 마스터 미연결 라벨이 실재한다', () => {
    // BE `Item.from` 은 lsLabel == null 이면 labelId/labelName/color 를 모두 null 로 둔다.
    // non-null 로 단정하면 색상·라벨명 판정이 잘못된 가정 위에 서게 된다.
    const unlinked: LabelItem = { ...item, labelId: null, labelName: null };
    expect(unlinked.labelId).toBeNull();
  });

  it('LabelItem 을 색상 판정기에 그대로 넘길 수 있다(어댑터용 구조 타입 불필요)', () => {
    // 값 축 — labelId 가 실제로 흘러 마스터 색(2순위 lookup)이 나온다.
    expect(reviewLabelColor(item, MASTERS)).toBe('#123456');
  });

  it('trackId 도 그대로 흘러 트랙 시각화 축 색상이 나온다', () => {
    // 분류 축(마스터 색)과 트랙 축(해시 색)은 서로 다른 것을 보여주는 별개 축이다.
    const barColor = reviewTrackBarColor(item);
    expect(barColor).toEqual(expect.any(String));
    expect(barColor).not.toBe('');
  });
});
