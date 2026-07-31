import {
  forwardRef,
  useEffect,
  useImperativeHandle,
  useRef,
  useState,
  type RefObject,
} from 'react';
import { Circle, Line, Rect } from 'react-konva';
import type Konva from 'konva';

import {
  isEditBlockedNow,
  useIsEditBlocked,
  useLabelStore,
  type BusyKind,
} from '@/stores/useLabelStore';

import { handleBusyEscape, isBusyOverlayShownFor } from '../../busyPolicy';
import { useBlockNotice } from '../../hooks/useBlockNotice';
import { busyRejectedMessage } from '../../hooks/useBusyTask';
import { useLabelMasters } from '../../hooks/useLabelMasters';
import type { Sam2SegmentRequest, Sam2SegmentResponse } from '../../api';
import type { Label, ToolType } from '../../types';
import { COCO_SKELETON, KEYPOINT_NAMES, ToolType as ToolTypeEnum } from '../../types';
import { isValidBox, normalizeBox } from '../utils/canvasGeometry';
import {
  clampToImage,
  translateFromCanvas,
  translateToCanvas,
  type Geometry,
  type Point,
} from '../utils/coordinateTransformer';
import { skeletonEdgeToCanvasLine } from '../utils/keypointHelpers';
import { closePolygonIfNear, simplifyPolygon, validatePolygonPoints } from '../utils/polygonHelpers';

import { resolveDefaultLabel } from './resolveDefaultLabel';

interface OverlayLayerProps {
  geometry: Geometry;
  activeTool: ToolType;
  onLabelAdd?: (label: Label) => void;
  stageRef: RefObject<Konva.Stage | null>;
  /**
   * SAM2 클릭/박스 분할 요청 함수 (SAM_SEGMENT 활성 시 주입).
   * 진행 중 무시·프레임 전환 stale 폐기는 호출자(useSam2Segment)가 책임지며 폐기 시 null 반환.
   */
  segment?: (payload: Omit<Sam2SegmentRequest, 'srcSn'>) => Promise<Sam2SegmentResponse | null>;
  /**
   * 현재 프레임 PK. **프레임 동일성 판정의 안정적 식별자**로만 쓴다(요청은 `segment` 가 수행).
   * `segment` 함수 참조는 경계 세밀함 슬라이더 조절 등으로도 바뀌므로 프레임 전환과 구별되지
   * 않는다 — 그 참조로 판정하면 슬라이더를 움직였을 뿐인데 큐잉된 확정이 조용히 폐기된다.
   * 미주입 시에만 종전처럼 `segment` 참조를 대용 키로 사용한다(하위호환).
   */
  srcSn?: number;
  /** mock 응답(모델 미로드) 수신 시 호출 — 경고 표시 + 자동 적용 차단. */
  onMockWarning?: (res: Sam2SegmentResponse) => void;
  /** 낮은 신뢰도(score < 0.3) 응답 수신 시 호출 — 적용 여부 안내. */
  onLowConfidence?: (res: Sam2SegmentResponse) => void;
  /** 폴리곤 커밋이 라벨 마스터 미로딩 등으로 실패했을 때 사용자 안내 메시지 전달. */
  onCommitError?: (message: string) => void;
  /**
   * KEYPOINT 순차 배치 중 "지금 찍을 관절"의 0-based 인덱스(0~16)를 상위에 보고.
   * 배치 미진행(툴 비활성) 또는 17점 완료 시 null. 캔버스 밖 인체 다이어그램 가이드 연동용.
   */
  onKeypointPlacingChange?: (placingIndex: number | null) => void;
  /**
   * AI 분할 즉시 그리기(프리뷰) 모드. true 면 SAM_SEGMENT 클릭 도구에서 클릭마다
   * 누적 점 전체로 즉시 분할 요청 → 프리뷰 폴리곤을 오버레이하고, 확정(Enter/더블클릭) 시
   * 프리뷰를 실제 라벨로 커밋한다. false(기본)면 "누적 후 확정 시 1회 요청" 기존 동작 유지.
   */
  immediateSegment?: boolean;
  /**
   * SAM2 분할 요청이 현재 in-flight 인지(useSam2Segment). 즉시 그리기 모드에서 확정(Enter/더블클릭)
   * 시점에 재요청이 필요한데 in-flight 라면, useSam2Segment 의 inflight 가드가 재요청을 null 로
   * 드롭시켜 아무것도 커밋되지 않는 "조용한 작업 소실"이 발생한다. 이 플래그가 true 면 확정을
   * 큐잉했다가 false 로 풀리는 순간 최신 누적점 전체로 재요청→커밋한다.
   *
   * ⚠ 이 값만으로 큐잉을 판정하면 안 된다 — `isSegmenting` 은 **분할 종류만** 보는 반면 실제
   *   드롭 조건은 **종류 무관 배타 실행**이다. 저장/AI 탐지/AI 추적 중에 확정하면 가드는 통과하고
   *   요청만 드롭돼 누적점이 무음으로 사라진다. 그래서 아래에서 store busy(useIsEditBlocked)를
   *   함께 본다.
   */
  isSegmenting?: boolean;
}

interface BboxDraft {
  start: Point; // canvas 좌표
  current: Point;
}

/**
 * OverlayLayer 가 상위(CanvasShell→LabelingPage)에 노출하는 명령 핸들.
 * 폴리곤 편집 state 가 OverlayLayer 내부에 캡슐화돼 있어, 키보드 단축키(F/Q)가
 * 마우스 클릭과 동일한 폴리곤 로직을 호출할 수 있도록 imperative handle 로 중계한다.
 */
export interface OverlayLayerHandle {
  /** F — 현재 포인터 위치를 폴리곤 점으로 추가(마우스 클릭과 동일 경로). POLYGON 도구 아니면 no-op. */
  addPointAtPointer: () => void;
  /** Q — 진행 중 폴리곤을 커밋. 점이 부족하면 no-op(draft 유지 — 오조작 방지). */
  completePolygon: () => void;
}

/**
 * 활성 도구의 임시 그리기 오버레이.
 * - BBOX: pointerdown → drag → pointerup 으로 박스 생성
 * - POLYGON: 클릭으로 점 추가, dblclick 또는 시작점 근접 시 닫기
 *
 * Phase 7: 신규 라벨은 activeLabelId 의 라벨 마스터로부터 classId/className 을 적용.
 *   activeLabelId 가 null 이면 sortNo 최소 활성 라벨로 fallback.
 *   labelMasters 가 비어 있으면 신규 BBOX/Polygon 생성 거부 (안전장치).
 */
/** SAM2 분할 자동 적용 차단 임계 — 이 미만이면 낮은 신뢰도 안내. */
const SAM_LOW_CONFIDENCE_THRESHOLD = 0.3;

/**
 * 프레임 동일성 판정 키. srcSn 이 주입되면 그 값, 아니면 `segment` 참조(하위호환).
 * 값 비교(===)로만 쓰이며 이 키로 요청을 보내지 않는다.
 */
type FrameKey = number | OverlayLayerProps['segment'];

