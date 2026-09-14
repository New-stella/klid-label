// 우측 메타 탭 요약 카드가 보여줄 값. [@design UI-157] [@design SCREEN-005] [@design SCREEN-019]
//
// 라벨링·검수 두 화면이 같은 요약을 그리므로 조달을 한 곳에 둔다 — 화면마다 계산하면 「아직
// 채우지 않은 항목」 판정이 갈려 같은 영상에서 서로 다른 안내가 뜬다.
//
// ★조회는 창이 쓰는 것과 <b>같은 훅</b>이다(useMeta · useEventAnnotation). 같은 쿼리 키를 보므로
//   카드 때문에 요청이 늘지 않고, 창에서 저장한 값이 카드에도 그대로 반영된다.

import { useMemo } from 'react';

import { useMeta } from '@/features/auto/hooks/useMeta';
import { isEditableMetaKey } from '@/features/auto/metaKeys';

import { descriptionFirstLine, unfilledItems } from '../annotationSummary';

import { useEventAnnotation } from './useEventAnnotation';

export interface AnnotationSummary {
  /** 이벤트 어노테이션의 이벤트 분류 코드(사람이 고친 값이 있으면 그 값). */
  eventTypeCd: string | null;
  descriptionFirstLine: string | null;
  unfilledItems: string[];
  /** 이벤트 어노테이션 검토 상태 코드. 라벨링 카드에서만 쓴다. */
  reviewStatus: string | null;
}

export function useAnnotationSummary(
  rawSn: number | undefined,
  srcSn: number | undefined,
): AnnotationSummary {
  const { data: meta } = useMeta(srcSn);
  const { data: annotation } = useEventAnnotation(rawSn);

  return useMemo(() => {
    // 영상 분석 설명의 원천은 편집 슬롯(화이트리스트)뿐이다 — 기술메타·이관 원문은 다른 축이라
    // 여기 섞이면 「설명이 채워져 있다」가 거짓이 된다.
    const descriptions = (meta?.items ?? [])
      .filter((it) => isEditableMetaKey(it.metaKey))
      .map((it) => it.metaVal ?? '');
    const payload = annotation?.payload;
    return {
      eventTypeCd:
        typeof payload?.event_class === 'string' && payload.event_class.trim() !== ''
          ? payload.event_class
          : null,
      descriptionFirstLine: descriptionFirstLine(descriptions),
      unfilledItems: unfilledItems({ descriptions, payload }),
      reviewStatus: annotation?.reviewStatus ?? null,
    };
  }, [meta?.items, annotation?.payload, annotation?.reviewStatus]);
}
