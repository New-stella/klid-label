export type Role = 'ADMIN' | 'REVIEWER' | 'WORKER' | 'PORTAL_USER';

export const ROLES: readonly Role[] = ['ADMIN', 'REVIEWER', 'WORKER', 'PORTAL_USER'] as const;

export const ROLE_LABEL: Record<Role, string> = {
  ADMIN: '시스템 관리자',
  REVIEWER: '검수자',
  WORKER: '라벨링 작업자',
  PORTAL_USER: '포털 사용자',
};

export const ROLE_COLOR: Record<Role, string> = {
  ADMIN: 'bg-purple-100 text-purple-700',
  REVIEWER: 'bg-blue-100 text-blue-700',
  WORKER: 'bg-green-100 text-green-700',
  PORTAL_USER: 'bg-orange-100 text-orange-700',
};
