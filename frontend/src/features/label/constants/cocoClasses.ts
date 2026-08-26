// COCO 80 검출 클래스 목록 — 라벨 마스터(LS_LABEL) COCO 매핑 select 옵션 소스.
//
// ★ 진실원은 BE(CocoClasses.LABELS / ai-server COCO_ID2LABEL, id 0~79). 이 상수는 라벨 관리
//   화면의 select 옵션 표시용이며, 값(id)은 BE allowlist 와 문자열이 정확히 일치해야 한다
//   (불일치 매핑은 BE 가 400 으로 거부). 표시 label 은 한글이 있으면 병기, 없으면 영문 그대로.
//
// AI 탐지 팝업의 후보 소스 역할은 이 상수가 아니라 BE 후보 조회 API(useDetectCandidates)가 담당한다.
// 이 상수는 "라벨↔COCO 매핑 지정" 입력 옵션에만 쓰인다.

export interface CocoClass {
  /** COCO 영문 클래스명 — BE 로 저장/전송하는 매핑 값. */
  id: string;
  /** select 표시용 라벨(한글 병기 우선, 없으면 영문). */
  label: string;
}

/**
 * COCO 80종 전량에 한글 병기(표시 편의). 사전에 없는 값은 영문 id 표시(폴백 유지).
 *
 * ★ **이 파일 전용(모듈 private)이다.** 쓰이는 곳은 아래 `COCO_CLASSES` 의 select 옵션 표시
 *   라벨 하나뿐이다. 2026-08-03 재확정으로 **라벨명 표시는 마스터 등록명 그대로**가 되어
 *   `utils/labelDisplayName` 은 이 사전을 참조하지 않는다 — 코드 사전은 라벨 마스터(LS_LABEL)와
 *   어긋나는 두 번째 진실원이 되기 때문이다. 표시명 경로에서 다시 import 하지 말 것(비-export 이유).
 */
const COCO_LABEL_KO: Readonly<Record<string, string>> = {
  person: '사람',
  bicycle: '자전거',
  car: '자동차',
  motorcycle: '오토바이',
  airplane: '비행기',
  bus: '버스',
  train: '기차',
  truck: '트럭',
  boat: '보트',
  'traffic light': '신호등',
  'fire hydrant': '소화전',
  'stop sign': '정지 표지판',
  'parking meter': '주차 요금기',
  bench: '벤치',
  bird: '새',
  cat: '고양이',
  dog: '개',
  horse: '말',
  sheep: '양',
  cow: '소',
  elephant: '코끼리',
  bear: '곰',
  zebra: '얼룩말',
  giraffe: '기린',
  backpack: '배낭',
  umbrella: '우산',
  handbag: '핸드백',
  tie: '넥타이',
  suitcase: '여행가방',
  frisbee: '원반',
  skis: '스키',
  snowboard: '스노보드',
  'sports ball': '공',
  kite: '연',
  'baseball bat': '야구 배트',
  'baseball glove': '야구 글러브',
  skateboard: '스케이트보드',
  surfboard: '서핑보드',
  'tennis racket': '테니스 라켓',
  bottle: '병',
  'wine glass': '와인잔',
  cup: '컵',
  fork: '포크',
  knife: '칼',
  spoon: '숟가락',
  bowl: '그릇',
  banana: '바나나',
  apple: '사과',
  sandwich: '샌드위치',
  orange: '오렌지',
  broccoli: '브로콜리',
  carrot: '당근',
  'hot dog': '핫도그',
  pizza: '피자',
  donut: '도넛',
  cake: '케이크',
  chair: '의자',
  couch: '소파',
  'potted plant': '화분',
  bed: '침대',
  'dining table': '식탁',
  toilet: '변기',
  tv: 'TV',
  laptop: '노트북',
  mouse: '마우스',
  remote: '리모컨',
  keyboard: '키보드',
  'cell phone': '휴대전화',
  microwave: '전자레인지',
  oven: '오븐',
  toaster: '토스터',
  sink: '싱크대',
  refrigerator: '냉장고',
  book: '책',
  clock: '시계',
  vase: '꽃병',
  scissors: '가위',
  'teddy bear': '곰인형',
  'hair drier': '헤어드라이어',
  toothbrush: '칫솔',
};

/** BE CocoClasses.LABELS 와 동일 순서·문자열(id 0~79). */
const COCO_IDS: readonly string[] = [
  'person', 'bicycle', 'car', 'motorcycle', 'airplane',
  'bus', 'train', 'truck', 'boat', 'traffic light',
  'fire hydrant', 'stop sign', 'parking meter', 'bench',
  'bird', 'cat', 'dog', 'horse', 'sheep', 'cow',
  'elephant', 'bear', 'zebra', 'giraffe', 'backpack',
  'umbrella', 'handbag', 'tie', 'suitcase', 'frisbee',
  'skis', 'snowboard', 'sports ball', 'kite',
  'baseball bat', 'baseball glove', 'skateboard', 'surfboard',
  'tennis racket', 'bottle', 'wine glass', 'cup', 'fork',
  'knife', 'spoon', 'bowl', 'banana', 'apple',
  'sandwich', 'orange', 'broccoli', 'carrot', 'hot dog',
  'pizza', 'donut', 'cake', 'chair', 'couch',
  'potted plant', 'bed', 'dining table', 'toilet', 'tv',
  'laptop', 'mouse', 'remote', 'keyboard', 'cell phone',
  'microwave', 'oven', 'toaster', 'sink', 'refrigerator',
  'book', 'clock', 'vase', 'scissors', 'teddy bear',
  'hair drier', 'toothbrush',
] as const;

export const COCO_CLASSES: readonly CocoClass[] = COCO_IDS.map((id) => ({
  id,
  label: COCO_LABEL_KO[id] ? `${COCO_LABEL_KO[id]} (${id})` : id,
}));

/** 매핑 값이 COCO allowlist 에 포함되는지(FE 사전판단 — 최종 판정은 BE). */
export function isCocoClass(id: string | null | undefined): boolean {
  if (id == null) return false;
  return COCO_IDS.includes(id.trim());
}
