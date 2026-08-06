import { useEffect, useState } from 'react';

import { acquireAuthImage, releaseAuthImage } from '@/lib/api/authImageStore';
import { toApiImagePath } from '@/lib/api/imagePath';
import { cn } from '@/lib/cn';

type AuthImageBaseProps = Omit<
  React.ImgHTMLAttributes<HTMLImageElement>,
  'src'
>;

/**
 * 이미지 소스 지정 방식 — 둘 중 하나만 사용한다.
 * - `srcSn`: 프레임 PK 로 원본 프레임 이미지(`/frames/{srcSn}/image`) 요청 (기존 사용처)
 * - `path` : BE 응답이 내려준 이미지 API 경로 문자열(`/v1/frames/{srcSn}/deid-image` 등)
 */
type AuthImageSourceProps =
  | { srcSn: number; path?: never }
  | { path: string; srcSn?: never };

export type AuthImageProps = AuthImageBaseProps & AuthImageSourceProps;

/**
 * `<img>` 전용 속성 — 폴백 `<div>` 에 전개하면 유효하지 않은 DOM 속성이 된다(React 경고).
 * `data-*`/`aria-*`/`id`/`style` 등 나머지는 그대로 넘겨 테스트·스타일 계약을 유지한다.
 */
const IMG_ONLY_PROPS = [
  'width',
  'height',
  'loading',
  'decoding',
  'srcSet',
  'sizes',
  'crossOrigin',
  'referrerPolicy',
  'useMap',
  'fetchPriority',
] as const;

function toFallbackProps(props: AuthImageBaseProps): React.HTMLAttributes<HTMLDivElement> {
  const clone: Record<string, unknown> = { ...props };
  for (const key of IMG_ONLY_PROPS) {
    delete clone[key];
  }
  // img 전용 키를 제거한 나머지는 div 에 유효한 속성 집합이다.
  return clone as React.HTMLAttributes<HTMLDivElement>;
}

/**
 * JWT 인증이 필요한 프레임 이미지를 표시하는 컴포넌트.
 * apiClient(axios)로 blob 다운로드 → objectURL 변환 → <img> 표시.
 * img 태그 직접 사용 시 Authorization 헤더가 누락되어 401이 발생하므로 이 컴포넌트를 사용한다.
 *
 * 보안: `path` 는 화이트리스트 검증(`toApiImagePath`)을 통과한 경우에만 요청한다.
 * 성능/메모리: 실제 요청은 `authImageStore` 가 수행한다 — 같은 경로를 보는 소비자끼리 요청·objectURL
 * 을 공유한다(중복 페치 제거). 동시 요청 상한은 두지 않는다(브라우저가 커넥션을 제한한다).
 * objectURL 은 마지막 소비자가 unmount 되거나 소스가 바뀌는 즉시 revoke 된다(영속 캐시 없음 —
 * 비식별 신고 게이트 우회 방지, `authImageStore` 주석 참조).
 */
export function AuthImage({ srcSn, path, alt, className, ...rest }: AuthImageProps) {
  const [blobUrl, setBlobUrl] = useState<string | null>(null);
  const [error, setError] = useState(false);

  // 요청 경로 확정 — srcSn 우선, 없으면 허용된 path 만.
  const requestPath =
    srcSn !== undefined ? `/frames/${srcSn}/image` : toApiImagePath(path);

  useEffect(() => {
    let cancelled = false;

    setError(false);
    setBlobUrl(null);

    if (requestPath === null) {
      // 허용되지 않은 경로 — 요청하지 않고 폴백 표시(fail-closed).
      setError(true);
      return;
    }

    acquireAuthImage(requestPath)
      .then((url) => {
        if (!cancelled) setBlobUrl(url);
      })
      .catch(() => {
        if (!cancelled) setError(true);
      });

    return () => {
      cancelled = true;
      releaseAuthImage(requestPath);
    };
  }, [requestPath]);

  if (error) {
    return (
      <div
        className={cn(
          // 대체 문구("이미지 없음") — ladder `caption`(14px). 크기는 구 `text-xs` 와 동일.
          'flex items-center justify-center bg-gray-100 text-gray-400 text-caption',
          className,
        )}
        aria-label={alt}
        {...toFallbackProps(rest)}
      >
        이미지 없음
      </div>
    );
  }

  if (!blobUrl) {
    // 로딩 폴백도 에러 폴백과 동일하게 식별 속성(data-*/aria-*)을 유지한다 — 여기서 누락되면
    // 로딩 동안 data-testid 가 사라져 호출자의 슬롯 식별(FrameGrid12)이 끊긴다.
    return (
      <div
        className={cn('animate-pulse bg-gray-200', className)}
        aria-label={alt}
        {...toFallbackProps(rest)}
      />
    );
  }

  return <img src={blobUrl} alt={alt} className={className} {...rest} />;
}
