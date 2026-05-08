import type { Role } from '../types/role';

export interface AppRouteMeta {
  path: string;
  label: string;
  roles: Role[];
  menuGroup?: string;
  hideInMenu?: boolean;
  portal?: boolean;
}

export const APP_ROUTES: AppRouteMeta[] = [
  // 대시보드
  {
    path: '/dashboard',
    label: '대시보드',
    roles: ['REVIEWER', 'WORKER'],
    menuGroup: '대시보드',
  },
  // 영상
  {
    path: '/video/completed',
    label: '영상 처리 현황',
    roles: ['REVIEWER', 'WORKER'],
    menuGroup: '영상',
  },
  {
    path: '/video/:id',
    label: '영상 상세',
    roles: ['REVIEWER', 'WORKER'],
    hideInMenu: true,
  },
  // 작업
  {
    path: '/task',
    label: '작업 목록',
    roles: ['REVIEWER', 'WORKER'],
    menuGroup: '작업',
  },
  {
    path: '/label/:id',
    label: '라벨링',
    roles: ['REVIEWER', 'WORKER'],
    hideInMenu: true,
  },
  // 검수
  {
    path: '/review/pending',
    label: '검수 목록',
    roles: ['REVIEWER'],
    menuGroup: '작업',
  },
  {
    path: '/review/:id',
    label: '검수 화면',
    roles: ['REVIEWER'],
    hideInMenu: true,
  },
  // 버전관리 (hideInMenu)
  {
    path: '/history/:id',
    label: '버전관리',
    roles: ['REVIEWER', 'WORKER'],
    hideInMenu: true,
  },
  // 데이터
  {
    path: '/augment/request',
    label: '증강 요청',
    roles: ['REVIEWER'],
    menuGroup: '데이터',
  },
  {
    path: '/augment/result/:id',
    label: '증강 결과',
    roles: ['REVIEWER'],
    hideInMenu: true,
  },
  {
    path: '/export',
    label: '내보내기',
    roles: ['REVIEWER'],
    menuGroup: '데이터',
  },
  // 포털
  {
    path: '/portal',
    label: '포털 메인',
    roles: ['PORTAL_USER'],
    portal: true,
  },
  {
    path: '/portal/label/:id',
    label: '포털 라벨링',
    roles: ['PORTAL_USER'],
    hideInMenu: true,
    portal: true,
  },
  // 통계
  {
    path: '/stat/worker',
    label: '작업자 통계',
    roles: ['REVIEWER', 'WORKER'],
    menuGroup: '통계',
  },
  {
    path: '/stat/overall',
    label: '전체 구축 현황',
    roles: ['REVIEWER'],
    menuGroup: '통계',
  },
  // 관리
  {
    path: '/manage/users',
    label: '사용자 관리',
    roles: ['REVIEWER'],
    menuGroup: '관리',
  },
  {
    path: '/manage/settings',
    label: '시스템 설정',
    roles: ['REVIEWER'],
    menuGroup: '관리',
  },
  {
    path: '/preset',
    label: '프리셋 관리',
    roles: ['REVIEWER'],
    menuGroup: '관리',
  },
];
