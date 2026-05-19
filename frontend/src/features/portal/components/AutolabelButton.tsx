// 오토라벨링 실행 버튼 (YOLO 체험용).
//
// hotfix(W-3): BE PortalAutolabelRequest 는 portalVideoSn + imageB64 가 모두 필수.
// 호출자는 캡처된 프레임 base64 를 제공해야 한다. 미제공 시 버튼은 disabled 처리.

import { useState } from 'react';

import { Button } from '@/components/common/Button';

import { requestAutolabel } from '../api';
import type { PortalAutolabelResponse } from '../types';

interface AutolabelButtonProps {
  /** 포털 사용자 본인 영상 PK (LS_PORTAL_USER_VIDEO). */
  portalVideoSn: number;
  /**
   * 추론 대상 프레임 base64.
   * 동기 string 또는 비동기 콜백(클릭 시 캔버스에서 추출) 둘 다 허용.
   * 미제공 시 버튼은 비활성화된다.
   */
  imageB64?: string | (() => Promise<string | null>);
  onSuccess?: (result: PortalAutolabelResponse) => void;
  onError?: (err: Error) => void;
}

export function AutolabelButton({
  portalVideoSn,
  imageB64,
  onSuccess,
  onError,
}: AutolabelButtonProps) {
  const [loading, setLoading] = useState(false);
  const disabled = imageB64 === undefined;

  async function handleClick() {
    setLoading(true);
    try {
      const resolved =
        typeof imageB64 === 'function' ? await imageB64() : imageB64 ?? null;
      if (!resolved) {
        throw new Error('오토라벨링 대상 프레임이 없습니다.');
      }
      const result = await requestAutolabel(portalVideoSn, resolved);
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
      disabled={disabled}
      aria-label="오토라벨링 실행"
    >
      오토라벨링 실행
    </Button>
  );
}
