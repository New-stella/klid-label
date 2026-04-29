import type { UserDto } from '../../api/types';
import { id, daysAgo } from './_helpers';

export const users: UserDto[] = [
  // REVIEWER 3명
  { id: id('user', 0), name: '김검수', email: 'reviewer0@cudo.co.kr', role: 'REVIEWER', status: 'ACTIVE', lastLoginAt: daysAgo(0) },
  { id: id('user', 1), name: '이검수', email: 'reviewer1@cudo.co.kr', role: 'REVIEWER', status: 'ACTIVE', lastLoginAt: daysAgo(1) },
  { id: id('user', 2), name: '박검토', email: 'reviewer2@cudo.co.kr', role: 'REVIEWER', status: 'ACTIVE', lastLoginAt: daysAgo(2) },
  // WORKER 8명
  { id: id('user', 3), name: '최라벨', email: 'worker1@cudo.co.kr', role: 'WORKER', status: 'ACTIVE', lastLoginAt: daysAgo(1) },
  { id: id('user', 4), name: '정작업', email: 'worker2@cudo.co.kr', role: 'WORKER', status: 'ACTIVE', lastLoginAt: daysAgo(0) },
  { id: id('user', 5), name: '강라벨링', email: 'worker3@cudo.co.kr', role: 'WORKER', status: 'ACTIVE', lastLoginAt: daysAgo(3) },
  { id: id('user', 6), name: '윤어노테', email: 'worker4@cudo.co.kr', role: 'WORKER', status: 'ACTIVE', lastLoginAt: daysAgo(2) },
  { id: id('user', 7), name: '임태그', email: 'worker5@cudo.co.kr', role: 'WORKER', status: 'ACTIVE', lastLoginAt: daysAgo(4) },
  { id: id('user', 8), name: '한마킹', email: 'worker6@cudo.co.kr', role: 'WORKER', status: 'INACTIVE', lastLoginAt: daysAgo(30) },
  { id: id('user', 9), name: '오분류', email: 'worker7@cudo.co.kr', role: 'WORKER', status: 'ACTIVE', lastLoginAt: daysAgo(1) },
  { id: id('user', 10), name: '서레이블', email: 'worker8@cudo.co.kr', role: 'WORKER', status: 'ACTIVE', lastLoginAt: daysAgo(5) },
  // PORTAL_USER 4명
  { id: id('user', 11), name: '홍길동', email: 'portal1@example.com', role: 'PORTAL_USER', status: 'ACTIVE', lastLoginAt: daysAgo(2) },
  { id: id('user', 12), name: '이순신', email: 'portal2@example.com', role: 'PORTAL_USER', status: 'ACTIVE', lastLoginAt: daysAgo(7) },
  { id: id('user', 13), name: '세종대왕', email: 'portal3@example.com', role: 'PORTAL_USER', status: 'ACTIVE', lastLoginAt: daysAgo(14) },
  { id: id('user', 14), name: '유관순', email: 'portal4@example.com', role: 'PORTAL_USER', status: 'INACTIVE', lastLoginAt: daysAgo(60) },
];
