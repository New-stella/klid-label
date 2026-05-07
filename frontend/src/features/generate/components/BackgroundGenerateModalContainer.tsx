import { useDeidentDetail } from '@/features/deident/hooks/useDeidentDetail';
import type { Video } from '@/features/video/types';

import { BackgroundGenerateModal } from './BackgroundGenerateModal';

export interface BackgroundGenerateModalContainerProps {
  video: Video;
  onClose(): void;
}

/**
 * BackgroundGenerateModal에 12 프레임 페어를 주입하는 컨테이너.
 *
 * `VideoActions`가 모달이 열렸을 때만 이 컨테이너를 mount하므로,
 * 컨테이너가 마운트되는 시점에만 useQuery가 호출됨 — VideoActions 단독 테스트는 QueryClient 불필요.
 */
export function BackgroundGenerateModalContainer({
  video,
  onClose,
}: BackgroundGenerateModalContainerProps) {
  const { data: deidentDetail } = useDeidentDetail(video.id);
  return (
    <BackgroundGenerateModal
      open
      video={video}
      frames={deidentDetail?.framePairs ?? []}
      onClose={onClose}
    />
  );
}
