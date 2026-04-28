import { useState } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import { ArrowLeft, GitBranch, DatabaseZap } from 'lucide-react';
import { Card } from '../../components/ui/Card';
import { Tabs } from '../../components/ui/Tabs';
import { Modal } from '../../components/ui/Modal';
import { Button } from '../../components/ui/Button';
import { StatusBadge } from '../../components/batch/StatusBadge';
import { EventTypeBadge } from '../../components/batch/EventTypeBadge';
import { AutoLabelSummary } from './AutoLabelSummary';
import { useFetch } from '../../api/queries';
import type { VideoDto, FrameMeta, FrameLabels } from '../../api/types';
import { formatDate, formatDuration, privacyTypeLabel } from '../../utils/format';
import { BatchStageIndicator } from '../../components/batch/BatchStageIndicator';

// SFR-08 — 영상이 포함된 데이터마트 요약
interface MartImpactItem {
  id: string;
  name: string;
  version: string;
}

function MartImpactSection({ videoId }: { videoId: string }) {
  const navigate = useNavigate();
  const { data, isLoading } = useFetch<{ datasets: MartImpactItem[] }>(
    `/videos/${videoId}/mart-impact`,
  );

  if (isLoading) return null;
  const datasets = data?.datasets ?? [];
  if (datasets.length === 0) return null;

  return (
    <Card>
      <div className="flex items-start gap-3">
        <div className="flex items-center justify-center w-8 h-8 rounded-lg bg-indigo-50 shrink-0">
          <DatabaseZap size={16} className="text-indigo-600" />
        </div>
        <div className="flex-1 min-w-0 space-y-2">
          <div className="flex items-center gap-2 flex-wrap">
            <h3 className="text-sm font-semibold text-gray-800">포함된 데이터마트</h3>
            <span className="text-xs text-gray-500">{datasets.length}건</span>
          </div>
          <p className="text-xs text-gray-500">
            라벨 변경 시 아래 데이터셋의 학습데이터가 동기화 대상이 됩니다.
          </p>
          <div className="flex flex-wrap gap-2">
            {datasets.map((d) => (
              <button
                key={d.id}
                type="button"
                onClick={() => navigate(`/mart?q=${encodeURIComponent(d.name)}`)}
                className="inline-flex items-center gap-1.5 text-xs bg-indigo-50 hover:bg-indigo-100 text-indigo-700 px-2.5 py-1 rounded-full border border-indigo-100 transition-colors"
              >
                <span className="font-medium">{d.name}</span>
                <span className="font-mono text-indigo-500">{d.version}</span>
              </button>
            ))}
          </div>
        </div>
      </div>
    </Card>
  );
}

// ── Info Tab ──────────────────────────────────────────────────────────────────
function InfoTab({ video }: { video: VideoDto }) {
  const metaRows: { label: string; value: string | React.ReactNode }[] = [
    { label: 'CCTV ID', value: video.id },
    { label: '해상도', value: '1920 × 1080' },
    { label: '길이', value: formatDuration(video.durationSec) },
    { label: '녹화 시각', value: formatDate(video.recordedAt) },
    {
      label: '처리 단계',
      value: (
        <div className="overflow-x-auto">
          <BatchStageIndicator stages={video.stages} />
        </div>
      ),
    },
    {
      label: '비식별 여부',
      value: (
        <span>
          {privacyTypeLabel(video.privacyType)} — {video.deidentified ? '처리됨' : '미처리'}
        </span>
      ),
    },
    { label: '생성일', value: formatDate(video.createdAt) },
    { label: '수정일', value: formatDate(video.updatedAt) },
  ];

  return (
    <div className="mt-4 grid grid-cols-1 md:grid-cols-2 gap-3">
      {metaRows.map((r) => (
        <div key={r.label} className="bg-gray-50 rounded-lg px-4 py-3">
          <p className="text-xs text-gray-500 mb-0.5">{r.label}</p>
          <div className="text-sm font-medium text-gray-800">{r.value}</div>
        </div>
      ))}
    </div>
  );
}

// ── Frame Preview Tab ─────────────────────────────────────────────────────────
function FramePreviewTab({ videoId }: { videoId: string }) {
  const navigate = useNavigate();
  const { data: frames, isLoading } = useFetch<FrameMeta[]>(`/videos/${videoId}/frames`);
  const [lightboxFrame, setLightboxFrame] = useState<FrameMeta | null>(null);

  if (isLoading) {
    return (
      <div className="mt-4 grid grid-cols-3 sm:grid-cols-6 gap-2">
        {Array.from({ length: 12 }).map((_, i) => (
          <div key={i} className="aspect-video bg-gray-200 animate-pulse rounded-md" />
        ))}
      </div>
    );
  }

  if (!frames || frames.length === 0) {
    return (
      <p className="mt-4 text-sm text-gray-400 text-center py-8">
        프레임 데이터가 없습니다.
      </p>
    );
  }

  return (
    <>
      <div className="mt-4 grid grid-cols-3 sm:grid-cols-6 gap-2">
        {frames.map((f) => (
          <button
            key={f.frameNo}
            onClick={() => setLightboxFrame(f)}
            className="relative group rounded-md overflow-hidden focus:outline-none focus:ring-2 focus:ring-primary-500"
          >
            <img
              src={f.thumbnailUrl}
              alt={`Frame ${f.frameNo}`}
              className="w-full aspect-video object-cover bg-gray-100"
              loading="lazy"
              width={160}
              height={90}
            />
            {/* Frame number overlay */}
            <div className="absolute bottom-0 left-0 right-0 bg-black/50 text-white text-xs px-1 py-0.5 text-center">
              #{f.frameNo}
            </div>
            {f.hasIssue && (
              <div className="absolute top-1 right-1 w-2 h-2 rounded-full bg-red-500" />
            )}
          </button>
        ))}
      </div>

      {/* Lightbox Modal */}
      <Modal
        open={lightboxFrame !== null}
        onClose={() => setLightboxFrame(null)}
        title={`프레임 #${lightboxFrame?.frameNo}`}
        size="xl"
        footer={
          <>
            <Button variant="secondary" onClick={() => setLightboxFrame(null)}>
              닫기
            </Button>
            {lightboxFrame && (
              <Button
                variant="primary"
                onClick={() => {
                  navigate(`/label/${videoId}?frame=${lightboxFrame.frameNo}`);
                }}
              >
                라벨링 편집
              </Button>
            )}
          </>
        }
      >
        {lightboxFrame && (
          <div className="flex flex-col items-center gap-3">
            <img
              src={lightboxFrame.thumbnailUrl.replace('/160/90', '/640/360')}
              alt={`Frame ${lightboxFrame.frameNo}`}
              className="w-full rounded-lg object-contain max-h-80 bg-gray-100"
              width={640}
              height={360}
            />
            <div className="flex gap-4 text-sm text-gray-500">
              <span>프레임 #{lightboxFrame.frameNo}</span>
              <span>타임스탬프 {lightboxFrame.timestampMs}ms</span>
              {lightboxFrame.hasIssue && (
                <span className="text-red-500 font-medium">이슈 있음</span>
              )}
            </div>
          </div>
        )}
      </Modal>
    </>
  );
}

