import { Sparkles } from 'lucide-react';
import { useState } from 'react';
import { useNavigate } from 'react-router-dom';

import { Button } from '@/components/common/Button';
import { BackgroundGenerateModalContainer } from '@/features/generate/components/BackgroundGenerateModalContainer';

import type { Video } from '../types';

export interface VideoActionsProps {
  video: Video;
  canAssign: boolean;
  canRequestBg: boolean;
  onDetail?: (video: Video) => void;
  onAssign?: (video: Video) => void;
  /** 외부 콜백이 주어지면 모달 자동 오픈 대신 콜백 위임 */
  onRequestBg?: (video: Video) => void;
}

/**
 * 영상 행 액션 버튼.
 * - REVIEWER만 [배정], [✨ 배경영상 요청] 노출
 * - 모든 권한자 [상세] 노출
 *
 * Phase 10: ✨ 클릭 시 BackgroundGenerateModal 자동 오픈 (외부 onRequestBg 미주입 시).
 *
 * 보안: video.id는 number 타입으로 검증됨 (Video 타입). 경로 조작 방지.
 */
export function VideoActions({
  video,
  canAssign,
  canRequestBg,
  onDetail,
  onAssign,
  onRequestBg,
}: VideoActionsProps) {
  const navigate = useNavigate();
  const [bgModalOpen, setBgModalOpen] = useState(false);

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

  const handleRequestBg = () => {
    if (onRequestBg) {
      onRequestBg(video);
      return;
    }
    setBgModalOpen(true);
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
      {canRequestBg && (
        <Button size="sm" variant="ghost" onClick={handleRequestBg}>
          <Sparkles className="h-3 w-3" aria-hidden />
          배경영상 요청
        </Button>
      )}
      {bgModalOpen && (
        <BackgroundGenerateModalContainer
          video={video}
          onClose={() => setBgModalOpen(false)}
        />
      )}
    </div>
  );
}
