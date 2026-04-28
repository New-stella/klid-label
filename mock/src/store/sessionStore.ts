import { create } from 'zustand';
import type { Role } from '../types/role';

interface CurrentUser {
  id: string;
  name: string;
  email: string;
}

interface SessionState {
  currentRole: Role;
  currentUser: CurrentUser;
  setRole: (role: Role) => void;
}

const USER_BY_ROLE: Record<Role, CurrentUser> = {
  ADMIN: { id: 'user-0001', name: '박관리자', email: 'admin@cudo.co.kr' },
  REVIEWER: { id: 'user-0002', name: '김검수자', email: 'reviewer@cudo.co.kr' },
  WORKER: { id: 'user-0004', name: '이작업자', email: 'worker@cudo.co.kr' },
  PORTAL_USER: { id: 'user-0012', name: '최포털', email: 'portal@example.com' },
};

export const useSessionStore = create<SessionState>((set) => ({
  currentRole: 'ADMIN',
  currentUser: USER_BY_ROLE['ADMIN'],
  setRole: (role: Role) =>
    set({ currentRole: role, currentUser: USER_BY_ROLE[role] }),
}));
