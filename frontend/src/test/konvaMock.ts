// react-konva 목(mock) 공유 팩토리 — 캔버스 계열 테스트가 쓰는 단 하나의 목 정의.
//
// ★ 왜 한 곳으로 모았나 (2026-08-09)
//   캔버스 테스트 67개 파일이 각자 react-konva 목을 복제해 두고 있었고, 정규화해서 세어 보니
//   **변형이 30종**이었다(노출 컴포넌트 6~9종 · `React.createElement` vs 임포트한
//   `createElement` · prop 필터링 유무 · `data-*` 직렬화 대상 제각각). 복제본이라
//   "목이 지켜야 할 계약"을 **실행으로 검증할 방법이 없었고**, 그래서 계약 검사를 소스 스캔에
//   의존해야 했다. 목이 한 곳에 있으면 계약을 그냥 렌더해서 확인할 수 있다.
//
// ★ 이 목이 지키는 계약 (konvaMock.contract.test.tsx 가 렌더로 고정한다)
//   ① 자식을 그대로 통과시킨다 — Stage/Layer/Group 안에 중첩된 도형이 사라지지 않는다.
//      중첩 없이 렌더하는 대다수 테스트는 자식이 통째로 사라져도 통과하므로, 이 계약이 깨지면
//      아무 신호 없이 조용히 사라진다. 그래서 별도로 못박는다.
//   ② `data-konva="{노드명}"` 으로 노드 종류를 드러낸다 — 테스트의 유일한 조회 수단이다.
//   ③ 캔버스 전용 prop 을 DOM 이 읽을 수 있는 형태로 옮긴다(아래 표).
//
// ★ prop 투영 규칙 — 30벌의 **합집합**이다
//   개별 변형이 하던 처리를 전부 켠 상태가 기본값이다. 어떤 변형이 하던 처리를 빼면 그 파일의
//   단언이 깨지고, 반대로 더해도 다른 파일은 "쓰지 않는 속성이 하나 더 붙을 뿐"이라 안전하다.
//   그래서 교집합이 아니라 합집합을 기본값으로 둔다.
//
//   | 원본 prop      | DOM 투영            | 이유 |
//   |---------------|---------------------|------|
//   | `dash`        | `data-dash`         | 배열이라 그대로 넘기면 읽을 수 없다. 점선 여부 단언에 쓰인다 |
//   | `points`      | `data-points`       | 위와 같음. 폴리곤 꼭짓점 단언에 쓰인다 |
//   | `closed`      | `data-closed`       | 폴리곤 닫힘 여부 |
//   | `draggable`   | `data-draggable`    | 잠금/읽기전용 단언축. 원본 이름 그대로 두면 HTML 표준 속성과 겹친다 |
//   | `onDblClick`  | `onDoubleClick`     | konva 표기를 DOM 표기로 — `fireEvent.doubleClick` 이 닿게 한다 |
//   | `image`       | (버림)               | HTMLImageElement 라 DOM 속성으로 옮길 수 없다 |
//   | `listening`   | (버림)               | konva 히트테스트 전용 축이라 DOM 에 대응이 없다 |
//   | 그 밖         | 그대로 spread       | `stroke`·`fill`·`name`·이벤트 핸들러 등을 테스트가 직접 읽는다 |
//
//   ⚠ 버리는 두 prop 도 `onNode` 로는 **원본 그대로** 전달된다 — `listening` 을 세는 테스트가
//     실재하므로(격자 레이어가 히트테스트에 참여하지 않는지) DOM 에서 뺐다고 관측까지 막으면 안 된다.
//
// ★ ref
//   모든 노드가 `forwardRef` 다. 기본 핸들은 `null` 이라 ref 를 받는 쪽(`transformerRef`·
//   `nodeRef`·`stageRef`)의 null 가드가 그대로 성립한다 — 복제본 시절(일반 함수 컴포넌트라
//   React 가 ref 를 무시하던 때)과 관측 결과가 같다. 포인터 좌표를 흉내 내야 하는 테스트만
//   `stageHandle` 로 Stage 핸들을 준다.

import {
  createElement,
  forwardRef,
  useImperativeHandle,
  type ForwardRefExoticComponent,
  type ReactNode,
  type RefAttributes,
} from 'react';

/**
 * 목이 노출하는 노드 목록. react-konva 실제 export 의 부분집합이며, 실 사용처
 * (`CanvasShell` · `LabelsLayer` · `OverlayLayer` · `ImageLayer` · 검수 `LabelCanvas`)가
 * 쓰는 7종에 `Group`·`Text` 를 더한 것이다.
 *
 * 쓰지 않는 노드를 함께 노출해도 해가 없다 — 렌더되지 않으면 그냥 미사용 export 다. 반대로
 * 빠뜨리면 그 노드를 렌더하는 순간 `Element type is invalid` 로 죽는다. 그래서 넓게 잡는다.
 */
