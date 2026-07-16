import { memo, useEffect, useRef } from 'react';
import { Image as KImage } from 'react-konva';
import type Konva from 'konva';

import type { ImageAdjust } from '@/stores/useLabelStore';

import type { Geometry } from '../utils/coordinateTransformer';

import { buildImageFilters } from './imageFilters';

interface ImageLayerProps {
  image: HTMLImageElement;
  geometry: Geometry;
  /** 밝기/대비 조절값(Phase 2c). 미지정 시 원본. */
  adjust?: ImageAdjust;
}

/**
 * 프레임 이미지 표시 전용 레이어.
 * memo + listening=false로 라벨 변경 시 재렌더 회피.
 *
 * 밝기/대비 필터: konva 규약상 `filters=[...]` + attr 지정 후 `node.cache()` 가 필요하다.
 * 성능: 이미지/조절값이 실제로 바뀔 때만 재캐시(useEffect deps) — 매 렌더 재캐시 금지.
 */
function ImageLayerInner({ image, geometry, adjust }: ImageLayerProps) {
  const nodeRef = useRef<Konva.Image | null>(null);
  const filterResult = adjust ? buildImageFilters(adjust) : null;
  const width = geometry.image.width * geometry.scale;
  const height = geometry.image.height * geometry.scale;

  // 이미지·필터 대상 축(밝기/대비)이 바뀔 때만 재캐시. cache() 는 jsdom 미지원이라 typeof 가드.
  useEffect(() => {
    const node = nodeRef.current;
    if (!node) return;
    if (filterResult?.hasFilters) {
      if (typeof node.cache === 'function') node.cache();
    } else if (typeof node.clearCache === 'function') {
      node.clearCache();
    }
    // image/adjust 변경 시에만 실행 (geometry scale 변경은 캔버스 변환이라 재캐시 불필요).
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [image, filterResult?.hasFilters, filterResult?.brightness, filterResult?.contrast]);

  return (
    <KImage
      ref={nodeRef}
      image={image}
      x={geometry.left}
      y={geometry.top}
      width={width}
      height={height}
      listening={false}
      filters={filterResult?.hasFilters ? filterResult.filters : undefined}
      brightness={filterResult?.brightness ?? 0}
      contrast={filterResult?.contrast ?? 0}
    />
  );
}

export const ImageLayer = memo(ImageLayerInner);
