// 회귀(2026-08-06) — 우측 객체 패널의 분류 그룹 점이 라벨 마스터 색상과 달랐다.
//
// 구 동작: `labelColors.getLabelColor(className)` 이 하드코딩 색상표(LABEL_CLASS_DEFS 9종)를
//          참조해, 마스터에서 색을 바꿔도 반영되지 않고 미등록 분류는 회색으로 떨어졌다.
//          그 표는 마스터와 어긋나는 **두 번째 진실원**이라 폐지하고 파일째 삭제했다.
// 새 동작: 색상 판정 단일 진실원 `utils/labelColor.getLabelDisplayColor` 를 재사용한다.
//
// ⚠ 개별 항목 좌측 막대(trackIdToColor)는 **트랙 시각화 의도**라 그대로 유지한다 —
//   이 파일이 그 경계도 함께 고정한다(그룹=분류축 / 항목=트랙축).

import { describe, expect, it, vi } from 'vitest';

let labelMastersData: Array<{
  labelId: number;
  name: string;
  color: string;
  type: string;
  sortNo: number;
  useYn: string;
  dtctTypeCd: string | null;
}> = [];

vi.mock('../hooks/useLabelMasters', () => ({
  useLabelMasters: () => ({ data: labelMastersData, isLoading: false, isError: false }),
}));

import { useLabelStore } from '@/stores/useLabelStore';
import { renderWithProviders } from '@/test/renderWithProviders';

import { ObjectClassTree } from '../components/ObjectClassTree';
import type { Label } from '../types';
import { trackIdToColor } from '../utils/trackColor';

function makeLabel(over: Partial<Label> & Pick<Label, 'id'>): Label {
  return {
    frameNo: 1,
    classId: 1,
    className: 'PERSON',
    source: 'MANUAL',
    shape: { type: 'BBOX', left: 0, top: 0, right: 10, bottom: 10 },
    ...over,
  };
}

/** 그룹 헤더 버튼 안의 색상 점(rounded-full) 스타일 색. */
function groupDotColor(container: HTMLElement, index = 0): string {
  const dots = container.querySelectorAll('.rounded-full');
  return (dots[index] as HTMLElement).style.backgroundColor;
}

describe('ObjectClassTree — 그룹 헤더 색상은 라벨 마스터 색상', () => {
  it('그룹_점은_라벨마스터_색상이며_하드코딩_색상표_색이_아니다', () => {
    // given: 마스터 'PERSON'(labelId=42) 의 색은 #123456.
    //        구 하드코딩 표는 PERSON 을 #EF4444 로 강제했다 — 그 값이 나오면 회귀다.
    useLabelStore.getState().reset();
    labelMastersData = [
      {
        labelId: 42,
        name: 'PERSON',
        color: '#123456',
        type: 'BBOX',
        sortNo: 1,
        useYn: 'Y',
        dtctTypeCd: null,
      },
    ];
    const labels: Label[] = [makeLabel({ id: 'a', labelId: 42, classId: 42, trackId: '9' })];

    // when
    const { container } = renderWithProviders(<ObjectClassTree labels={labels} />);

    // then: 마스터 색(#123456 = rgb(18,52,86))
    expect(groupDotColor(container)).toBe('rgb(18, 52, 86)');
    // 구 하드코딩 표의 PERSON 색(#EF4444)이 아니다.
    expect(groupDotColor(container)).not.toBe('rgb(239, 68, 68)');
  });

  it('BE가_실어준_label_color가_있으면_그_값을_쓴다', () => {
    // given: 저장 왕복 후 BE LabelResponse.Item.color 가 실려온 상태
    useLabelStore.getState().reset();
    labelMastersData = [];
    const labels: Label[] = [
      makeLabel({ id: 'a', labelId: 42, classId: 42, color: '#00FF00' }),
    ];

    // when
    const { container } = renderWithProviders(<ObjectClassTree labels={labels} />);

    // then
    expect(groupDotColor(container)).toBe('rgb(0, 255, 0)');
  });

  it('그룹_점에는_trackId_해시색이_새지_않는다', () => {
    // given: 마스터 미로딩 + label.color 없음 → 마스터 색을 못 찾는 상황.
    //        이때도 그룹(분류축)에 트랙 해시색이 들어가면 안 된다.
    useLabelStore.getState().reset();
    labelMastersData = [];
    const labels: Label[] = [makeLabel({ id: 'a', trackId: '42' })];

    // when
    const { container } = renderWithProviders(<ObjectClassTree labels={labels} />);

    // then: 그룹 점 ≠ trackId 해시색
    const trackColor = trackIdToColor('42'); // hsl(...)
    expect(groupDotColor(container)).not.toBe(trackColor);
    // 결정적 fallback(#RRGGBB)으로만 떨어진다 — inline style 안전값.
    expect(groupDotColor(container)).toMatch(/^rgb\(\d+, \d+, \d+\)$/);
  });

  it('개별_항목_막대는_여전히_trackId_해시색이다_의도된_동작_보존', () => {
    // given
    useLabelStore.getState().reset();
    labelMastersData = [
      {
        labelId: 42,
        name: 'PERSON',
        color: '#123456',
        type: 'BBOX',
        sortNo: 1,
        useYn: 'Y',
        dtctTypeCd: null,
      },
    ];
    const labels: Label[] = [
      makeLabel({ id: 'a', labelId: 42, classId: 42, trackId: '42' }),
      makeLabel({ id: 'b', labelId: 42, classId: 42, trackId: '99' }),
    ];

    // when
    const { container } = renderWithProviders(<ObjectClassTree labels={labels} />);

    // then: 같은 분류(같은 그룹 색)라도 항목 막대는 트랙별로 다른 색
    const bars = container.querySelectorAll('[data-testid="label-color-bar"]');
    expect(bars).toHaveLength(2);
    const c0 = (bars[0] as HTMLElement).style.backgroundColor;
    const c1 = (bars[1] as HTMLElement).style.backgroundColor;
    expect(c0).not.toBe(c1);
    // 마스터 색(그룹 점)과도 다르다 — 두 축이 섞이지 않았다.
    expect(c0).not.toBe('rgb(18, 52, 86)');
  });
});

