import { memo } from 'react';
import { Image as KImage } from 'react-konva';

import type { Geometry } from '../utils/coordinateTransformer';

interface ImageLayerProps {
  image: HTMLImageElement;
  geometry: Geometry;
}

/**
 * 프레임 이미지 표시 전용 레이어.
 * memo + listening=false로 라벨 변경 시 재렌더 회피.
 */
function ImageLayerInner({ image, geometry }: ImageLayerProps) {
  return (
    <KImage
      image={image}
      x={geometry.left}
      y={geometry.top}
      width={geometry.image.width * geometry.scale}
      height={geometry.image.height * geometry.scale}
      listening={false}
    />
  );
}

export const ImageLayer = memo(ImageLayerInner);
