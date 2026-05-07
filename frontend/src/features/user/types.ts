// 사용자 도메인 타입 (BE OpenAPI alias)

import type { Role } from '@/lib/api/types';

export interface User {
  id: number;
  loginId: string;
  name: string;
  email?: string;
  role: Role;
  active: boolean;
  createdAt: string;
  lastLoginAt?: string;
}

export interface UserListParams {
  keyword?: string;
  role?: Role;
  active?: boolean;
  page?: number;
  size?: number;
  sort?: string;
}
