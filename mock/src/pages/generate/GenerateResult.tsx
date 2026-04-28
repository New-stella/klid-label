import { useNavigate, useParams } from 'react-router-dom';
import { ArrowLeft, Sparkles, Info } from 'lucide-react';
import { Button } from '../../components/ui/Button';
import { Badge } from '../../components/ui/Badge';
import { Skeleton } from '../../components/ui/Skeleton';
import { EventTypeBadge } from '../../components/batch/EventTypeBadge';
import { useFetch } from '../../api/queries';
import type { VideoDto } from '../../api/types';
import { formatDate } from '../../utils/format';

interface BackgroundGenerateRecord {
  id: string;
  videoId: string;
  frameIndex: number;
  type: 'FIRE' | 'FLOOD';
  requestedAt: string;
}

const TYPE_LABEL: Record<BackgroundGenerateRecord['type'], { icon: string; label: string }> = {
  FIRE: { icon: '🔥', label: '산불 배경' },
  FLOOD: { icon: '🌊', label: '침수 배경' },
};

const formatFrameTime = (idx: number): string => {
  const totalSeconds = idx * 5;
  const minutes = Math.floor(totalSeconds / 60);
  const seconds = totalSeconds % 60;
  return `${minutes.toString().padStart(2, '0')}:${seconds.toString().padStart(2, '0')}`;
};

export function GenerateResult() {
  const navigate = useNavigate();
  const { id } = useParams<{ id: string }>();

  const { data: record, isLoading, error } = useFetch<BackgroundGenerateRecord>(
    id ? `/generate/background/${id}` : '/generate/background/__missing__',
  );

  // Lookup source video metadata
  const { data: video } = useFetch<VideoDto>(
    record?.videoId ? `/videos/${record.videoId}` : '/videos/__noop__',
  );

  return (
    <div className="p-6 space-y-6 max-w-3xl">
      {/* Header */}
      <div className="flex items-center gap-3">
        <button
          onClick={() => navigate('/video/completed')}
          className="p-2 rounded-lg hover:bg-gray-100 text-gray-500 transition-colors"
          aria-label="뒤로가기"
        >
          <ArrowLeft size={18} />
        </button>
        <div className="flex items-center gap-2 flex-1">
          <Sparkles size={18} className="text-violet-500" />
          <h1 className="text-xl font-bold text-gray-900">배경영상 생성 요청</h1>
        </div>
      </div>

      {/* Loading */}
      {isLoading && (
        <div className="space-y-3">
          <Skeleton height="2rem" />
          <Skeleton height="10rem" />
        </div>
      )}

      {/* Error / not found */}
      {!isLoading && (error || !record) && (
        <div className="bg-white border border-gray-200 rounded-lg p-8 text-center">
          <p className="text-sm text-gray-600 mb-4">요청을 찾을 수 없습니다.</p>
          <Button
            variant="secondary"
            size="md"
            onClick={() => navigate('/video/completed')}
          >
            영상 목록으로 돌아가기
          </Button>
        </div>
      )}

      {/* Request meta card */}
      {!isLoading && record && (
        <div className="bg-white border border-gray-200 rounded-lg p-5 space-y-4">
          {/* 기본 메타 */}
          <dl className="grid grid-cols-1 md:grid-cols-3 gap-4 text-sm">
            <div>
              <dt className="text-xs text-gray-400 mb-0.5">요청 ID</dt>
              <dd className="font-mono font-medium text-gray-800">{record.id}</dd>
            </div>
            <div>
              <dt className="text-xs text-gray-400 mb-0.5">상태</dt>
              <dd>
                <Badge tone="info" size="sm">관제서버로 전달됨</Badge>
              </dd>
            </div>
            <div>
              <dt className="text-xs text-gray-400 mb-0.5">요청 시각</dt>
              <dd className="font-medium text-gray-700 tabular-nums">
                {formatDate(record.requestedAt, 'YYYY-MM-DD HH:mm')}
              </dd>
            </div>
          </dl>

          <hr className="border-gray-100" />

          {/* 소스 영상 + 선택 프레임 */}
          <div className="grid grid-cols-1 md:grid-cols-2 gap-5">
            <div>
              <p className="text-xs text-gray-400 mb-1.5">소스 영상</p>
              {video ? (
                <div className="flex items-center gap-3">
                  <div className="w-28 aspect-video rounded-md bg-gray-100 overflow-hidden shrink-0">
                    <img
                      src={`https://picsum.photos/seed/video-${video.id}/320/180`}
                      alt={video.cctvName}
                      className="w-full h-full object-cover"
                      loading="lazy"
                      width={112}
                      height={63}
                    />
                  </div>
                  <div className="min-w-0">
                    <p className="text-sm font-semibold text-gray-800 truncate">
                      {video.cctvName}
                    </p>
                    <div className="mt-1">
                      <EventTypeBadge eventType={video.eventType} />
                    </div>
                  </div>
                </div>
              ) : (
                <p className="text-xs text-gray-400 font-mono">{record.videoId}</p>
              )}
            </div>

            <div>
              <p className="text-xs text-gray-400 mb-1.5">선택 프레임</p>
              <div className="flex items-center gap-3">
                <div className="w-28 aspect-video rounded-md bg-gray-100 overflow-hidden shrink-0">
                  <img
                    src={`https://picsum.photos/seed/frame-${record.videoId}-${record.frameIndex}/320/180`}
                    alt={`프레임 ${record.frameIndex + 1}`}
                    className="w-full h-full object-cover"
                    loading="lazy"
                    width={112}
                    height={63}
                  />
                </div>
                <div>
                  <p className="text-sm font-semibold text-gray-800">
                    프레임 {record.frameIndex + 1}
                  </p>
                  <p className="text-xs text-gray-500 tabular-nums">
                    {formatFrameTime(record.frameIndex)}
                  </p>
                </div>
              </div>
            </div>
          </div>

          <hr className="border-gray-100" />

          {/* 생성 유형 */}
          <div>
            <p className="text-xs text-gray-400 mb-1">생성 유형</p>
            <p className="text-sm font-semibold text-gray-800">
              <span className="mr-1">{TYPE_LABEL[record.type].icon}</span>
              {TYPE_LABEL[record.type].label}
            </p>
          </div>

          {/* 안내 */}
          <div className="flex items-start gap-2 rounded-md bg-blue-50 border border-blue-100 px-3 py-2.5 text-xs text-blue-800">
            <Info size={14} className="mt-0.5 shrink-0" />
            <span>
              관제서버에서 배경영상 생성 후 별도 채널로 결과를 회신합니다.
            </span>
          </div>
        </div>
      )}
    </div>
  );
}

export default GenerateResult;
