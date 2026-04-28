export const LABEL_DEFS: Record<string, { name: string; color: string }> = {
  PERSON: { name: '사람', color: '#EF4444' },
  VEHICLE: { name: '차량', color: '#3B82F6' },
  BICYCLE: { name: '자전거', color: '#10B981' },
  MOTORCYCLE: { name: '오토바이', color: '#F59E0B' },
  TRUCK: { name: '트럭', color: '#8B5CF6' },
  BUS: { name: '버스', color: '#EC4899' },
  FIRE: { name: '화재', color: '#DC2626' },
  SMOKE: { name: '연기', color: '#6B7280' },
};

export function labelColor(code: string): string {
  return LABEL_DEFS[code]?.color ?? '#94A3B8';
}

export function labelName(code: string): string {
  return LABEL_DEFS[code]?.name ?? code;
}
