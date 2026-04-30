interface SideBySideCompareProps {
  beforeSrc: string;
  afterSrc: string;
  height?: number;
  label?: { before: string; after: string };
}

/**
 * 두 이미지를 좌우 50:50으로 나란히 보여주는 단순 비교 컴포넌트.
 * 별도 인터랙션(드래그·zoom·클릭) 없이 보여주기만 한다.
 * CompareSlider와 동일한 props 시그니처로 호환된다. (V1.6)
 */
export function SideBySideCompare({
  beforeSrc,
  afterSrc,
  height = 400,
  label = { before: '원본', after: '비식별' },
}: SideBySideCompareProps) {
  return (
    <div
      className="grid grid-cols-2 gap-2 w-full select-none"
      style={{ height }}
      aria-label="좌우 비교"
    >
      {/* Before (left) */}
      <div className="relative overflow-hidden rounded-lg bg-gray-900">
        <img
          src={beforeSrc}
          alt={label.before}
          className="absolute inset-0 w-full h-full object-cover"
          draggable={false}
        />
        <span className="absolute top-3 left-3 bg-black/60 text-white text-xs font-semibold px-2.5 py-1 rounded pointer-events-none">
          {label.before}
        </span>
      </div>

      {/* After (right) */}
      <div className="relative overflow-hidden rounded-lg bg-gray-900">
        <img
          src={afterSrc}
          alt={label.after}
          className="absolute inset-0 w-full h-full object-cover"
          draggable={false}
        />
        <span className="absolute top-3 left-3 bg-blue-600/90 text-white text-xs font-semibold px-2.5 py-1 rounded pointer-events-none">
          {label.after}
        </span>
      </div>
    </div>
  );
}

export default SideBySideCompare;
