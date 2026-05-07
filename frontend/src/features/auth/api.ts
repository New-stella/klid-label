import { apiClient } from '@/lib/api/client';
import type { Channel, Role } from '@/lib/api/types';

export interface MeResponse {
  sub: string;
  role: Role;
  channel: Channel;
  name?: string;
}

/**
 * 보안: BE가 검증한 JWT 클레임을 다시 받아 FE 메모리 클레임과 동기화 검증한다.
 */
export async function getMe(): Promise<MeResponse> {
  const res = await apiClient.get<MeResponse>('/me');
  return res.data;
}
