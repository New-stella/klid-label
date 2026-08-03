// 라벨 클래스별 색상 매핑 (mock 정합).
//
// BE의 className(영문 라벨 코드 또는 한글 라벨명)을 받아 표시 색상으로 변환한다.
// classId 단일 값보다 className 키 매칭이 안정적 — 미매칭은 fallback 색상.
// ⚠ 표시명(이름) 변환은 이 파일의 책임이 아니다 — 파일 하단 주석 참조.

export interface LabelClassDef {
  name: string;
  color: string;
}

export const LABEL_CLASS_DEFS: Record<string, LabelClassDef> = {
  PERSON: { name: '사람', color: '#EF4444' },
  VEHICLE: { name: '차량', color: '#3B82F6' },
  CAR: { name: '차량', color: '#3B82F6' },
  BICYCLE: { name: '자전거', color: '#10B981' },
  MOTORCYCLE: { name: '오토바이', color: '#F59E0B' },
  TRUCK: { name: '트럭', color: '#8B5CF6' },
  BUS: { name: '버스', color: '#EC4899' },
  FIRE: { name: '화재', color: '#DC2626' },
  SMOKE: { name: '연기', color: '#6B7280' },
};

const FALLBACK_COLOR = '#94A3B8';

/**
 * className(영문 코드 또는 한글 라벨)에서 색상 추출.
 * - 영문 코드(`PERSON`, `VEHICLE` 등)면 매핑 테이블 사용
 * - 한글 라벨(`사람`, `차량`)이면 매핑 테이블에서 name 매칭
 * - 매칭 실패 시 fallback gray
 */
export function getLabelColor(className: string | undefined): string {
  if (!className) return FALLBACK_COLOR;
  const upper = className.toUpperCase();
  const direct = LABEL_CLASS_DEFS[upper];
  if (direct) return direct.color;
  const matchedByName = Object.values(LABEL_CLASS_DEFS).find((d) => d.name === className);
  return matchedByName?.color ?? FALLBACK_COLOR;
}

// ⚠ 표시용 한글 라벨 변환 함수(구 getLabelDisplayName)는 폐지됐다 — 2026-08-03 재확정으로
//    **라벨명은 라벨 마스터(LS_LABEL) 등록명을 그대로 표시**한다. 표시 지점은 모두 공용 함수
//    `utils/labelDisplayName.resolveLabelDisplayName`(원문 통과) 하나만 쓴다.
//    한글로 보이길 원하면 마스터에서 이름을 한글로 등록한다 — 여기(LABEL_CLASS_DEFS)에 이름을
//    더해 치환을 되살리지 말 것. 코드 사전은 마스터와 어긋나는 두 번째 진실원이 된다.
//    아래 LABEL_CLASS_DEFS 는 이제 **색상 매핑 전용**이다(getLabelColor).
