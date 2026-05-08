// 라벨 클래스별 색상/한글명 매핑 (mock 정합).
//
// BE의 className(영문 라벨 코드)을 받아 한글 표시명/색상으로 변환한다.
// classId 단일 값보다 className 키 매칭이 안정적 — 미매칭은 fallback 색상.

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

/**
 * className에서 표시용 한글 라벨 추출. 매칭 실패 시 원본 그대로 반환.
 */
export function getLabelDisplayName(className: string | undefined): string {
  if (!className) return '-';
  const upper = className.toUpperCase();
  const direct = LABEL_CLASS_DEFS[upper];
  if (direct) return direct.name;
  return className;
}
