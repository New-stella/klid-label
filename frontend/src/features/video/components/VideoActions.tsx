import { useNavigate } from 'react-router-dom';

import { Button } from '@/components/common/Button';

import type { Video } from '../types';

export interface VideoActionsProps {
  video: Video;
  canAssign: boolean;
  onDetail?: (video: Video) => void;
  onAssign?: (video: Video) => void;
}

/**
 * 영상 행 액션 버튼.
 * - REVIEWER만 [배정] 노출
 * - 모든 권한자 [상세] 노출
 *
 * 보안: video.id는 number 타입으로 검증됨 (Video 타입). 경로 조작 방지.
 */
export function VideoActions({
  video,
  canAssign,
  onDetail,
  onAssign,
}: VideoActionsProps) {
  const navigate = useNavigate();

  const handleDetail = () => {
    if (onDetail) {
      onDetail(video);
      return;
    }
    navigate(`/video/${video.id}`);
  };

  const handleAssign = () => {
    if (onAssign) {
      onAssign(video);
      return;
    }
    // Phase 4(작업 배정)에서 모달 열기
    // eslint-disable-next-line no-alert
    alert(`배정 (videoId=${video.id}) — Phase 4 구현 예정`);
  };

  return (
    <div className="flex items-center gap-1" data-testid={`video-actions-${video.id}`}>
      {canAssign && (
        <Button size="sm" variant="outline" onClick={handleAssign}>
          배정
        </Button>
      )}
      <Button size="sm" variant="ghost" onClick={handleDetail}>
        상세
      </Button>
    </div>
  );
}