// 그룹핑 키는 className **문자열**인데 마스터 라벨명에는 유일성 제약이 없다. 같은 이름·다른
// labelId 가 한 그룹에 섞이면 대표를 items[0](배열 순서)로 고를 때 정렬·필터·재조회만으로
// 그룹 점 색이 흔들린다 — 수정 전 하드코딩 색상표는 (값은 틀렸어도) 결정적이었으므로
// 이번 수정이 새로 들일 뻔한 불안정성이다. 대표 선택은 최소 labelId(→ id 사전순) 로 고정한다.
describe('ObjectClassTree — 그룹 대표 색상의 결정성', () => {
  const MASTERS = [
    {
      labelId: 7,
      name: 'PERSON',
      color: '#123456',
      type: 'BBOX',
      sortNo: 1,
      useYn: 'Y',
      dtctTypeCd: null,
    },
    // 같은 표시명(PERSON)이지만 다른 마스터 행 — 이름 유일성이 없다는 전제 그 자체.
    {
      labelId: 88,
      name: 'PERSON',
      color: '#AABBCC',
      type: 'BBOX',
      sortNo: 2,
      useYn: 'Y',
      dtctTypeCd: null,
    },
  ];

  it('동명_다른labelId가_섞인_그룹에서_배열_순서를_뒤집어도_그룹_점_색이_같다', () => {
    // given: 같은 className('PERSON') + 서로 다른 labelId(7, 88).
    useLabelStore.getState().reset();
    labelMastersData = [...MASTERS];
    const a = makeLabel({ id: 'a', labelId: 88, classId: 88 });
    const b = makeLabel({ id: 'b', labelId: 7, classId: 7 });

    // when: 같은 집합을 서로 다른 순서로 렌더
    const forward = renderWithProviders(<ObjectClassTree labels={[a, b]} />);
    const colorForward = groupDotColor(forward.container);
    forward.unmount();

    const reversed = renderWithProviders(<ObjectClassTree labels={[b, a]} />);
    const colorReversed = groupDotColor(reversed.container);
    reversed.unmount();

    // then: 순서와 무관하게 동일 + 최소 labelId(7) 의 마스터 색(#123456)
    expect(colorForward).toBe(colorReversed);
    expect(colorForward).toBe('rgb(18, 52, 86)');
    // items[0] 을 대표로 쓰면 뒤집었을 때 #AABBCC 로 흔들린다.
    expect(colorReversed).not.toBe('rgb(170, 187, 204)');
  });

  it('labelId가_전부_null인_레거시_그룹에서도_순서_독립이다', () => {
    // given: 마스터 미연결(labelId null) + 행마다 실려온 color 가 다른 최악 조건.
    useLabelStore.getState().reset();
    labelMastersData = [...MASTERS];
    const a = makeLabel({ id: 'z-old', labelId: null, classId: 0, color: '#111111' });
    const b = makeLabel({ id: 'a-old', labelId: null, classId: 0, color: '#222222' });

    // when
    const forward = renderWithProviders(<ObjectClassTree labels={[a, b]} />);
    const colorForward = groupDotColor(forward.container);
    forward.unmount();

    const reversed = renderWithProviders(<ObjectClassTree labels={[b, a]} />);
    const colorReversed = groupDotColor(reversed.container);
    reversed.unmount();

    // then: 크래시 없이 동일 색 — id 사전순 tiebreak('a-old')
    expect(colorForward).toBe(colorReversed);
    expect(colorForward).toBe('rgb(34, 34, 34)');
  });

  it('마스터_연결된_항목이_미연결_항목보다_대표로_우선한다', () => {
    // given: 미연결(null) 행이 배열 앞에 오는 순서.
    useLabelStore.getState().reset();
    labelMastersData = [...MASTERS];
    const legacy = makeLabel({ id: 'a-legacy', labelId: null, classId: 0 });
    const linked = makeLabel({ id: 'z-linked', labelId: 88, classId: 88 });

    // when
    const { container } = renderWithProviders(
      <ObjectClassTree labels={[legacy, linked]} />,
    );

    // then: 미연결(fallback 회색)이 아니라 연결된 마스터 색(#AABBCC)
    expect(groupDotColor(container)).toBe('rgb(170, 187, 204)');
  });
});
