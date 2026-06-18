import { apiClient } from '@/lib/api/client';
import type { MarkingRequest, MarkingResponse } from './types';
import type { AxiosResponse } from 'axios';

export function createMarking(rawSn: number, body: MarkingRequest): Promise<MarkingResponse> {
  return apiClient.post<MarkingResponse>(`/videos/${rawSn}/markings`, body).then((r: AxiosResponse<MarkingResponse>) => r.data);
}
