import { useState, useEffect, useCallback, useRef } from 'react';
import { useNavigate } from 'react-router-dom';
import { RefreshCw, Activity } from 'lucide-react';
import { Button } from '../../components/ui/Button';
import { ProgressBar } from '../../components/ui/ProgressBar';
import { EmptyState } from '../../components/ui/EmptyState';
import { EventTypeBadge } from '../../components/batch/EventTypeBadge';
import { BatchStageIndicator } from '../../components/batch/BatchStageIndicator';
import { useFetch } from '../../api/queries';
import type { VideoDto } from '../../api/types';
import { formatDate } from '../../utils/format';
import dayjs from 'dayjs';

function overallProgress(stages: VideoDto['stages']): number {
  if (stages.length === 0) return 0;
  const total = stages.reduce((sum, s) => sum + s.progress, 0);
  return Math.round(total / stages.length);
}

function elapsed(updatedAt: string): string {
  const diffMs = dayjs().diff(dayjs(updatedAt));
  const diffSec = Math.floor(diffMs / 1000);
  if (diffSec < 60) return `${diffSec}초 전`;
  const diffMin = Math.floor(diffSec / 60);
  if (diffMin < 60) return `${diffMin}분 전`;
  const diffH = Math.floor(diffMin / 60);
  return `${diffH}시간 ${diffMin % 60}분 전`;
}

export function BatchMonitoring() {
  const navigate = useNavigate();
  const [autoRefresh, setAutoRefresh] = useState(true);
  const { data: videos, isLoading, refetch } = useFetch<VideoDto[]>('/videos/monitoring');

  // Auto-refresh every 5 seconds
  const refetchRef = useRef(refetch);
  refetchRef.current = refetch;
  useEffect(() => {
    if (!autoRefresh) return;
    const interval = setInterval(() => {
      refetchRef.current();
    }, 5000);
    return () => clearInterval(interval);
  }, [autoRefresh]);

  const handleToggle = useCallback(() => setAutoRefresh((p) => !p), []);

  return (
    <div className="space-y-4">
      {/* Header */}
      <div className="flex items-center justify-between">
        <h1 className="text-xl font-bold text-gray-900">처리 현황</h1>
        <div className="flex items-center gap-2">
          <Button
            variant={autoRefresh ? 'primary' : 'secondary'}
            size="sm"
            leftIcon={Activity}
            onClick={handleToggle}
          >
            자동 새로고침 {autoRefresh ? '켜짐' : '꺼짐'}
          </Button>
          <Button
            variant="secondary"
            size="sm"
            leftIcon={RefreshCw}
            onClick={() => refetch()}
            loading={isLoading}
          >
            새로고침
          </Button>
        </div>
      </div>

      {/* Cards */}
      {isLoading && !videos ? (
        <div className="grid grid-cols-1 md:grid-cols-2 xl:grid-cols-3 gap-4">
          {Array.from({ length: 6 }).map((_, i) => (
            <div key={i} className="h-48 bg-gray-100 animate-pulse rounded-lg" />
          ))}
        </div>
      ) : !videos || videos.length === 0 ? (
        <EmptyState
          icon={Activity}
          title="처리중인 영상이 없습니다"
          description="현재 PROCESSING 상태인 영상이 없습니다."
        />
      ) : (
        <div className="grid grid-cols-1 md:grid-cols-2 xl:grid-cols-3 gap-4">
          {videos.map((video) => {
            const progress = overallProgress(video.stages);
            return (
              <div
                key={video.id}
                className="bg-white border border-gray-200 rounded-lg shadow-sm p-4 hover:shadow-md transition-shadow cursor-pointer"
                onClick={() => navigate(`/video/${video.id}`)}
              >
                {/* Thumbnail + title */}
                <div className="flex gap-3 mb-3">
                  <img
                    src={`https://picsum.photos/seed/video-${video.id}/320/180`}
                    alt={video.cctvName}
                    className="w-20 h-12 object-cover rounded-md bg-gray-100 shrink-0"
                    loading="lazy"
                    width={80}
                    height={48}
                  />
                  <div className="flex-1 min-w-0">
                    <p className="text-sm font-semibold text-gray-800 truncate">{video.cctvName}</p>
                    <div className="mt-1">
                      <EventTypeBadge eventType={video.eventType} />
                    </div>
                  </div>
                </div>

                {/* Stage indicator */}
                <div className="mb-3 overflow-x-auto">
                  <BatchStageIndicator stages={video.stages} />
                </div>

                {/* Progress bar */}
                <ProgressBar value={progress} size="md" tone="primary" showLabel />

                {/* Footer */}
                <div className="flex justify-between mt-2 text-xs text-gray-400">
                  <span>업데이트: {elapsed(video.updatedAt)}</span>
                  <span>{formatDate(video.recordedAt, 'MM-DD')}</span>
                </div>
              </div>
            );
          })}
        </div>
      )}
    </div>
  );
}

export default BatchMonitoring;
