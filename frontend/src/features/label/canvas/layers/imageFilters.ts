// konva 서브모듈 직접 import — 상위 `konva`(index-node) 는 네이티브 `canvas` 를 require 하여
// jsdom 테스트에서 로드 실패한다. 필터 함수만 서브모듈에서 가져와 브라우저/테스트 양쪽 호환.
import { Brighten } from 'konva/lib/filters/Brighten';
import { Contrast } from 'konva/lib/filters/Contrast';
import type { Filter } from 'konva/lib/Node';

import type { ImageAdjust } from '@/stores/useLabelStore';

/**
 * 이미지 조절값 → konva 필터 구성 (Phase 2c, 순수 함수).
 *
 * konva 규약: 필터는 노드의 `filters=[...]` 배열 + 해당 attr(brightness/contrast)로 지정하고
 * `node.cache()` 후 적용된다. 캐시/노드 조작은 ImageLayer(useEffect)에서 수행하며,
 * 본 함수는 어떤 필터를 걸지만 결정한다(테스트 가능하게 분리).
 *
 * 값이 중립(0)인 축은 필터를 제외해 불필요한 캐시/연산을 피한다.
 */
export interface ImageFilterResult {
  filters: Filter[];
  brightness: number;
  contrast: number;
  hasFilters: boolean;
}

/** konva 필터 함수 참조 — 소비처(테스트/레이어)가 동일 참조로 비교/사용. */
export const BRIGHTEN_FILTER: Filter = Brighten;
export const CONTRAST_FILTER: Filter = Contrast;

export function buildImageFilters(adjust: ImageAdjust): ImageFilterResult {
  const filters: Filter[] = [];
  if (adjust.brightness !== 0) filters.push(BRIGHTEN_FILTER);
  if (adjust.contrast !== 0) filters.push(CONTRAST_FILTER);
  return {
    filters,
    brightness: adjust.brightness,
    contrast: adjust.contrast,
    hasFilters: filters.length > 0,
  };
}
