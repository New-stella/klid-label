// 공유 목(mock)이 지켜야 할 계약 — **렌더해서** 확인한다.
//
// ★ 이 파일은 무엇을 이어받았나 (2026-08-09)
//   전임은 `mockChildrenPassthroughGuard.test.tsx` 라는 **소스 스캔** 가드였다. 그때는 react-konva
//   목이 67개 테스트 파일에 각각 복제(정규화해서 30종 변형)돼 있어 **밖에서 실행해 볼 방법이 없었고**,
//   그래서 목의 소스를 문자열로 읽어 `createElement(..., children)` 모양이 있는지 보는 수밖에 없었다.
//   목을 `konvaMock.ts` 한 곳으로 모은 지금은 그냥 렌더해서 동작을 볼 수 있다 — 모양이 아니라 동작이
//   기준이므로 전임보다 강하다. 전임은 폐기했다.
//
// ★ 전임이 지키던 것과, 그것을 여기서 어떻게 잇는가
//   ① **자식 전달** — Stage/Layer/Group 이 자식 트리를 그대로 통과시켜야 한다.
//      전임의 실측 근거를 그대로 옮겨 둔다: 67개 파일에서 자식 전달을 **동시에 끊고** 전체 스위트를
//      돌렸더니 **4개 파일 18건만 실패**했다(CanvasShellZoomArea · CanvasShellRotationGrid ·
//      CanvasShellSegmentWiring · review/LabelCanvas). 나머지 63개 파일은 레이어를 중첩 없이 직접
//      렌더하므로 **자식이 통째로 사라져도 전량 통과한다.** 즉 이 계약은 깨져도 아무 신호가 없다 —
//      그래서 따로 못박는다. (`★자식_전달` 두 건이 그 자리다.)
//   ② **`data-konva` 표식** — 테스트가 노드를 찾는 유일한 수단이다.
//   ③ **prop 투영** — 전임은 "각 테스트가 이미 단언하고 있어 중복 검사하지 않는다"며 검사 밖에 뒀다.
//      목이 한 벌이 된 지금은 그 규칙이 **67개 파일 공용**이라 한 곳에서 고정해야 한다.
//   ④ **재분열 방지** — 전임은 소스를 스캔했으므로 "새로 복제된 목"도 자동으로 검사 대상이 됐다.
//      행위 테스트는 공유 팩토리만 보므로 그 성질이 사라진다. 그래서 그 한 조각만 스캔으로 남긴다
//      (`★react-konva_목은_공유_팩토리로만`). 이것이 전임에서 유일하게 계승한 소스 스캔이다.
//
// ★ 전임이 못 보던 것 중 여기서 해소된 것
//   - 전임은 `children` 을 다른 이름에 담아 넘기는 변형을 오탐했고, 실행 시점의 조건부 분기는 보지
//     못했다. 지금은 실제로 렌더하므로 표기와 무관하게 결과만 본다.
//
// ★ 여전히 못 보는 것 (수치와 함께 읽을 것)
//   - 목을 쓰지 않는(= konva 를 안 쓰는) 테스트는 대상이 아니다.
//   - 아래 recharts 계약은 `setup.ts` 의 **다른** 공용 목이다. 목적(자식 전달)이 같아 함께 둔다.

import { render, screen, fireEvent } from '@testing-library/react';
import fs from 'node:fs';
import path from 'node:path';
import { createRef } from 'react';
import { ResponsiveContainer } from 'recharts';
import { describe, expect, it, vi } from 'vitest';

import { KONVA_COMPONENT_NAMES, createKonvaMock } from '@/test/konvaMock';

const SRC_DIR = path.resolve(__dirname, '..');

