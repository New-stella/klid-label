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
  REVIEWER: { id: 'user-0002', name: '김검수자', email: 'reviewer@cudo.co.kr' },
  WORKER: { id: 'user-0004', name: '이작업자', email: 'worker@cudo.co.kr' },
  PORTAL_USER: { id: 'user-0012', name: '최포털', email: 'portal@example.com' },
};

export const useSessionStore = create<SessionState>((set) => ({
  currentRole: 'REVIEWER',
  currentUser: USER_BY_ROLE['REVIEWER'],
  setRole: (role: Role) =>
    set({ currentRole: role, currentUser: USER_BY_ROLE[role] }),
}));