export const OverlayLayer = forwardRef<OverlayLayerHandle, OverlayLayerProps>(function OverlayLayer(
  {
    geometry,
    activeTool,
    onLabelAdd,
    stageRef,
    segment,
    srcSn,
    onMockWarning,
    onLowConfidence,
    onCommitError,
    onKeypointPlacingChange,
    immediateSegment = false,
    isSegmenting = false,
  }: OverlayLayerProps,
  ref,
) {
  const [bboxDraft, setBboxDraft] = useState<BboxDraft | null>(null);
  // SAM_SEGMENT 박스 드래그 draft (canvas 좌표).
  const [segDraft, setSegDraft] = useState<BboxDraft | null>(null);
  // R6 — SAM_SEGMENT 다중 positive-click 누적(이미지 좌표). 확정(Enter/더블클릭) 시 1회 요청.
  const [segPoints, setSegPoints] = useState<Point[]>([]);
  // 즉시 프리뷰 모드에서 마지막 유효 분할 결과 폴리곤(flat, 이미지 px). null=프리뷰 없음.
  const [segPreview, setSegPreview] = useState<number[] | null>(null);
  // 이월 MED — 확정 재요청이 in-flight 로 드롭될 상황에서 확정 의도를 큐잉(조용한 소실 방지).
  // true=대기 중. isSegmenting 이 false 로 풀리면 effect 가 최신 누적점으로 재요청→커밋한다.
  const [pendingConfirm, setPendingConfirm] = useState(false);
  // 큐잉 시점의 프레임 키. 프레임이 바뀌면 값이 달라지므로 옛 프레임의 확정을 폐기한다.
  const pendingConfirmFrameRef = useRef<FrameKey>(undefined);
  const [polyPoints, setPolyPoints] = useState<number[]>([]);
  // KEYPOINT 순차 배치 draft — 배치된 관절(이미지 좌표) 0..17 개. 17개 채워지면 커밋.
  const [kptDraft, setKptDraft] = useState<{ x: number; y: number; v: number }[]>([]);

  // SAM_SEGMENT: 박스 드래그 후 발생하는 click 이벤트가 포인트로 잘못 처리되지 않도록 억제.
  const segSuppressClickRef = useRef(false);
  // 차단 구간에서 시작된 SAM_SEGMENT 제스처의 mousedown 지점(canvas 좌표). 드래프트를 만들지 않고
  // 위치만 기억한다 — mouseup 에서 "클릭(누적 성립)" 과 "드래그(큐 없음 = 실패)" 를 구별하기 위함.
  const segBlockedDownRef = useRef<Point | null>(null);

  // R6 — 누적 클릭/확정·취소 핸들러를 ref 로 보관해 window 키보드 리스너가 최신 값을 참조하도록 한다
  // (리스너는 activeTool 에만 재구독, 매 클릭마다 재구독 방지).
  const segPointsRef = useRef<Point[]>([]);
  segPointsRef.current = segPoints;
  // 즉시 프리뷰 모드 전용 — 확정 핸들러가 매 렌더 최신 프리뷰를 동기 참조하도록 미러링.
  const segPreviewRef = useRef<number[] | null>(null);
  segPreviewRef.current = segPreview;
  // 즉시 프리뷰가 "몇 개의 누적점"으로 만들어졌는지(최신성 판정용). null=프리뷰 없음/미추적.
  const segPreviewForCountRef = useRef<number | null>(null);
  // 즉시 요청 세대 토큰. 즉시 요청마다 증가시켜 늦게 도착한(무효화된) 응답이 프리뷰를
  // 되살리지 못하게 한다(확정/취소/도구·프레임 전환 시에도 증가시켜 무효화).
  const segGenRef = useRef(0);
  const confirmSegmentRef = useRef<() => void>(() => {});
  const cancelSegmentRef = useRef<() => void>(() => {});

  // 프레임 동일성 키(srcSn 우선) — 비동기 콜백이 "발사 시점 프레임" 과 비교할 수 있도록 ref 미러링.
  const frameKey: FrameKey = srcSn ?? segment;
  const frameKeyRef = useRef<FrameKey>(frameKey);
  frameKeyRef.current = frameKey;
  // 현재 활성 도구 — 비동기 콜백이 "지금도 분할 도구인가" 를 동기 확인한다.
  const activeToolRef = useRef<ToolType>(activeTool);
  activeToolRef.current = activeTool;

  const { data: labelMasters } = useLabelMasters();
  const activeLabelId = useLabelStore((s) => s.activeLabelId);
  // 종류 무관 배타 실행 판정(저장/AI 탐지/AI 분할/AI 추적/불러오기). 확정 큐 effect 의 트리거로
  // 쓰기 위해 구독한다 — busy 가 풀리는 순간 큐가 재시도되어야 한다.
  const busyBlocked = useIsEditBlocked();
  // window 키보드 리스너(activeTool 에만 재구독)가 최신 차단 상태를 참조하도록 미러링.
  const busyBlockedRef = useRef(busyBlocked);
  busyBlockedRef.current = busyBlocked;

  /**
   * 지금 분할 요청이 드롭될 상황인가. **렌더 값이 아니라 store 실시간 값**을 함께 본다 —
   * 렌더 사이(이벤트 핸들러 실행 시점)에 시작된 busy 는 구독 값에 아직 반영되지 않는다.
   * 판정이 실제 드롭 조건(beginBusy 실패)보다 좁으면 누적 클릭이 무음으로 사라진다.
   */
  function isSegmentBlockedNow(): boolean {
    return isSegmenting || isEditBlocked();
  }

  /**
   * 편집 입력 차단 판정 — 렌더 값(구독)과 실시간 store 값 중 **하나라도** 막혀 있으면 막는다.
   * 판정이 갈릴 때 열리는 쪽이 아니라 막히는 쪽으로 붙인다(fail-closed).
   */
  function isEditBlocked(): boolean {
    return busyBlocked || isEditBlockedNow();
  }

  /**
   * 진행 오버레이가 **지금 이 프레임 캔버스를 덮고 있는가**.
   *
   * ⚠ 판정은 **오버레이의 실제 렌더 조건과 같은 축**(프레임 스코프)이어야 한다 — 스코프를 빼면
   *   다른 프레임의 작업이 도는 동안 오버레이가 없는데도 이 판정만 참이 되어, 클릭이 안내 없이
   *   사라진다(NF-3 무음 드롭). 판정 본체는 busyPolicy 단일 소스에 있다.
   *
   * ★ 정책(Phase 3 DEV_FIX D3) — **오버레이가 보이면 완전 차단, 안 보이면(지연 창 <300ms) 누적 허용.**
   *   Phase 2 가 분할 클릭 누적을 차단 구간에도 열어둔 근거는 "오버레이가 없어서 사용자가 진행
   *   중임을 모른다" 였다. 오버레이가 떠 있으면 그 전제가 사라진다 — 사용자는 이미 상태를 보고
   *   있으므로 클릭이 무시되는 것은 무음 소실이 아니다. (실제로도 오버레이 백드롭이 포인터
   *   이벤트를 흡수해 클릭이 캔버스에 도달하지 못하므로, 이 판정이 없으면 코드가 말하는 계약과
   *   화면의 실제 동작이 어긋난 채로 남는다.)
   */
  function isBusyOverlayShown(): boolean {
    return isBusyOverlayShownFor(useLabelStore.getState(), srcSn);
  }

  /**
   * 차단 안내 발행부(같은 조작 연타 시 도배 방지). dedupe 정책은 화면 공통 하나를 쓴다 —
   * P-1: 여기만 **시각 기반**이라 사유가 달라도 두 번째 안내가 통째로 삼켜졌다.
   */
  const pushBlockNotice = useBlockNotice();

  /**
   * 차단된 조작을 사용자에게 알린다. **작업 결과를 잃는 조작이 무음으로 사라지지 않게** 하는 것이
   * 목적이라, 진행 중인 작업 종류만 알린다(내부 경로·식별자·좌표는 담지 않는다).
   *
   * ⚠ **오버레이 미표시 구간(지연 창 <300ms) 전용 경로**다(D3). 오버레이가 뜬 뒤에는 백드롭이
   *   포인터 이벤트를 흡수해 캔버스 핸들러 자체가 실행되지 않고, 그때는 진행 상태가 화면에
   *   보이므로 안내도 불필요하다. 지연 창 안에서는 여전히 도달하며 이 안내가 유일한 단서다.
   *
   * @param attemptKind 이 조작이 시작하려던 작업 종류. **같은 종류가 막은 거부는 무음**이다 —
   *   진행 인디케이터가 이미 상태를 보여주고 사용자가 취할 조치도 없다(`runExclusiveOrNotify` 와
   *   동일 규칙). 라벨을 직접 만드는 조작(폴리곤 점·키포인트)처럼 대응하는 작업 종류가 없으면 생략.
   */
  function notifyBlocked(attemptKind?: BusyKind) {
    const blockedBy = useLabelStore.getState().busy?.kind ?? null;
    if (attemptKind !== undefined && blockedBy === attemptKind) return;
    pushBlockNotice(busyRejectedMessage(blockedBy));
  }

  /**
   * 박스 프롬프트(드래그)가 처리되지 못했음을 알린다.
   *
   * ⚠ **자기 종류(AI 분할)가 막았어도 알린다** — 자기 종류 무음 규칙은 "거부돼도 그 조작이 다른
   * 경로로 성사된다"(클릭 누적 → 확정 큐)를 전제로 한다. 박스 프롬프트에는 큐도 복원도 없어
   * 조작이 통째로 소멸하므로 무음이면 사용자는 분할이 진행되는 줄 알고 기다린다.
   *
   * ⚠ 위 notifyBlocked 와 같이 **오버레이 미표시 구간(지연 창 <300ms) 전용**이다(D3) — 드래그
   *   도중 오버레이가 떠도 mouseup 은 백드롭이 흡수하므로, 이 안내가 뜨는 것은 오버레이가 아직
   *   없는 구간뿐이다.
   *
   * 문구는 **발사 시점 실시간 판정**으로 정한다. 아직 막혀 있으면 진행 중 작업을 알리고, 그 사이
   * 작업이 끝났다면 "진행 중" 이라고 말하지 않는다(사실과 다른 안내 금지).
   */
  function notifySegmentBoxDropped() {
    if (isSegmentBlockedNow()) {
      pushBlockNotice(busyRejectedMessage(useLabelStore.getState().busy?.kind ?? null));
      return;
    }
    pushBlockNotice('작업이 진행 중일 때 시작한 드래그라 처리되지 않았습니다. 다시 그려주세요.');
  }

  // 도구가 SAM_SEGMENT 가 아니게 되면 진행 중 분할 박스 draft + 누적 클릭 초기화.
  useEffect(() => {
    if (activeTool !== ToolTypeEnum.SAM_SEGMENT) {
      setSegDraft(null);
      setSegPoints([]);
      setSegPreview(null);
      setPendingConfirm(false); // 확정 큐 취소(도구 전환 시 유령 커밋 방지)
      segGenRef.current += 1; // 진행 중 프리뷰 응답 무효화(도구 전환 후 유령 방지)
      segSuppressClickRef.current = false;
      segBlockedDownRef.current = null;
    }
    // KEYPOINT 도구를 벗어나면 진행 중 배치 draft 폐기(부분 배치 조용한 소실 방지 겸 초기화).
    if (activeTool !== ToolTypeEnum.KEYPOINT) {
      setKptDraft([]);
    }
    // POLYGON 도구를 벗어나면 진행 중 폴리곤 draft 초기화(다른 도구로 전환 시 스테일 점 잔존 방지).
    if (activeTool !== ToolTypeEnum.POLYGON) {
      setPolyPoints([]);
    }
  }, [activeTool]);

  // R6 — 프레임 전환 시 누적 클릭 초기화(스테일 포인트 잔존 방지).
  // 판정은 frameKey — srcSn 이 있으면 그 값만 보므로, 같은 프레임에서 분할 옵션(경계 세밀함)만
  // 바꿔 segment 참조가 교체돼도 초기화되지 않는다.
  useEffect(() => {
    setSegPoints([]);
    setSegPreview(null);
    setPendingConfirm(false); // 확정 큐 취소(프레임 전환 시 유령 커밋 방지)
    segGenRef.current += 1; // 진행 중 프리뷰 응답 무효화(프레임 전환 후 유령 방지)
    // 진행 중 제스처 상태도 정리한다 — 차단 구간의 mousedown 뒤 캔버스 밖에서 release 하면
    // Rect 의 mouseup 이 발화하지 않아 ref 가 잔존하고, 다음 mouseup 이 "차단 제스처"로 오판돼
    // 뒤따르는 클릭 1점이 조용히 삼켜진다(도구 전환 effect 와 같은 정리를 프레임 축에도 건다).
    segBlockedDownRef.current = null;
    segSuppressClickRef.current = false;
  }, [frameKey]);

  // 이월 MED — 확정 큐 처리. 재요청이 드롭될 상황(분할 in-flight 또는 다른 장시간 작업 진행 중)에서
  // 큐잉된 확정을, 그 상황이 풀리는 순간 최신 누적점 전체로 재요청→커밋한다(조용한 작업 소실 제거).
  // 큐가 새 클릭/취소/도구·프레임 전환으로 취소되면 pendingConfirm=false 라 no-op.
  //
  // ⚠ effect 실행 순서에 기대지 않는다 — 같은 렌더에서 도구/프레임이 바뀌고 busy 도 풀리면,
  //   위 초기화 effect 가 pendingConfirm 을 내려도 **이 effect 는 같은 렌더의 옛 값(true)** 을
  //   보고 실행돼 유령 커밋이 난다(실측). 그래서 도구·프레임 컨텍스트를 여기서 직접 확인한다.
  useEffect(() => {
    if (!pendingConfirm || isSegmenting || busyBlocked) return;
    if (activeTool !== ToolTypeEnum.SAM_SEGMENT) return; // 도구를 떠났으면 확정하지 않는다
    if (pendingConfirmFrameRef.current !== frameKey) return; // 프레임이 바뀌었으면 폐기
    // 렌더 값이 낡았을 수 있으므로 발사 직전 store 실시간 재확인 — 아직 막혀 있으면 큐를 유지한다
    // (busy 가 풀리면 busyBlocked 변화로 이 effect 가 다시 돈다).
    if (isSegmentBlockedNow()) return;
    const pts = segPointsRef.current;
    setPendingConfirm(false);
    segGenRef.current += 1; // 앞선 프리뷰 응답 무효화
    setSegPreview(null);
    if (pts.length === 0 || !segment) {
      setSegPoints([]);
      return;
    }
    dispatchPointsConfirm(segment, pts);
    // applySegmentResult/segPointsRef 는 매 렌더 최신 참조 — deps 는 트리거 값만 둔다.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [pendingConfirm, isSegmenting, busyBlocked, frameKey]);

  // R6 — SAM_SEGMENT 진행 중 Enter=확정 / Esc=취소. 도구 활성 시에만 구독(자기완결).
  useEffect(() => {
    if (activeTool !== ToolTypeEnum.SAM_SEGMENT) return;
    const onKey = (e: KeyboardEvent) => {
      const t = e.target as HTMLElement | null;
      const tag = t?.tagName;
      // 입력 필드 포커스 중에는 텍스트 편집 보존(확정/취소 억제).
      if (tag === 'INPUT' || tag === 'TEXTAREA' || t?.isContentEditable) return;
      if (e.key === 'Enter') {
        // ★ 진행 오버레이가 떠 있으면 캔버스 확정은 **오버레이 '작업 취소' 버튼에 양보**한다(NF-1).
        //   오버레이는 뜨는 순간 취소 버튼으로 포커스를 옮기는데, 여기서 preventDefault 하면 그
        //   버튼의 Enter 활성화가 취소된다 — 취소는 일어나지 않고 확정만 큐잉돼, 사용자가 취소하려던
        //   그 작업이 busy 해제 시 그대로 재발사된다(취소가 정반대로 동작). 어차피 오버레이가 뜬
        //   구간의 캔버스 확정은 차단 대상이다(D3).
        if (isBusyOverlayShown()) return;
        // 누적 포인트가 있을 때만 확정 + preventDefault. 비어 있으면 button/a/select 등
        // 포커스 요소의 Enter 기본 동작(활성화)을 가로채지 않는다(a11y).
        if (segPointsRef.current.length === 0) return;
        e.preventDefault();
        confirmSegmentRef.current();
      } else if (e.key === 'Escape') {
        // Phase 3 — 차단 구간의 ESC 는 **진행 중 작업 취소**다. 드래프트 파기가 아니다:
        // 취소 수단이 없던 Phase 2 에서는 ESC 가 누적점만 지우고 작업은 그대로 남겨 사용자가
        // 취소한 적 없는 작업을 잃었다. 이제 작업만 취소하고 누적점은 보존한다(R9/AC10).
        // 리스너는 activeTool 에만 재구독하므로 렌더 값은 ref 로, 실시간 값은 store 로 본다(fail-closed).
        // 취소 판정·안내는 화면 공통 단일 헬퍼(M4)에 있다 — 지연 창 취소는 안내가 필요하다(D4).
        if (busyBlockedRef.current || isEditBlockedNow()) {
          handleBusyEscape();
          return;
        }
        cancelSegmentRef.current();
      }
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
    // isBusyOverlayShown 은 store 실시간 값만 읽는 판정이라 렌더 값에 의존하지 않는다 —
    // deps 에 넣으면 매 렌더 리스너가 재구독될 뿐이다(기존 계약: activeTool 에만 재구독).
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [activeTool]);

  // 콜백을 ref 에 보관해 인라인 함수 전달에도 보고 effect 가 매 렌더 재실행되지 않도록 한다.
  const placingChangeRef = useRef(onKeypointPlacingChange);
  placingChangeRef.current = onKeypointPlacingChange;
  // 마지막으로 보고한 값 — 동일 값 중복 보고(불필요 setState) 억제. 초기값 null 로 시작.
  const lastPlacingRef = useRef<number | null>(null);

  // "지금 찍을 관절" 인덱스를 상위(다이어그램 가이드)로 보고. 배치 미진행/완료 시 null.
  useEffect(() => {
    const placing =
      activeTool === ToolTypeEnum.KEYPOINT && kptDraft.length < KEYPOINT_NAMES.length
        ? kptDraft.length
        : null;
    if (placing !== lastPlacingRef.current) {
      lastPlacingRef.current = placing;
      placingChangeRef.current?.(placing);
    }
  }, [activeTool, kptDraft.length]);

  function pointerCanvas(): Point | null {
    const stage = stageRef.current;
    if (!stage) return null;
    const pos = stage.getPointerPosition();
    return pos ? { x: pos.x, y: pos.y } : null;
  }

  function commitBbox(start: Point, end: Point) {
    const a = translateFromCanvas(geometry, start.x, start.y);
    const b = translateFromCanvas(geometry, end.x, end.y);
    const ca = clampToImage(geometry, a);
    const cb = clampToImage(geometry, b);
    const norm = normalizeBox(ca.x, ca.y, cb.x, cb.y);
    if (!isValidBox(norm.left, norm.top, norm.right, norm.bottom)) return;
    const def = resolveDefaultLabel(labelMasters ?? [], activeLabelId);
    if (!def) return; // 라벨 마스터 없음 — 신규 라벨 생성 거부 (Object fallback 제거)
    onLabelAdd?.({
      id: `tmp-${Date.now()}`,
      frameNo: 0,
      classId: def.labelId,
      className: def.name,
      source: 'MANUAL',
      shape: { type: 'BBOX', ...norm },
    });
  }

  /**
   * 폴리곤을 라벨로 커밋. 커밋에 성공하면 true, 검증 실패/라벨 마스터 미로딩 등으로
   * no-op 이면 false 를 반환한다. 호출처는 성공 시에만 그리던 점을 비워야 한다
   * (실패 시 점을 유지해 사용자가 그린 폴리곤이 조용히 사라지지 않도록).
   */
  function commitPolygon(points: number[]): boolean {
    const pts: number[] = [];
    for (let i = 0; i + 1 < points.length; i += 2) {
      const p = translateFromCanvas(geometry, points[i], points[i + 1]);
      const c = clampToImage(geometry, p);
      pts.push(c.x, c.y);
    }
    const valid = validatePolygonPoints(pts);
    if (!valid) return false;
    const def = resolveDefaultLabel(labelMasters ?? [], activeLabelId);
    if (!def) {
      // 라벨 마스터 없음 — 신규 라벨 생성 거부. 사용자에게 원인 안내(점은 호출처에서 유지).
      onCommitError?.('라벨 분류가 로딩되지 않아 폴리곤을 추가할 수 없습니다. 잠시 후 다시 시도하세요.');
      return false;
    }
    onLabelAdd?.({
      id: `tmp-${Date.now()}`,
      frameNo: 0,
      classId: def.labelId,
      className: def.name,
      source: 'MANUAL',
      shape: { type: 'POLYGON', points: valid },
    });
    return true;
  }

  /**
   * 진행 중 폴리곤에 canvas 좌표 1점을 추가한다. 시작점 근접 시 자동 닫힘 → 커밋 시도.
   * 마우스 클릭(onClick)과 키보드 F(addPointAtPointer)가 공유하는 단일 경로.
   */
  function addPolygonPointAt(cp: Point) {
    const next = [...polyPoints, cp.x, cp.y];
    const closed = closePolygonIfNear(next);
    if (closed.closed) {
      // 커밋 성공 시에만 점 비움 — 실패(라벨 마스터 미로딩 등)면 점 유지.
      if (commitPolygon(closed.points)) setPolyPoints([]);
    } else {
      setPolyPoints(next);
    }
  }

  /**
   * 진행 중 폴리곤 커밋 시도(점 3개 이상 + 검증 통과 시에만). 성공하면 draft 를 비우고 true 를 반환한다.
   * 점이 부족하거나 커밋 실패면 draft 를 건드리지 않고 false 를 반환한다.
   * 마우스 dblclick(finish)과 키보드 Q(completePolygon)가 공유한다.
   */
  function tryCommitPolygon(): boolean {
    if (polyPoints.length < 6) return false;
    if (commitPolygon(polyPoints)) {
      setPolyPoints([]);
      return true;
    }
    return false;
  }

  useImperativeHandle(ref, () => ({
    addPointAtPointer() {
      if (activeTool !== ToolTypeEnum.POLYGON) return; // 폴리곤 도구 아니면 무시
      if (isEditBlocked()) return; // 단축키 경로도 동일하게 차단(키보드 우회 금지)
      const cp = pointerCanvas();
      if (!cp) return;
      addPolygonPointAt(cp);
    },
    completePolygon() {
      if (isEditBlocked()) return;
      // 점 부족이면 tryCommitPolygon 이 no-op(draft 유지). 마우스 dblclick 과 달리 취소로 비우지 않음.
      tryCommitPolygon();
    },
  }));

  // 이미지 좌표 flat points 를 그대로 폴리곤으로 커밋 (SAM_SEGMENT 응답 적용 — 좌표 변환 불필요).
  function commitImagePolygon(imagePoints: number[]) {
    const clamped: number[] = [];
    for (let i = 0; i + 1 < imagePoints.length; i += 2) {
      const c = clampToImage(geometry, { x: imagePoints[i], y: imagePoints[i + 1] });
      clamped.push(c.x, c.y);
    }
    const simplified = simplifyPolygon(clamped, 1.0, 1000);
    const valid = validatePolygonPoints(simplified);
    if (!valid) return;
    const def = resolveDefaultLabel(labelMasters ?? [], activeLabelId);
    if (!def) return;
    onLabelAdd?.({
      id: `tmp-${Date.now()}`,
      frameNo: 0,
      classId: def.labelId,
      className: def.name,
      source: 'MANUAL',
      shape: { type: 'POLYGON', points: valid },
    });
  }

  /**
   * 배치 완료된 17 관절(이미지 좌표 삼중값)을 KEYPOINT 라벨로 커밋.
   * 라벨 마스터 미로딩 시 no-op(false) + 사용자 안내. 성공 시 true.
   */
  function commitKeypoints(kps: { x: number; y: number; v: number }[]): boolean {
    const def = resolveDefaultLabel(labelMasters ?? [], activeLabelId);
    if (!def) {
      onCommitError?.('라벨 분류가 로딩되지 않아 스켈레톤을 추가할 수 없습니다. 잠시 후 다시 시도하세요.');
      return false;
    }
    onLabelAdd?.({
      id: `tmp-${Date.now()}`,
      frameNo: 0,
      classId: def.labelId,
      className: def.name,
      source: 'MANUAL',
      shape: { type: 'KEYPOINT', keypoints: kps },
    });
    return true;
  }

  // KEYPOINT: 캡처 Rect 클릭마다 현재 관절 1점 배치. 17점 채워지면 커밋.
  function handleKeypointClick() {
    if (kptDraft.length >= KEYPOINT_NAMES.length) return; // 이미 17점(커밋 대기) — 추가 무시
    const cp = pointerCanvas();
    if (!cp) return;
    const img = clampToImage(geometry, translateFromCanvas(geometry, cp.x, cp.y));
    // 기본 가시성 v=2(가시). 비가시/미표기 전환은 커밋 후 편집 단계에서 처리.
    const next = [...kptDraft, { x: img.x, y: img.y, v: 2 }];
    if (next.length >= KEYPOINT_NAMES.length) {
      // 커밋 성공 시에만 draft 비움 — 실패(라벨 마스터 미로딩)면 유지해 재시도 허용.
      if (commitKeypoints(next)) setKptDraft([]);
      else setKptDraft(next);
    } else {
      setKptDraft(next);
    }
  }

  // === SAM2 분할(SAM_SEGMENT) ===
  // 응답 폴리곤([[x,y],...] image px)을 기존 폴리곤 적용 흐름으로 처리.
  // 빈 폴리곤(모델 미로드/mock) 이면 자동 적용 차단 + 경고 콜백, score 낮으면 안내 콜백.
  // asPreview=true(즉시 프리뷰 모드): 유효 폴리곤을 커밋하지 않고 프리뷰로만 표시.
  // asPreview=false(기존): 유효 폴리곤을 라벨로 커밋.
  // mock/저신뢰 게이팅은 두 모드 공통 — 프리뷰 모드에서도 자동 적용/표시 차단(프리뷰 해제).
  function applySegmentResult(
    res: Sam2SegmentResponse | null,
    opts: { asPreview: boolean; gen?: number; pointCount?: number },
  ) {
    if (!res) return; // 폐기(진행 중 무시 / 프레임 전환 stale) — 프리뷰 유지(점은 이미 누적됨)
    // 즉시 프리뷰 — 확정/취소/전환으로 세대가 증가(무효화)된 뒤 늦게 도착한 응답은 반영하지 않는다
    // (이슈1b: 확정 후 유령 프리뷰 재생 방지). 세대가 부재한 비프리뷰(확정/박스) 경로는 통과.
    if (opts.asPreview && opts.gen !== undefined && opts.gen !== segGenRef.current) return;
    // mock(모델 미로드) 신호 = 빈 폴리곤. 저신뢰(score) 분기보다 먼저 판정해야
    // "낮은 신뢰도(0%)" 로 오분류되지 않고 올바른 안내(message)가 표시된다.
    if (res.polygon.length === 0) {
      if (opts.asPreview) setSegPreview(null); // 프리뷰 표시 차단
      onMockWarning?.(res);
      return; // 자동 적용 차단 — 사용자 확인 후만 적용
    }
    if (res.score < SAM_LOW_CONFIDENCE_THRESHOLD) {
      if (opts.asPreview) setSegPreview(null); // 프리뷰 표시 차단
      onLowConfidence?.(res);
      return;
    }
    const flat: number[] = [];
    for (const pair of res.polygon) {
      if (pair.length >= 2) flat.push(pair[0], pair[1]);
    }
    if (opts.asPreview) {
      // 유효 폴리곤 → 프리뷰로만 표시(커밋 안 함). 확정 시 commitImagePolygon 으로 커밋.
      // 이 프리뷰가 반영한 누적점 수를 기록(확정 시 최신성 판정용 — 이슈1a).
      setSegPreview(flat);
      segPreviewForCountRef.current = opts.pointCount ?? null;
      return;
    }
    // clampToImage + validatePolygonPoints + simplify — 기존 폴리곤 적용 흐름과 동일.
    commitImagePolygon(flat);
  }

  // R6 — 클릭 1회당 positive-click 을 누적한다. 즉시 프리뷰 모드면 누적 점 전체로 즉시 요청.
  // 연속 동일 좌표(더블클릭 등)는 스킵해 확정 시 중복 포인트를 만들지 않는다.
  function handleSegmentClick() {
    const cp = pointerCanvas();
    if (!cp) return;
    // 새 클릭 = 사용자가 계속 그리는 중 → 대기 중이던 확정 큐 취소(사용자 의도 최신화).
    setPendingConfirm(false);
    const img = clampToImage(geometry, translateFromCanvas(geometry, cp.x, cp.y));
    // 현재 누적 점(segPointsRef 는 매 렌더 최신 동기화)에서 다음 누적 배열을 계산.
    // 함수형 setState 업데이터는 이벤트 핸들러 내에서 동기 실행되지 않으므로(배칭),
    // 즉시 요청에 필요한 nextPts 는 ref 로 확정한다.
    const prev = segPointsRef.current;
    const last = prev[prev.length - 1];
    const nextPts = last && last.x === img.x && last.y === img.y ? prev : [...prev, img];
    setSegPoints(nextPts);
    // 즉시 프리뷰 모드: 누적 점 전체로 즉시 분할 요청 → 프리뷰 렌더.
    // ⚠ 차단 구간에서는 **요청을 아예 보내지 않는다** — 보내봐야 거부되는데, 그 거부는 사용자가
    //   할 수 있는 일이 없는 정상 동선(점 누적 + 확정 큐)에 "완료 후 다시 시도" 라는 사실과 다른
    //   안내를 클릭마다 띄운다. 프리뷰만 생략되고 누적·확정은 그대로 살아 있다.
    // 세대(gen) + 요청 시점 누적점 수(pointCount)를 함께 발급해, 늦게 온 응답은 무효화하고
    // 프리뷰의 최신성(확정 시 point-count 일치)을 판정한다.
    // ⚠ 판정은 `isEditBlocked()`(store busy) 뿐이다 — `isSegmenting`(요청 in-flight)까지 넣으면
    //   같은 분할 요청이 도는 동안의 프리뷰 갱신 요청까지 사라진다. 그건 정상 동선이고, 뒤에
    //   도착하는 최신 프리뷰를 세대(gen)로 골라내는 기존 계약이 담당한다.
    if (immediateSegment && segment && !isEditBlocked()) {
      const gen = (segGenRef.current += 1);
      const pointCount = nextPts.length;
      const points = nextPts.map((p) => [p.x, p.y]);
      void segment({ points }).then((r) =>
        applySegmentResult(r, { asPreview: true, gen, pointCount }),
      );
    }
  }

  // R6 — 확정(Enter/더블클릭). 즉시 프리뷰 모드에서 프리뷰가 현재 누적점 전체를 반영하면
  // 재요청 없이 커밋, 아니면(마지막 클릭 미반영/드롭/stale) 누적점 전체로 재요청 후 커밋.
  // 확정 후 프리뷰/누적 초기화. OFF 경로는 기존 동작 불변.
  function confirmSegment() {
    if (immediateSegment) {
      const pts = segPointsRef.current;
      // 빈-포인트 가드(이슈2) — Enter/더블클릭 공통. 유령 프리뷰만 남아 있으면 정리 후 무간섭.
      if (pts.length === 0) {
        if (segPreviewRef.current !== null) {
          segGenRef.current += 1; // 진행 중 프리뷰 응답 무효화
          setSegPreview(null);
        }
        return;
      }
      // 최신성(이슈1a) — 프리뷰가 현재 누적점 수를 반영할 때만 재요청 없이 직접 커밋.
      if (
        segPreviewRef.current !== null &&
        segPreviewRef.current.length >= 6 &&
        segPreviewForCountRef.current === pts.length
      ) {
        commitImagePolygon(segPreviewRef.current);
        segGenRef.current += 1; // 진행 중 프리뷰 응답 무효화(이슈1b)
        setSegPoints([]);
        setSegPreview(null);
        return;
      }
      // 프리뷰 부재/stale — 현재 누적점 전체로 재요청 후 그 결과를 커밋(항상 최신 전체 점 반영).
      if (!segment) return; // 미주입 — 안전 무시
      // 이월 MED — 재요청이 드롭될 상황이면 조용히 소실하지 말고 확정을 큐잉한다.
      // 누적점은 유지(시각 피드백 보존)하고 stale 프리뷰만 제거. 해제되면 effect 가 커밋.
      if (isSegmentBlockedNow()) {
        segGenRef.current += 1; // 앞선 프리뷰 응답 무효화(늦게 와도 유령 방지)
        setSegPreview(null);
        pendingConfirmFrameRef.current = frameKey; // 이 프레임의 확정임을 기록
        setPendingConfirm(true);
        return;
      }
      segGenRef.current += 1; // 앞선 클릭의 프리뷰 응답 무효화(늦게 와도 유령 방지 — 이슈1b)
      setSegPreview(null);
      dispatchPointsConfirm(segment, pts);
      return;
    }
    // OFF(누적 후 확정 1회 요청) 경로.
    if (!segment) return; // 미주입 — 안전 무시
    const pts = segPointsRef.current;
    if (pts.length === 0) return;
    // 즉시모드와 동일하게, 드롭될 상황이면 점을 지우지 않고 확정을 큐잉한다(무음 소실 방지).
    if (isSegmentBlockedNow()) {
      setSegPreview(null);
      pendingConfirmFrameRef.current = frameKey; // 이 프레임의 확정임을 기록
      setPendingConfirm(true);
      return;
    }
    setSegPreview(null);
    dispatchPointsConfirm(segment, pts);
  }

  /**
   * 누적점 복원 — 요청이 거부·폐기·실패했을 때 사용자의 클릭이 무음으로 사라지지 않게 되돌린다.
   *
   * ⚠ **발사 시점 컨텍스트가 일치할 때만** 복원한다. "지금 점이 비어 있다" 는 조건만으로 되돌리면
   *   프레임 전환으로 비워진 상태(전환 effect)와 구분되지 않아, **옛 프레임의 클릭이 새 프레임에
   *   부활**한다(그 상태로 확정하면 새 프레임에 옛 좌표로 요청이 나간다). 누적점 마커는 도구와
   *   무관하게 렌더되므로 도구 전환 여부도 함께 본다.
   *
   * ⚠ 세대(gen)도 함께 본다 — 사용자가 **명시적으로 지운**(Esc/취소) 점을 응답 도착 시점에
   *   되살리면 안 된다. 지운 뒤에 확정하면 사용자가 취소한 좌표로 요청이 나간다.
   */
  function restoreSegPoints(firedFrame: FrameKey, firedGen: number, pts: Point[]) {
    if (frameKeyRef.current !== firedFrame) return; // 프레임이 바뀌었다 — 남의 점을 되살리지 않는다
    if (activeToolRef.current !== ToolTypeEnum.SAM_SEGMENT) return; // 도구를 떠났다
    if (segGenRef.current !== firedGen) return; // 취소·재시도로 무효화됐다
    // 그 사이 새로 찍은 점이 있으면 최신 입력을 덮지 않는다.
    setSegPoints((cur) => (cur.length === 0 ? pts : cur));
  }

  /**
   * 확정 요청 발사 + 결과 반영. 클릭 확정(누적점)과 박스 드래그가 **같은 경로**를 쓴다 —
   * 경로가 갈리면 한쪽만 복원·에러 안내를 갖게 되어 다른 쪽이 무음으로 사라진다.
   *
   * @param restore 되돌릴 누적점. 지정 시 누적점은 확정 즉시 비우되(시각적 확정),
   *   **거부·폐기(null)·요청 실패면 되돌려 놓는다** — 지운 채로 두면 요청이 나가지도, 안내가
   *   뜨지도 않은 상태에서 사용자의 클릭만 사라진다. 박스 드래그는 되돌릴 누적점이 없어 생략한다.
   */
  function dispatchConfirm(
    send: NonNullable<OverlayLayerProps['segment']>,
    payload: Omit<Sam2SegmentRequest, 'srcSn'>,
    restore?: Point[],
  ) {
    // 발사 시점 프레임·세대를 캡처 — 응답이 돌아온 시점과 비교해 복원 여부를 정한다.
    const firedFrame = frameKeyRef.current;
    const firedGen = segGenRef.current;
    let responded = false;
    if (restore) setSegPoints([]);
    void send(payload)
      .then((r) => {
        responded = true;
        if (!r) {
          if (restore) restoreSegPoints(firedFrame, firedGen, restore);
          return;
        }
        applySegmentResult(r, { asPreview: false });
      })
      .catch(() => {
        // 커밋 단계(applySegmentResult) 예외는 요청 실패가 아니다 — 오안내하지 않는다.
        if (responded) return;
        // 원본 오류 메시지(서버 응답/스택)는 사용자에게 노출하지 않고 사용자 언어로 안내한다.
        if (restore) restoreSegPoints(firedFrame, firedGen, restore);
        onCommitError?.('AI 분할 요청에 실패했습니다. 잠시 후 다시 시도하세요.');
      });
  }

  /** 누적점 확정 요청 — 좌표 변환은 여기 한 곳에서만 한다. */
  function dispatchPointsConfirm(
    send: NonNullable<OverlayLayerProps['segment']>,
    pts: Point[],
  ) {
    dispatchConfirm(send, { points: pts.map((p) => [p.x, p.y]) }, pts);
  }

  // R6 — 진행 중 누적 취소(Esc).
  function cancelSegment() {
    segGenRef.current += 1; // 진행 중 프리뷰 응답 무효화(취소 후 유령 방지)
    setSegPoints([]);
    setSegPreview(null);
    setPendingConfirm(false); // 확정 큐 취소
  }

  // 최신 확정/취소 핸들러를 ref 에 반영(window 리스너가 참조).
  confirmSegmentRef.current = confirmSegment;
  cancelSegmentRef.current = cancelSegment;

  function handleSegmentBox(start: Point, end: Point) {
    if (!segment) return;
    // 드래그 도중 차단이 시작될 수 있다(mousedown 게이트는 시작만 막는다). 박스 프롬프트는
    // 확정 큐가 없어 되돌릴 것도 없으므로, 요청을 보내지 않고 **실패로 안내**한다.
    // 무음 no-op 이면 사용자는 분할이 되는 줄 알고 기다린다.
    if (isSegmentBlockedNow()) {
      notifySegmentBoxDropped();
      return;
    }
    const a = clampToImage(geometry, translateFromCanvas(geometry, start.x, start.y));
    const b = clampToImage(geometry, translateFromCanvas(geometry, end.x, end.y));
    dispatchConfirm(segment, { box: [a.x, a.y, b.x, b.y] });
  }

  // === BBox 핸들러 (전체 캔버스 영역에 invisible Rect 캡처)
  const captureRect =
    activeTool === ToolTypeEnum.BBOX ||
    activeTool === ToolTypeEnum.POLYGON ||
    activeTool === ToolTypeEnum.SAM_SEGMENT ||
    activeTool === ToolTypeEnum.KEYPOINT ? (
      <Rect
        x={0}
        y={0}
        width={geometry.canvas.width}
        height={geometry.canvas.height}
        fill="rgba(0,0,0,0.001)"
        onMouseDown={() => {
          // 차단 구간에서는 드래그를 **시작조차 하지 않는다**. 시작시켜 놓고 발사 시점에 거부하면
          // 그때까지의 드래그 궤적을 되돌릴 방법이 없어 조작이 무음으로 사라진다(큐가 없는 경로).
          if (isEditBlocked()) {
            if (activeTool === ToolTypeEnum.SAM_SEGMENT) {
              // 분할은 **클릭 누적이 차단 구간에도 성립**한다(확정 큐). 그 성공하는 조작 직전의
              // mousedown 이 "완료 후 다시 시도" 를 띄우면 사실과 다른 안내가 된다 — 여기서는
              // 알리지 않고, 드래그(박스)였는지는 mouseup 에서 판정한다.
              segBlockedDownRef.current = pointerCanvas();
              return;
            }
            notifyBlocked();
            return;
          }
          segBlockedDownRef.current = null;
          if (activeTool === ToolTypeEnum.SAM_SEGMENT) {
            const p = pointerCanvas();
            if (!p) return;
            setSegDraft({ start: p, current: p });
            return;
          }
          if (activeTool !== ToolTypeEnum.BBOX) return;
          const p = pointerCanvas();
          if (!p) return;
          setBboxDraft({ start: p, current: p });
        }}
        onMouseMove={() => {
          if (activeTool === ToolTypeEnum.SAM_SEGMENT) {
            if (!segDraft) return;
            const p = pointerCanvas();
            if (!p) return;
            setSegDraft({ ...segDraft, current: p });
            return;
          }
          if (activeTool !== ToolTypeEnum.BBOX || !bboxDraft) return;
          const p = pointerCanvas();
          if (!p) return;
          setBboxDraft({ ...bboxDraft, current: p });
        }}
        onMouseUp={() => {
          if (activeTool === ToolTypeEnum.SAM_SEGMENT) {
            const blockedDown = segBlockedDownRef.current;
            segBlockedDownRef.current = null;
            if (blockedDown) {
              // 차단 구간에서 시작된 제스처. 드래그였다면 뒤따르는 click 이 mouseup 지점을
              // 누적점으로 삼는 것을 막고(stray point) 실패로 안내한다. 단순 클릭이면 누적이
              // 정상 동선이므로 아무것도 하지 않는다.
              // 안내 문구는 notifySegmentBoxDropped 가 **이 시점에 다시 판정**한다 — 드래그 도중
              // 작업이 끝났는데 "진행 중" 이라고 말하면 사실과 다른 안내가 된다.
              const up = pointerCanvas() ?? blockedDown;
              if (Math.hypot(up.x - blockedDown.x, up.y - blockedDown.y) >= 3) {
                segSuppressClickRef.current = true;
                notifySegmentBoxDropped();
              }
              return;
            }
            if (!segDraft) return;
            const p = pointerCanvas() ?? segDraft.current;
            const moved = Math.hypot(p.x - segDraft.start.x, p.y - segDraft.start.y);
            // 유의미한 드래그면 박스 프롬프트, 아니면 무시(클릭은 onClick 에서 포인트 처리).
            if (moved >= 3) {
              handleSegmentBox(segDraft.start, p);
              segSuppressClickRef.current = true;
            }
            setSegDraft(null);
            return;
          }
          if (activeTool !== ToolTypeEnum.BBOX || !bboxDraft) return;
          const { start, current } = bboxDraft;
          setBboxDraft(null);
          // 드래그 도중 차단이 시작됐으면 커밋하지 않는다(mousedown 게이트는 시작만 막는다).
          // 차단 중 라벨이 추가되면 진행 중 작업의 결과 병합과 겹쳐 작업본이 두 축에서 갈린다.
          if (isEditBlocked()) {
            notifyBlocked();
            return;
          }
          commitBbox(start, current);
        }}
        onClick={() => {
          if (activeTool === ToolTypeEnum.SAM_SEGMENT) {
            // 직전 드래그(박스)로 처리된 클릭이면 무시.
            if (segSuppressClickRef.current) {
              segSuppressClickRef.current = false;
              return;
            }
            // ★ 오버레이가 떠 있으면 누적하지 않는다(D3). 사용자가 진행 상태를 보고 있으므로
            //   무시가 무음 소실이 아니고, 실제로도 오버레이 백드롭이 클릭을 흡수한다.
            if (isBusyOverlayShown()) return;
            // ⚠ 분할 클릭은 **오버레이가 뜨기 전(지연 창 <300ms)** 차단 구간에서만 누적을 허용한다
            //   — 그 구간에는 화면에 아무 단서가 없어 클릭을 삼키면 조작이 이유 없이 사라진다.
            //   이 경로에는 확정 큐가 있어 busy 가 풀리면 누적점 전체로 실행된다(작업 결과 보존).
            //   대신 프리뷰 요청은 handleSegmentClick 안에서 막는다.
            handleSegmentClick();
            return;
          }
          // 라벨을 만드는 경로(폴리곤 점·키포인트 배치)는 차단 구간에 입력 자체를 받지 않는다.
          if (isEditBlocked()) {
            notifyBlocked();
            return;
          }
          if (activeTool === ToolTypeEnum.KEYPOINT) {
            handleKeypointClick();
            return;
          }
          if (activeTool !== ToolTypeEnum.POLYGON) return;
          const p = pointerCanvas();
          if (!p) return;
          addPolygonPointAt(p);
        }}
        onDblClick={() => {
          if (activeTool === ToolTypeEnum.SAM_SEGMENT) {
            // R6 — 더블클릭으로 누적 포인트 확정. 차단 구간이면 confirmSegment 가 큐잉한다.
            confirmSegment();
            return;
          }
          if (isEditBlocked()) {
            notifyBlocked();
            return;
          }
          if (activeTool !== ToolTypeEnum.POLYGON) return;
          // 커밋 시도 후 점이 부족(폴리곤 불가)이면 그리기 취소로 간주해 draft 를 비운다.
          // (커밋 성공/실패는 tryCommitPolygon 내부에서 처리 — 실패 시 점 유지, 부족 시에만 여기서 취소)
          if (!tryCommitPolygon() && polyPoints.length < 6) setPolyPoints([]);
        }}
      />
    ) : null;

  return (
    <>
      {captureRect}
      {bboxDraft && (
        <Rect
          x={Math.min(bboxDraft.start.x, bboxDraft.current.x)}
          y={Math.min(bboxDraft.start.y, bboxDraft.current.y)}
          width={Math.abs(bboxDraft.current.x - bboxDraft.start.x)}
          height={Math.abs(bboxDraft.current.y - bboxDraft.start.y)}
          stroke="#26A69A"
          strokeWidth={2}
          dash={[4, 4]}
          listening={false}
        />
      )}
      {segDraft && (
        <Rect
          x={Math.min(segDraft.start.x, segDraft.current.x)}
          y={Math.min(segDraft.start.y, segDraft.current.y)}
          width={Math.abs(segDraft.current.x - segDraft.start.x)}
          height={Math.abs(segDraft.current.y - segDraft.start.y)}
          stroke="#7E57C2"
          strokeWidth={2}
          dash={[4, 4]}
          listening={false}
        />
      )}
      {/* R6 — SAM_SEGMENT 누적 positive-click 마커(확정 전 시각 피드백). */}
      {segPoints.map((p, i) => {
        const c = translateToCanvas(geometry, p.x, p.y);
        return (
          <Circle
            key={`seg-pt-${p.x}:${p.y}:${i}`}
            x={c.x}
            y={c.y}
            radius={4}
            fill="#7E57C2"
            stroke="#ffffff"
            strokeWidth={1}
            listening={false}
          />
        );
      })}
      {/* 즉시 프리뷰 모드 — AI 분할 결과 폴리곤을 확정 전 점선으로 미리 표시(커밋 전). */}
      {activeTool === ToolTypeEnum.SAM_SEGMENT && segPreview && segPreview.length >= 6 && (
        <Line
          points={(() => {
            const flat: number[] = [];
            for (let i = 0; i + 1 < segPreview.length; i += 2) {
              const c = translateToCanvas(geometry, segPreview[i], segPreview[i + 1]);
              flat.push(c.x, c.y);
            }
            return flat;
          })()}
          closed
          stroke="#FFB300"
          strokeWidth={2}
          dash={[6, 3]}
          listening={false}
        />
      )}
      {polyPoints.length >= 4 && (
        <Line points={polyPoints} stroke="#26A69A" strokeWidth={2} dash={[4, 4]} listening={false} />
      )}
      {polyPoints.length > 0 && (
        <>
          {(() => {
            const dots = [];
            for (let i = 0; i + 1 < polyPoints.length; i += 2) {
              const px = polyPoints[i];
              const py = polyPoints[i + 1];
              // 정점 좌표 기반 stable key — append-only 드래프트 정점이라 좌표가 정점 정체성을
              // 안정적으로 식별한다 (flat index 단독보다 재정렬/리렌더에 강함).
              dots.push(
                <Circle
                  key={`${px}:${py}`}
                  x={px}
                  y={py}
                  radius={4}
                  fill="#26A69A"
                  listening={false}
                />,
              );
            }
            return dots;
          })()}
        </>
      )}
      {/* KEYPOINT 배치 draft — 부분 스켈레톤 Line + 배치된 관절 Circle + 다음 관절 가이드. */}
      {activeTool === ToolTypeEnum.KEYPOINT && kptDraft.length > 0 && (
        <>
          {COCO_SKELETON.map((edge, i) => {
            const line = skeletonEdgeToCanvasLine(geometry, kptDraft, edge);
            return line ? (
              <Line
                key={`kpt-draft-edge-${i}`}
                points={line}
                stroke="#26A69A"
                strokeWidth={2}
                listening={false}
              />
            ) : null;
          })}
          {kptDraft.map((kp, i) => {
            const c = translateToCanvas(geometry, kp.x, kp.y);
            return (
              <Circle
                key={`kpt-draft-${i}`}
                x={c.x}
                y={c.y}
                radius={4}
                fill="#26A69A"
                listening={false}
              />
            );
          })}
        </>
      )}
    </>
  );
});
