import { useEffect, useState } from 'react';

import { apiClient } from '@/lib/api/client';
import { cn } from '@/lib/cn';

interface AuthImageProps extends Omit<React.ImgHTMLAttributes<HTMLImageElement>, 'src'> {
  srcSn: number;
}

/**
 * JWT 인증이 필요한 프레임 이미지를 표시하는 컴포넌트.
 * apiClient(axios)로 blob 다운로드 → objectURL 변환 → <img> 표시.
 * img 태그 직접 사용 시 Authorization 헤더가 누락되어 401이 발생하므로 이 컴포넌트를 사용한다.
 */
export function AuthImage({ srcSn, alt, className, ...rest }: AuthImageProps) {
  const [blobUrl, setBlobUrl] = useState<string | null>(null);
  const [error, setError] = useState(false);

  useEffect(() => {
    let revoked = false;
    let url: string | null = null;

    setError(false);
    setBlobUrl(null);

    apiClient
      .get<Blob>(`/frames/${srcSn}/image`, { responseType: 'blob' })
      .then((res) => {
        if (revoked) return;
        url = URL.createObjectURL(res.data);
        setBlobUrl(url);
      })
      .catch(() => {
        if (!revoked) setError(true);
      });

    return () => {
      revoked = true;
      if (url) URL.revokeObjectURL(url);
    };
  }, [srcSn]);

  if (error) {
    return (
      <div
        className={cn(
          'flex items-center justify-center bg-gray-100 text-gray-400 text-xs',
          className,
        )}
        aria-label={alt}
        {...rest}
      >
        이미지 없음
      </div>
    );
  }

  if (!blobUrl) {
    return <div className={cn('animate-pulse bg-gray-200', className)} aria-label={alt} />;
  }

  return <img src={blobUrl} alt={alt} className={className} {...rest} />;
}
