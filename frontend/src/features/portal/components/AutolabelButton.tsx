// 오토라벨링 실행 버튼 (YOLO+SAM2 체험용).

import { useState } from 'react';

import { Button } from '@/components/common/Button';

import { requestAutolabel } from '../api';
import type { PortalAutolabelResponse } from '../types';

interface AutolabelButtonProps {
  srcSn: number;
  onSuccess?: (result: PortalAutolabelResponse) => void;
  onError?: (err: Error) => void;
}

export function AutolabelButton({ srcSn, onSuccess, onError }: AutolabelButtonProps) {
  const [loading, setLoading] = useState(false);

  async function handleClick() {
    setLoading(true);
    try {
      const result = await requestAutolabel(srcSn);
      onSuccess?.(result);
    } catch (e) {
      onError?.(e instanceof Error ? e : new Error('오토라벨링 실패'));
    } finally {
      setLoading(false);
    }
  }

  return (
    <Button
      type="button"
      variant="primary"
      onClick={handleClick}
      loading={loading}
      aria-label="오토라벨링 실행"
    >
      오토라벨링 실행
    </Button>
  );
}
