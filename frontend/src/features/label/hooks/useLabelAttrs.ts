// Phase 2 — 라벨별 속성 정의 조회 + 생성/수정/삭제 mutation 훅 (TanStack Query).
//
// Query Key Factory 는 labelId 별로 분리(LABEL_ATTR_KEYS.byLabel). mutation 성공 시 해당 라벨의
// 속성 쿼리만 invalidate 한다. (state-management.md: 커스텀 훅 래핑 + onSuccess 무효화)

import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';

import {
  createLabelAttr,
  deleteLabelAttr,
  fetchLabelAttrs,
  updateLabelAttr,
  type LabelAttrDef,
  type LabelAttrUpsert,
} from '../api/labelAttr';

export const LABEL_ATTR_KEYS = {
  all: ['label-attrs'] as const,
  byLabel: (labelId: number) => [...LABEL_ATTR_KEYS.all, labelId] as const,
};

/** 특정 라벨의 속성 정의 목록 조회. labelId 미확정(≤0)이면 조회하지 않음. */
export function useLabelAttrs(labelId: number) {
  return useQuery<LabelAttrDef[]>({
    queryKey: LABEL_ATTR_KEYS.byLabel(labelId),
    queryFn: () => fetchLabelAttrs(labelId),
    enabled: labelId > 0,
    staleTime: 5 * 60 * 1000,
  });
}

/** 속성 정의 생성. 성공 시 해당 라벨 속성 목록 무효화. */
export function useCreateLabelAttr(labelId: number) {
  const queryClient = useQueryClient();
  return useMutation<LabelAttrDef, unknown, LabelAttrUpsert>({
    mutationFn: (body: LabelAttrUpsert) => createLabelAttr(labelId, body),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: LABEL_ATTR_KEYS.byLabel(labelId) });
    },
  });
}

/** 속성 정의 수정 변수 — 대상 attrId + 본문. */
export interface UpdateLabelAttrVars {
  attrId: number;
  body: LabelAttrUpsert;
}

/** 속성 정의 수정. 성공 시 해당 라벨 속성 목록 무효화. */
export function useUpdateLabelAttr(labelId: number) {
  const queryClient = useQueryClient();
  return useMutation<LabelAttrDef, unknown, UpdateLabelAttrVars>({
    mutationFn: ({ attrId, body }: UpdateLabelAttrVars) => updateLabelAttr(labelId, attrId, body),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: LABEL_ATTR_KEYS.byLabel(labelId) });
    },
  });
}

/** 속성 정의 삭제. 성공 시 해당 라벨 속성 목록 무효화. */
export function useDeleteLabelAttr(labelId: number) {
  const queryClient = useQueryClient();
  return useMutation<void, unknown, number>({
    mutationFn: (attrId: number) => deleteLabelAttr(labelId, attrId),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: LABEL_ATTR_KEYS.byLabel(labelId) });
    },
  });
}