describe('react-konva 공유 목 — 자식 전달 계약', () => {
  it('★자식_전달_중첩된_도형이_통째로_사라지지_않는다', () => {
    // given: Stage > Layer > Group > Rect 로 3단 중첩한 트리
    const { Stage, Layer, Group, Rect } = createKonvaMock();

    // when
    const { container } = render(
      <Stage>
        <Layer>
          <Group>
            <Rect data-testid="deepest" />
          </Group>
        </Layer>
      </Stage>,
    );

    // then: 가장 안쪽 도형이 살아 있고, 중첩 관계까지 그대로다
    const deepest = screen.getByTestId('deepest');
    expect(
      deepest,
      '중첩된 도형이 사라졌다 — 자식을 통과시키지 않는 목이다. 중첩 없이 렌더하는 테스트 63개는' +
        ' 이 파손을 그대로 통과시키므로 여기서만 드러난다',
    ).toBeInTheDocument();
    expect(
      container.querySelector('[data-konva="Stage"] [data-konva="Layer"] [data-konva="Group"]'),
    ).not.toBeNull();
  });

  it('★자식_전달_노출하는_모든_노드가_자식을_통과시킨다', () => {
    // given/when/then: 노드 종류별로 하나씩 — 한 종류만 끊겨도 여기서 드러난다
    const mock = createKonvaMock();
    const missing: string[] = [];

    for (const name of KONVA_COMPONENT_NAMES) {
      const Node = mock[name];
      const { unmount } = render(
        <Node>
          <span data-testid={`child-of-${name}`} />
        </Node>,
      );
      if (screen.queryByTestId(`child-of-${name}`) === null) missing.push(name);
      unmount();
    }

    expect(missing, `자식을 떨어뜨리는 노드: ${missing.join(', ')}`).toEqual([]);
  });

  it('★ResponsiveContainer_목이_자식을_그대로_렌더한다', () => {
    // recharts 공용 목(setup.ts)도 같은 계약을 진다. jsdom 에서 실제 ResponsiveContainer 는
    // width/height 가 0 이라 차트가 비어 렌더되므로 고정 크기 래퍼로 대체돼 있는데, 그 래퍼가
    // 자식을 떨어뜨리면 통계 화면 테스트는 여전히 전부 통과하면서 차트만 사라진다.
    render(
      <ResponsiveContainer width={600} height={240}>
        <div data-testid="chart-child" />
      </ResponsiveContainer>,
    );

    expect(screen.getByTestId('responsive-container')).toBeInTheDocument();
    expect(
      screen.getByTestId('chart-child'),
      'ResponsiveContainer 목이 자식을 렌더하지 않는다 — 차트 본문이 통째로 사라진다',
    ).toBeInTheDocument();
  });
});

describe('react-konva 공유 목 — 노드 표식과 prop 투영', () => {
  it('data-konva_로_노드_종류를_드러낸다', () => {
    const mock = createKonvaMock();
    const rendered = KONVA_COMPONENT_NAMES.map((name) => {
      const Node = mock[name];
      const { container, unmount } = render(<Node />);
      const marker = container.firstElementChild?.getAttribute('data-konva');
      unmount();
      return marker;
    });

    expect(rendered).toEqual([...KONVA_COMPONENT_NAMES]);
  });

  it('캔버스_전용_prop_을_DOM_이_읽을_수_있는_형태로_옮긴다', () => {
    // given: 배열·불리언이라 그대로는 DOM 에서 읽을 수 없는 props
    const { Line } = createKonvaMock();

    // when
    const { container } = render(
      <Line dash={[10, 5]} points={[0, 0, 10, 10]} closed={false} draggable={true} stroke="#f00" />,
    );

    // then: 이름을 바꿔 옮긴 축 + 그대로 통과하는 축
    const node = container.querySelector('[data-konva="Line"]');
    expect(node?.getAttribute('data-dash')).toBe('10,5');
    expect(node?.getAttribute('data-points')).toBe('0,0,10,10');
    expect(node?.getAttribute('data-closed')).toBe('false');
    expect(node?.getAttribute('data-draggable')).toBe('true');
    expect(node?.getAttribute('stroke')).toBe('#f00');
    // 원본 이름으로는 남기지 않는다 — `draggable` 은 HTML 표준 속성이라 겹치면 오독을 부른다.
    expect(node?.hasAttribute('points')).toBe(false);
  });

  it('onDblClick_은_DOM_표기로_옮겨_fireEvent_doubleClick_이_닿는다', () => {
    const { Rect } = createKonvaMock();
    const onDblClick = vi.fn();

    const { container } = render(<Rect onDblClick={onDblClick} />);
    fireEvent.doubleClick(container.querySelector('[data-konva="Rect"]') as Element);

    expect(onDblClick).toHaveBeenCalledTimes(1);
  });

  it('DOM_이벤트_핸들러는_그대로_통과한다', () => {
    const { Rect } = createKonvaMock();
    const onClick = vi.fn();

    const { container } = render(<Rect onClick={onClick} />);
    fireEvent.click(container.querySelector('[data-konva="Rect"]') as Element);

    expect(onClick).toHaveBeenCalledTimes(1);
  });

  it('DOM_에_대응이_없는_prop_은_버리되_onNode_로는_원본을_전달한다', () => {
    // image(HTMLImageElement)·listening(konva 히트테스트 축)은 DOM 속성으로 옮길 수 없다.
    // 그렇다고 관측까지 막으면 "격자가 히트테스트에 참여하지 않는가" 같은 단언이 성립하지 않는다.
    const seen: Array<[string, Record<string, unknown>]> = [];
    const { Image } = createKonvaMock({ onNode: (name, props) => seen.push([name, props]) });
    const image = document.createElement('img');

    const { container } = render(<Image image={image} listening={false} name="image-layer" />);

    const node = container.querySelector('[data-konva="Image"]');
    expect(node?.hasAttribute('image')).toBe(false);
    expect(node?.hasAttribute('listening')).toBe(false);
    expect(seen).toHaveLength(1);
    expect(seen[0][0]).toBe('Image');
    expect(seen[0][1].image).toBe(image);
    expect(seen[0][1].listening).toBe(false);
  });
});

