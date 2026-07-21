// 라벨 인스턴스(LS_DATA_LBL, lblSn) 속성 저장값 조회 + upsert 훅 (TanStack Query).
//
// - 조회: GET /v1/labels/{lblSn}/attrs (lblSn 미확정이면 skip)
// - 저장: PUT /v1/labels/{lblSn}/attrs ({values:[{attrId,value}]}) → 성공 시 값 쿼리 무효화
// (state-management.md: 커스텀 훅 래핑 + onSuccess 무효화)

import { useMemo } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';

import {
  getLabelAttrValues,
  putLabelAttrValues,
  type LabelAttrValue,
} from '../api/labelAttr';

export const LABEL_ATTR_VALUE_KEYS = {
  byLabel: (lblSn: number) => ['labelAttrValues', lblSn] as const,
};

/** 속성값 저장 변수 — 변경된 속성만 전달. */
export interface LabelAttrValueSaveVar {
  attrId: number;
  value: string;
}

/**
 * lblSn(serverId) 의 속성 저장값을 조회하고 upsert 한다.
 * lblSn 미확정(undefined/≤0)이면 조회하지 않으며 valueMap 은 빈 객체.
 */
export function useLabelAttrValues(lblSn: number | undefined) {
  const queryClient = useQueryClient();
  const enabled = lblSn != null && lblSn > 0;
  const key = LABEL_ATTR_VALUE_KEYS.byLabel(lblSn ?? 0);

  const query = useQuery<LabelAttrValue[]>({
    queryKey: key,
    queryFn: () => getLabelAttrValues(lblSn as number),
    enabled,
    staleTime: 60 * 1000,
  });

  const mutation = useMutation<void, unknown, LabelAttrValueSaveVar[]>({
    mutationFn: (values: LabelAttrValueSaveVar[]) => putLabelAttrValues(lblSn as number, values),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: key });
    },
  });

  const valueMap = useMemo(() => {
    const m: Record<number, string> = {};
    for (const v of query.data ?? []) m[v.attrId] = v.value;
    return m;
  }, [query.data]);

  return {
    values: query.data ?? [],
    valueMap,
    isLoading: query.isLoading,
    // 조회 실패를 fail-open("값 없음"으로 위장)하지 않도록 에러 상태를 표면화한다.
    // enabled=false(lblSn 미확정)일 때는 fetch 자체가 없어 isError=false(에러 아님).
    isError: query.isError,
    save: mutation.mutate,
    isSaving: mutation.isPending,
    saveError: mutation.error,
  };
}
