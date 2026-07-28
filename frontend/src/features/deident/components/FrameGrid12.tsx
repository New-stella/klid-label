import { AuthImage } from '@/components/common/AuthImage';
import { cn } from '@/lib/cn';

export interface FrameGrid12Frame {
  srcSn: number;
  frameNo?: number;
  originalUrl: string;
  /** undefined이면 "비식별 이미지 없음" 표시 */
  processedUrl?: string;
}

export interface FrameGrid12Props {
  /** 최대 12개 프레임 페어 (UI/UX §4-10·§4-12·§4-13 공통 패턴) */
  frames: FrameGrid12Frame[];
  selectedSrcSn: number | null;
  onSelect: (srcSn: number) => void;
  /** 상단/하단 슬롯 라벨 — 기본은 원본/비식별. Phase 10 증강에서는 원본/증강(겨울) 등으로 재사용 */
  pairLabel?: { top: string; bottom: string };
  /**
   * true 면 URL 을 인증(Bearer) blob 요청으로 로드한다(`AuthImage`).
   * BE 이미지 서빙 API(`/v1/frames/{srcSn}/deid-image`)는 Authorization 헤더가 필수라
   * raw `<img src>` 로는 401 이 되어 전부 깨진다. 정적 URL 을 넘기는 호출자는 기본값(false) 유지.
   */
  authImages?: boolean;
  className?: string;
}

const DEFAULT_LABELS = { top: '원본', bottom: '비식별' };

/**
 * 12 프레임 그리드 — 원본·비식별 페어를 라디오 단일 선택으로 비교.
 * Phase 10 데이터 증강에서도 동일 컴포넌트 재사용 (`pairLabel`로 슬롯명 변경).
 *
 * 보안: src URL은 BE 응답값만 사용 (사용자 입력 금지). 이미지 alt 한국어로 자동 생성.
 */
export function FrameGrid12({
  frames,
  selectedSrcSn,
  onSelect,
  pairLabel = DEFAULT_LABELS,
  authImages = false,
  className,
}: FrameGrid12Props) {
  return (
    <div
      role="radiogroup"
      aria-label="12 프레임 그리드"
      data-testid="frame-grid-12"
      className={cn('grid grid-cols-3 gap-2 sm:grid-cols-4 lg:grid-cols-6', className)}
    >
      {frames.slice(0, 12).map((f) => {
        const checked = selectedSrcSn === f.srcSn;
        return (
          <label
            key={f.srcSn}
            data-testid={`frame-pair-${f.srcSn}`}
            className={cn(
              'flex cursor-pointer flex-col gap-1 rounded border bg-white p-1 transition-colors duration-100',
              checked ? 'border-accent ring-2 ring-accent' : 'border-border hover:border-accent',
            )}
          >
            <input
              type="radio"
              name="frame-grid-12"
              value={f.srcSn}
              checked={checked}
              onChange={() => onSelect(f.srcSn)}
              className="sr-only"
            />
            <PairSlot
              label={pairLabel.top}
              imageUrl={f.originalUrl}
              authImage={authImages}
              alt={`${pairLabel.top} 프레임 ${f.frameNo ?? f.srcSn}`}
              testid={`frame-pair-${f.srcSn}-original`}
            />
            <PairSlot
              label={pairLabel.bottom}
              imageUrl={f.processedUrl}
              authImage={authImages}
              alt={
                f.processedUrl
                  ? `${pairLabel.bottom} 프레임 ${f.frameNo ?? f.srcSn}`
                  : `${pairLabel.bottom} 이미지 없음`
              }
              testid={`frame-pair-${f.srcSn}-processed`}
              missingText={`${pairLabel.bottom} 이미지 없음`}
            />
            {f.frameNo !== undefined && (
              <span className="text-center text-sub text-neutral">#{f.frameNo}</span>
            )}
          </label>
        );
      })}
    </div>
  );
}

const SLOT_IMAGE_CLASS = 'h-auto w-full rounded border border-border object-cover';
// AuthImage 는 로딩/에러 시 <div> 로 폴백하므로 고정 높이를 줘 레이아웃 이동(CLS)을 막는다.
const SLOT_AUTH_IMAGE_CLASS = 'h-16 w-full rounded border border-border object-cover';

function PairSlot({
  label,
  imageUrl,
  authImage,
  alt,
  testid,
  missingText,
}: {
  label: string;
  imageUrl?: string;
  authImage?: boolean;
  alt: string;
  testid: string;
  missingText?: string;
}) {
  return (
    <div className="flex flex-col gap-0.5">
      <span className="text-sub text-neutral">{label}</span>
      <SlotImage
        imageUrl={imageUrl}
        authImage={authImage}
        alt={alt}
        testid={testid}
        missingText={missingText}
      />
    </div>
  );
}

function SlotImage({
  imageUrl,
  authImage,
  alt,
  testid,
  missingText,
}: {
  imageUrl?: string;
  authImage?: boolean;
  alt: string;
  testid: string;
  missingText?: string;
}) {
  if (!imageUrl) {
    return (
      <div
        data-testid={testid}
        className="flex h-16 w-full items-center justify-center rounded border border-dashed border-border bg-bgLight text-sub text-neutral"
      >
        {missingText ?? '이미지 없음'}
      </div>
    );
  }

  if (authImage) {
    return (
      <AuthImage
        path={imageUrl}
        alt={alt}
        width={120}
        height={68}
        data-testid={testid}
        className={SLOT_AUTH_IMAGE_CLASS}
      />
    );
  }

  return (
    <img
      src={imageUrl}
      alt={alt}
      loading="lazy"
      width={120}
      height={68}
      data-testid={testid}
      className={SLOT_IMAGE_CLASS}
    />
  );
}