describe('react-konva 공유 목 — ref', () => {
  it('기본_ref_는_null_이라_소비처의_null_가드가_그대로_성립한다', () => {
    // 복제본 시절 목은 일반 함수 컴포넌트라 React 가 ref 를 무시했다(current=null). 공유 목은
    // forwardRef 지만 기본 핸들이 null 이라 관측 결과가 같다 — `transformerRef.current` 가
    // 갑자기 객체가 되면 `tr.nodes(...)` 가 없는 메서드를 부른다.
    const { Stage, Transformer } = createKonvaMock();
    const stageRef = createRef<unknown>();
    const transformerRef = createRef<unknown>();

    render(
      <Stage ref={stageRef}>
        <Transformer ref={transformerRef} />
      </Stage>,
    );

    expect(stageRef.current).toBeNull();
    expect(transformerRef.current).toBeNull();
  });

  it('stageHandle_을_주면_Stage_ref_로_노출되고_읽을_때마다_최신값을_돌려준다', () => {
    // 포인터 좌표를 흉내 내는 테스트가 렌더 이후에 좌표를 바꾸므로, 핸들을 렌더 시점에 고정하면
    // 낡은 좌표가 나온다.
    const pointer = { x: 1, y: 2 };
    const { Stage } = createKonvaMock({
      stageHandle: () => ({ getPointerPosition: () => pointer }),
    });
    const ref = createRef<{ getPointerPosition: () => { x: number; y: number } }>();

    render(<Stage ref={ref} />);
    expect(ref.current?.getPointerPosition()).toEqual({ x: 1, y: 2 });

    pointer.x = 30;
    pointer.y = 40;
    expect(ref.current?.getPointerPosition()).toEqual({ x: 30, y: 40 });
  });
});

describe('react-konva 공유 목 — 단일 진실원 유지', () => {
  /**
   * 여기만 소스를 스캔한다. 위 행위 테스트들은 **공유 팩토리 하나만** 검증하므로, 누군가 목을 다시
   * 손으로 복제하면 그 복제본은 어떤 계약 검사도 받지 않는다(전임 소스 스캔 가드는 새 복제본도
   * 자동으로 검사 대상에 넣었다 — 그 성질만 여기로 옮겼다).
   */
  const MOCK_MARKERS = ["vi.mock('react-konva'", 'vi.mock("react-konva"'];
  const FACTORY_CALL = 'createKonvaMock';

  const konvaMockFiles = fs
    .readdirSync(SRC_DIR, { recursive: true, encoding: 'utf-8' })
    .filter((f) => /\.test\.tsx?$/.test(f))
    .map((f) => path.join(SRC_DIR, f))
    // 이 파일 자신은 제외한다 — 위 마커 문자열을 설명용으로 들고 있다.
    .filter((f) => path.basename(f) !== path.basename(__filename))
    .filter((f) => MOCK_MARKERS.some((m) => fs.readFileSync(f, 'utf-8').includes(m)));

  it('★검사할_konva_목이_실제로_존재한다_가드_공회전_방지', () => {
    // 스캔 결과가 0건이면 아래 검사는 "위반 없음"으로 항상 통과한다. 목이 사라졌거나 표기가 바뀌어
    // 검사기가 눈이 먼 상태를 여기서 먼저 드러낸다.
    expect(
      konvaMockFiles.length,
      'konva 목을 하나도 찾지 못했다 — 목이 전부 사라졌거나 vi.mock 표기가 바뀌어 이 가드가 헛돌고 있다',
    ).toBeGreaterThan(0);
  });

  it('★react-konva_목은_공유_팩토리로만_만든다_손복제_재분열_차단', () => {
    const offenders = konvaMockFiles
      .filter((f) => !fs.readFileSync(f, 'utf-8').includes(FACTORY_CALL))
      .map((f) => path.relative(SRC_DIR, f));

    expect(
      offenders,
      'react-konva 목을 손으로 복제했다 — 복제본은 이 파일의 행위 계약(자식 전달·data-konva·prop 투영)을\n' +
        '한 건도 검증받지 않는다. 예전에 이런 복제본이 30종 변형으로 갈라져 있었다.\n' +
        `고치는 법: vi.mock('react-konva', () => createKonvaMock({ /* 필요하면 onNode·stageHandle */ }));\n` +
        offenders.join('\n'),
    ).toEqual([]);
  });
});