// ── AutoLabel Tab ─────────────────────────────────────────────────────────────
function AutoLabelTab({ video }: { video: VideoDto }) {
  // Use frame 0 labels for aggregated display
  const { data: frameLabels, isLoading } = useFetch<FrameLabels>(
    `/videos/${video.id}/frames/0/labels`,
  );

  if (isLoading) {
    return (
      <div className="mt-4 space-y-3">
        {Array.from({ length: 3 }).map((_, i) => (
          <div key={i} className="h-16 bg-gray-100 animate-pulse rounded-lg" />
        ))}
      </div>
    );
  }

  return (
    <div className="mt-4">
      <AutoLabelSummary video={video} labels={frameLabels?.objects ?? []} />
    </div>
  );
}

// ── VideoDetail (main) ────────────────────────────────────────────────────────
export function VideoDetail() {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const [activeTab, setActiveTab] = useState('info');

  const { data: video, isLoading, error } = useFetch<VideoDto>(`/videos/${id ?? ''}`);

  if (isLoading) {
    return (
      <div className="space-y-4">
        <div className="h-8 bg-gray-200 animate-pulse rounded w-1/3" />
        <div className="h-32 bg-gray-200 animate-pulse rounded" />
      </div>
    );
  }

  if (error || !video) {
    return (
      <div className="flex flex-col items-center justify-center py-24 gap-4">
        <p className="text-gray-500 text-lg">영상을 찾을 수 없습니다.</p>
        <Button variant="secondary" leftIcon={ArrowLeft} onClick={() => navigate(-1)}>
          뒤로가기
        </Button>
      </div>
    );
  }

  const tabs = [
    { value: 'info', label: '기본 정보' },
    { value: 'frames', label: '프레임 미리보기' },
    { value: 'autolabel', label: '오토라벨 결과' },
  ];

  return (
    <div className="space-y-4">
      {/* Back button */}
      <button
        onClick={() => navigate(-1)}
        className="flex items-center gap-1.5 text-sm text-gray-500 hover:text-gray-800 transition-colors"
      >
        <ArrowLeft size={16} />
        뒤로가기
      </button>

      {/* Header Card */}
      <Card
        actions={
          <Button
            variant="secondary"
            size="sm"
            leftIcon={GitBranch}
            onClick={() => navigate(`/history/${video.id}`)}
          >
            버전관리로 이동
          </Button>
        }
      >
        <div className="flex flex-wrap items-start gap-3">
          <img
            src={`https://picsum.photos/seed/video-${video.id}/320/180`}
            alt={video.cctvName}
            className="w-32 h-20 object-cover rounded-lg bg-gray-100 shrink-0"
            loading="lazy"
            width={128}
            height={80}
          />
          <div className="flex-1 min-w-0">
            <h2 className="text-lg font-bold text-gray-900">{video.cctvName}</h2>
            <div className="flex flex-wrap gap-2 mt-1.5">
              <EventTypeBadge eventType={video.eventType} size="md" />
              <StatusBadge status={video.batchStatus} size="md" />
            </div>
            <div className="flex flex-wrap gap-4 mt-2 text-sm text-gray-500">
              <span>길이: {formatDuration(video.durationSec)}</span>
              <span>녹화일: {formatDate(video.recordedAt, 'YYYY-MM-DD')}</span>
            </div>
          </div>
        </div>
      </Card>

      {/* SFR-08 — 포함된 데이터마트 (영향 있을 때만 노출) */}
      <MartImpactSection videoId={video.id} />

      {/* Tabs */}
      <div className="bg-white border border-gray-200 rounded-lg shadow-sm">
        <div className="px-4 pt-4">
          <Tabs tabs={tabs} value={activeTab} onChange={setActiveTab} />
        </div>
        <div className="px-6 pb-6">
          {activeTab === 'info' && <InfoTab video={video} />}
          {activeTab === 'frames' && <FramePreviewTab videoId={video.id} />}
          {activeTab === 'autolabel' && <AutoLabelTab video={video} />}
        </div>
      </div>
    </div>
  );
}

export default VideoDetail;
