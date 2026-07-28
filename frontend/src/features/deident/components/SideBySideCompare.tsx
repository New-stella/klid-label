import { AuthImage } from '@/components/common/AuthImage';
import { cn } from '@/lib/cn';

export interface SideBySideCompareProps {
  leftImage: string;
  rightImage: string;
  leftLabel: string;
  rightLabel: string;
  /** 우측 이미지 누락 시 placeholder 텍스트 — 비식별 실패 등 */
  rightMissingText?: string;
  /**
   * true 면 URL 을 인증(Bearer) blob 요청으로 로드한다(`AuthImage`).
   * BE 이미지 서빙 API 는 Authorization 헤더가 필수라 raw `<img src>` 로는 401 이 된다.
   */
  authImages?: boolean;
  className?: string;
}

const COMPARE_IMAGE_CLASS = 'h-auto w-full rounded border border-border object-contain';
// AuthImage 는 로딩/에러 시 <div> 폴백이라 고정 높이로 레이아웃 이동(CLS)을 막는다.
const COMPARE_AUTH_IMAGE_CLASS =
  'h-48 w-full rounded border border-border object-contain';

/**
 * V1.6: 50:50 좌우 비교 — 슬라이더 오버레이 X.
 * SCR-DEIDENT-002, SCR-AUG-001 등에서 사용.
 *
 * 보안: src URL은 호출자가 BE 응답값으로 전달하는 전제. FE에서는 단순 렌더만.
 */
export function SideBySideCompare({
  leftImage,
  rightImage,
  leftLabel,
  rightLabel,
  rightMissingText,
  authImages = false,
  className,
}: SideBySideCompareProps) {
  const rightMissing = !rightImage;
  return (
    <div
      data-testid="side-by-side-compare"
      aria-label="좌우 비교"
      className={cn('grid grid-cols-2 gap-2', className)}
    >
      <figure className="flex flex-col gap-1">
        <figcaption className="text-sub font-medium text-primary">{leftLabel}</figcaption>
        <CompareImage
          image={leftImage}
          label={leftLabel}
          authImage={authImages}
          testid="side-by-side-left"
        />
      </figure>
      <figure className="flex flex-col gap-1">
        <figcaption className="text-sub font-medium text-primary">{rightLabel}</figcaption>
        {rightMissing ? (
          <div
            data-testid="side-by-side-right-missing"
            className="flex h-48 w-full items-center justify-center rounded border border-dashed border-border bg-bgLight text-body text-neutral"
          >
            {rightMissingText ?? `${rightLabel} 이미지 없음`}
          </div>
        ) : (
          <CompareImage
            image={rightImage}
            label={rightLabel}
            authImage={authImages}
            testid="side-by-side-right"
          />
        )}
      </figure>
    </div>
  );
}

function CompareImage({
  image,
  label,
  authImage,
  testid,
}: {
  image: string;
  label: string;
  authImage: boolean;
  testid: string;
}) {
  if (authImage) {
    return (
      <AuthImage
        path={image}
        alt={label}
        width={640}
        height={360}
        data-testid={testid}
        className={COMPARE_AUTH_IMAGE_CLASS}
      />
    );
  }

  return (
    <img
      src={image}
      alt={label}
      width={640}
      height={360}
      loading="lazy"
      data-testid={testid}
      className={COMPARE_IMAGE_CLASS}
    />
  );
}
