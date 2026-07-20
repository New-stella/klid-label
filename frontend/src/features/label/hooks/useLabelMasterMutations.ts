// Phase 2 — 라벨 마스터 생성/수정/삭제 mutation 훅 (TanStack Query).
//
// 성공 시 LABEL_MASTER_KEYS.all 전체를 invalidate 하여 목록/캔버스 색상 lookup 이 즉시 갱신되게 한다.
// (state-management.md: mutation onSuccess 에서 관련 쿼리 무효화)

import { useMutation, useQueryClient } from '@tanstack/react-query';

import {
  createLabelMaster,
  deleteLabelMaster,
  updateLabelMaster,
  type LabelMaster,
  type LabelMasterUpsert,
} from '../api/labelMaster';

import { LABEL_MASTER_KEYS } from './useLabelMasters';

/** 라벨 마스터 생성. 성공 시 마스터 목록 무효화. */
export function useCreateLabelMaster() {
  const queryClient = useQueryClient();
  return useMutation<LabelMaster, unknown, LabelMasterUpsert>({
    mutationFn: (body: LabelMasterUpsert) => createLabelMaster(body),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: LABEL_MASTER_KEYS.all });
    },
  });
}

/** 라벨 마스터 수정 변수 — 대상 id + 본문. */
export interface UpdateLabelMasterVars {
  labelId: number;
  body: LabelMasterUpsert;
}

/** 라벨 마스터 수정. 성공 시 마스터 목록 무효화. */
export function useUpdateLabelMaster() {
  const queryClient = useQueryClient();
  return useMutation<LabelMaster, unknown, UpdateLabelMasterVars>({
    mutationFn: ({ labelId, body }: UpdateLabelMasterVars) => updateLabelMaster(labelId, body),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: LABEL_MASTER_KEYS.all });
    },
  });
}

/** 라벨 마스터 삭제. 성공 시 마스터 목록 무효화. */
export function useDeleteLabelMaster() {
  const queryClient = useQueryClient();
  return useMutation<void, unknown, number>({
    mutationFn: (labelId: number) => deleteLabelMaster(labelId),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: LABEL_MASTER_KEYS.all });
    },
  });
}
