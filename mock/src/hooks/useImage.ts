import { useEffect, useState } from 'react';

type ImageStatus = 'loading' | 'loaded' | 'error';

export function useImage(src: string): [HTMLImageElement | null, ImageStatus] {
  const [image, setImage] = useState<HTMLImageElement | null>(null);
  const [status, setStatus] = useState<ImageStatus>('loading');

  useEffect(() => {
    if (!src) {
      setImage(null);
      setStatus('error');
      return;
    }

    setImage(null);
    setStatus('loading');

    const img = new Image();
    img.crossOrigin = 'anonymous';

    img.onload = () => {
      setImage(img);
      setStatus('loaded');
    };

    img.onerror = () => {
      setImage(null);
      setStatus('error');
    };

    img.src = src;

    return () => {
      img.onload = null;
      img.onerror = null;
    };
  }, [src]);

  return [image, status];
}