export const KONVA_COMPONENT_NAMES = [
  'Stage',
  'Layer',
  'Group',
  'Image',
  'Rect',
  'Line',
  'Circle',
  'Text',
  'Transformer',
] as const;

export type KonvaComponentName = (typeof KONVA_COMPONENT_NAMES)[number];

/** 노드에 전달된 props (children 제외). */
export type KonvaNodeProps = Record<string, unknown>;

export interface KonvaMockOptions {
  /**
   * 노드가 렌더될 때마다 **가공 전 원본 props** 로 호출된다. DOM 으로 드러낼 수 없는 축
   * (`listening`·`onDragEnd` 같은 konva 콜백)을 관측하거나, 핸들러를 붙잡아 직접 호출하는 데 쓴다.
   *
   * ⚠ 렌더 중에 호출되므로 여기서 상태를 바꾸지 말 것. 수집만 한다.
   */
  onNode?: (name: KonvaComponentName, props: KonvaNodeProps) => void;

  /**
   * Stage 의 ref 로 노출할 핸들을 만든다. 미지정이면 `null` — 즉 `stageRef.current` 가 null 이라
   * 소비처의 `?.` 가드가 그대로 동작한다.
   *
   * 렌더 시점이 아니라 **ref 를 읽는 시점**에 호출되므로, 테스트가 나중에 바꾼 좌표를 반영할 수 있다.
   */
  stageHandle?: () => unknown;
}

type KonvaMockComponent = ForwardRefExoticComponent<
  { children?: ReactNode } & KonvaNodeProps & RefAttributes<unknown>
>;

export type KonvaMockModule = Record<KonvaComponentName, KonvaMockComponent>;

/** 배열 prop 을 DOM 속성으로 읽을 수 있게 편다. */
function serialize(value: unknown): string {
  return Array.isArray(value) ? value.join(',') : String(value);
}

/**
 * konva 노드 props → DOM 속성. 규칙은 이 파일 상단의 표가 정본이며, 여기가 그 유일한 구현이다.
 */
function toDomProps(name: KonvaComponentName, props: KonvaNodeProps): Record<string, unknown> {
  const {
    // DOM 으로 옮길 수 없는 축 — 버린다(원본은 onNode 로 나간다).
    image: _image,
    listening: _listening,
    // 이름을 바꿔 옮기는 축.
    dash,
    points,
    closed,
    draggable,
    onDblClick,
    ...rest
  } = props;

  const dom: Record<string, unknown> = { 'data-konva': name, ...rest };
  if (dash !== undefined) dom['data-dash'] = serialize(dash);
  if (points !== undefined) dom['data-points'] = serialize(points);
  if (closed !== undefined) dom['data-closed'] = String(closed);
  if (draggable !== undefined) dom['data-draggable'] = String(draggable);
  if (typeof onDblClick === 'function') dom.onDoubleClick = onDblClick;
  return dom;
}

/**
 * 호출 형태 — **팩토리 안에서 동적 import** 한다.
 *
 * ```ts
 * vi.mock('react-konva', async () => (await import('@/test/konvaMock')).createKonvaMock());
 * ```
 *
 * ⚠ 최상위 import 로 받아 쓰는 형태(`vi.mock('react-konva', () => createKonvaMock())`)도 대개
 *   동작하지만 **import 순서에 의존한다** — `vi.mock` 은 호이스팅되고 팩토리는 react-konva 가 처음
 *   요청되는 시점(= 테스트 대상 컴포넌트를 import 하는 시점)에 실행되므로, 이 모듈의 import 문이
 *   대상 컴포넌트의 import 문보다 **아래**에 있으면 팩토리가 아직 초기화되지 않은 바인딩을 읽는다.
 *   동적 import 는 그 순서 의존이 아예 없고, import/order 린트 그룹도 흐트러뜨리지 않는다.
 */
export function createKonvaMock(options: KonvaMockOptions = {}): KonvaMockModule {
  const build = (name: KonvaComponentName): KonvaMockComponent => {
    const Node = forwardRef<unknown, { children?: ReactNode } & KonvaNodeProps>(
      function KonvaMockNode({ children, ...rest }, ref) {
        options.onNode?.(name, rest);
        useImperativeHandle(
          ref,
          // 의존성을 비워 둔다 — 핸들 함수가 매 호출마다 최신 값을 읽으므로 재생성이 필요 없고,
          // 재생성하면 ref 가 렌더마다 바뀌어 소비처의 useEffect 가 불필요하게 다시 돈다.
          () => (name === 'Stage' ? (options.stageHandle?.() ?? null) : null),
          [],
        );
        // children 은 인덱스 시그니처와 교차돼 `unknown` 으로 좁혀지므로 단언이 필요하다.
        return createElement('div', toDomProps(name, rest), children as ReactNode);
      },
    );
    Node.displayName = `KonvaMock(${name})`;
    return Node;
  };

  return Object.fromEntries(
    KONVA_COMPONENT_NAMES.map((name) => [name, build(name)]),
  ) as KonvaMockModule;
}
