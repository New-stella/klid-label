import { useState, useEffect } from 'react';
import { useNavigate } from 'react-router-dom';
import { Wand2, AlertCircle, History } from 'lucide-react';
import { useFetch, useMutation } from '../../api/queries';
import { api } from '../../api/client';
import type { Page, VideoDto, AugmentJob, AugmentDecision } from '../../api/types';
import { Button } from '../../components/ui/Button';
import { Badge } from '../../components/ui/Badge';
import { Skeleton } from '../../components/ui/Skeleton';
import { ProgressBar } from '../../components/ui/ProgressBar';
import { useToast } from '../../components/common/Toast';
import { AugmentTypeCard } from '../../components/augment/AugmentTypeCard';

type AugmentType = 'WINTER' | 'NIGHT' | 'RAIN' | 'RESOLUTION';

// 상태 매핑 (AugmentResult.tsx와 동일)
const STATUS_LABEL: Record<AugmentJob['status'], string> = {
  PENDING: '대기',
  PROCESSING: '처리 중',
  COMPLETED: '완료',
  FAILED: '실패',
};

const STATUS_TONE: Record<
  AugmentJob['status'],
  'neutral' | 'info' | 'success' | 'danger'
> = {
  PENDING: 'neutral',
  PROCESSING: 'info',
  COMPLETED: 'success',
  FAILED: 'danger',
};

const TYPE_CHIP: Record<AugmentType, string> = {
  WINTER: '❄️ 겨울',
  NIGHT: '🌙 야간',
  RAIN: '🌧 비',
  RESOLUTION: '📐 해상도',
};

// SFR-07 — 활용 결정 배지 라벨/톤
const DECISION_LABEL: Record<AugmentDecision, string> = {
  PENDING: '결정 대기',
  ACCEPTED: '활용',
  REJECTED: '거부',
};

const DECISION_TONE: Record<AugmentDecision, 'warning' | 'success' | 'danger'> = {
  PENDING: 'warning',
  ACCEPTED: 'success',
  REJECTED: 'danger',
};

const ALL_TYPES: AugmentType[] = ['WINTER', 'NIGHT', 'RAIN', 'RESOLUTION'];

interface AugmentRequestBody {
  videoIds: string[];
  types: AugmentType[];
}

export function AugmentRequest() {
  const navigate = useNavigate();
  const { showToast } = useToast();

  // Step state
  const [selectedTypes, setSelectedTypes] = useState<Set<AugmentType>>(new Set());
  const [selectedVideoIds, setSelectedVideoIds] = useState<Set<string>>(new Set());

  const { data, isLoading } = useFetch<Page<VideoDto>>('/videos', {
    status: 'COMPLETED',
    size: 50,
  });

  const { data: jobsPage, isLoading: jobsLoading, refetch: refetchJobs } = useFetch<Page<AugmentJob>>(
    '/augment',
    { page: 0, size: 6 },
  );

  const { mutate, isLoading: isSubmitting } = useMutation<AugmentRequestBody, AugmentJob>(
    (body) => api.post<AugmentJob>('/augment/request', body),
  );

  // PENDING/PROCESSING 잡이 있으면 5초마다 자동 갱신
  const hasPendingJobs = jobsPage?.content.some(
    (j) => j.status === 'PENDING' || j.status === 'PROCESSING',
  );
  useEffect(() => {
    if (!hasPendingJobs) return;
    const timer = setInterval(() => refetchJobs(), 5000);
    return () => clearInterval(timer);
  }, [hasPendingJobs, refetchJobs]);

  const videos = data?.content ?? [];

  const toggleType = (type: AugmentType) => {
    setSelectedTypes((prev) => {
      const next = new Set(prev);
      if (next.has(type)) {
        next.delete(type);
      } else {
        next.add(type);
      }
      return next;
    });
  };

  const toggleVideo = (id: string) => {
    setSelectedVideoIds((prev) => {
      const next = new Set(prev);
      if (next.has(id)) {
        next.delete(id);
      } else {
        next.add(id);
      }
      return next;
    });
  };

  const toggleAllVideos = () => {
    if (selectedVideoIds.size === videos.length) {
      setSelectedVideoIds(new Set());
    } else {
      setSelectedVideoIds(new Set(videos.map((v) => v.id)));
    }
  };

  const totalEstimated = selectedTypes.size * selectedVideoIds.size;
  const canSubmit = selectedTypes.size > 0 && selectedVideoIds.size > 0;

  const handleSubmit = async () => {
    if (!canSubmit) return;
    try {
      const job = await mutate({
        videoIds: Array.from(selectedVideoIds),
        types: Array.from(selectedTypes),
      });
      showToast('증강 요청이 등록되었습니다.', 'success');
      refetchJobs();
      navigate(`/augment/result/${job.id}`);
    } catch {
      showToast('증강 요청 중 오류가 발생했습니다.', 'error');
    }
  };

  return (
    <div className="p-6 pb-28 space-y-8">
      {/* Header */}
      <div className="flex items-center gap-3">
        <div className="flex items-center justify-center w-9 h-9 rounded-lg bg-purple-50">
          <Wand2 size={20} className="text-purple-600" />
        </div>
        <h1 className="text-xl font-bold text-gray-900">데이터 증강 요청</h1>
      </div>

      {/* Step 1: 증강 유형 선택 */}
      <section className="space-y-4">
        <div className="flex items-center gap-2">
          <span className="flex items-center justify-center w-7 h-7 rounded-full bg-primary-600 text-white text-xs font-bold shrink-0">
            1
          </span>
          <h2 className="text-base font-semibold text-gray-800">증강 유형 선택</h2>
          {selectedTypes.size > 0 && (
            <Badge tone="info" size="sm">
              {selectedTypes.size}종 선택
            </Badge>
          )}
        </div>

        <div className="grid grid-cols-2 md:grid-cols-4 gap-4">
          {ALL_TYPES.map((type) => (
            <AugmentTypeCard
              key={type}
              type={type}
              selected={selectedTypes.has(type)}
              onToggle={() => toggleType(type)}
            />
          ))}
        </div>

        {selectedTypes.size === 0 && (
          <p className="flex items-center gap-1.5 text-xs text-amber-600">
            <AlertCircle size={13} />
            증강 유형을 하나 이상 선택하세요.
          </p>
        )}
      </section>

      {/* Step 2: 대상 영상 선택 */}
      <section className="space-y-4">
        <div className="flex items-center gap-2">
          <span className="flex items-center justify-center w-7 h-7 rounded-full bg-primary-600 text-white text-xs font-bold shrink-0">
            2
          </span>
          <h2 className="text-base font-semibold text-gray-800">대상 영상 선택</h2>
          {selectedVideoIds.size > 0 && (
            <Badge tone="success" size="sm">
              {selectedVideoIds.size}건 선택
            </Badge>
          )}
        </div>

        {isLoading ? (
          <div className="grid grid-cols-4 gap-3">
            {Array.from({ length: 8 }, (_, i) => (
              <Skeleton key={i} height="8rem" />
            ))}
          </div>
        ) : videos.length === 0 ? (
          <div className="bg-gray-50 rounded-lg p-8 text-center text-sm text-gray-500">
            완료된 영상이 없습니다.
          </div>
        ) : (
          <>
            {/* Select all header */}
            <div className="flex items-center justify-between">
              <label className="flex items-center gap-2 text-sm text-gray-600 cursor-pointer">
                <input
                  type="checkbox"
                  checked={videos.length > 0 && selectedVideoIds.size === videos.length}
                  onChange={toggleAllVideos}
                  className="w-4 h-4 rounded border-gray-300 text-primary-600 focus:ring-primary-500"
                />
                전체 선택 ({videos.length}건)
              </label>
              <span className="text-xs text-gray-400">COMPLETED 상태 영상만 표시</span>
            </div>

            {/* Video grid */}
            <div className="grid grid-cols-2 md:grid-cols-4 gap-3">
              {videos.map((video) => {
                const checked = selectedVideoIds.has(video.id);
                return (
                  <button
                    key={video.id}
                    type="button"
                    onClick={() => toggleVideo(video.id)}
                    className={[
                      'relative text-left rounded-lg border-2 overflow-hidden transition-all cursor-pointer',
                      'focus:outline-none focus:ring-2 focus:ring-primary-500 focus:ring-offset-1',
                      checked
                        ? 'border-primary-500 shadow-sm'
                        : 'border-gray-200 hover:border-gray-300',
                    ].join(' ')}
                  >
                    <div className="aspect-video bg-gray-100 overflow-hidden">
                      <img
                        src={`https://picsum.photos/seed/video-${video.id}/320/180`}
                        alt={video.cctvName}
                        className="w-full h-full object-cover"
                        loading="lazy"
                        width={200}
                        height={113}
                      />
                    </div>
                    <div className={['p-2', checked ? 'bg-primary-50' : 'bg-white'].join(' ')}>
                      <p className="text-xs font-medium text-gray-800 truncate">{video.cctvName}</p>
                      <p className="text-xs text-gray-400">{video.eventType}</p>
                    </div>

                    {/* Checkbox indicator */}
                    <span
                      className={[
                        'absolute top-1.5 left-1.5 w-5 h-5 rounded border-2 flex items-center justify-center',
                        checked
                          ? 'bg-primary-600 border-primary-600 text-white'
                          : 'bg-white/80 border-gray-300',
                      ].join(' ')}
                    >
                      {checked && (
                        <svg
                          viewBox="0 0 12 12"
                          className="w-3 h-3"
                          fill="none"
                          stroke="currentColor"
                          strokeWidth={2.5}
                        >
                          <path d="M2 6l3 3 5-5" strokeLinecap="round" strokeLinejoin="round" />
                        </svg>
                      )}
                    </span>
                  </button>
                );
              })}
            </div>
          </>
        )}

        {selectedVideoIds.size === 0 && !isLoading && videos.length > 0 && (
          <p className="flex items-center gap-1.5 text-xs text-amber-600">
            <AlertCircle size={13} />
            대상 영상을 하나 이상 선택하세요.
          </p>
        )}
      </section>

      {/* 최근 요청 이력 */}
      <section className="space-y-4">
        <div className="flex items-center justify-between">
          <div className="flex items-center gap-2">
            <History size={18} className="text-gray-500" />
            <h2 className="text-base font-semibold text-gray-800">최근 요청 이력</h2>
          </div>
          {jobsPage && (
            <Badge tone="neutral" size="sm">
              {jobsPage.totalElements}건
            </Badge>
          )}
        </div>

        {jobsLoading ? (
          <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-3">
            {Array.from({ length: 6 }, (_, i) => (
              <Skeleton key={i} height="9rem" />
            ))}
          </div>
        ) : !jobsPage || jobsPage.content.length === 0 ? (
          <div className="bg-gray-50 rounded-lg p-8 text-center text-sm text-gray-500">
            아직 증강 요청 이력이 없습니다.
          </div>
        ) : (
          <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-3">
            {jobsPage.content.map((job) => {
              const progressTone =
                job.status === 'FAILED'
                  ? 'danger'
                  : job.status === 'COMPLETED'
                    ? 'success'
                    : 'primary';

              return (
                <button
                  key={job.id}
                  type="button"
                  onClick={() => navigate(`/augment/result/${job.id}`)}
                  className={[
                    'text-left bg-white border border-gray-200 rounded-lg p-4 space-y-3',
                    'hover:border-primary-400 hover:shadow-sm transition-all cursor-pointer',
                    'focus:outline-none focus:ring-2 focus:ring-primary-500 focus:ring-offset-1',
                  ].join(' ')}
                >
                  {/* 상단: jobId + 상태 배지 + (COMPLETED만) 활용 결정 배지 */}
                  <div className="flex items-center justify-between gap-2">
                    <span className="font-mono text-xs text-gray-400 truncate">{job.id}</span>
                    <div className="flex items-center gap-1.5 shrink-0">
                      {job.status === 'COMPLETED' && (
                        <Badge tone={DECISION_TONE[job.decision ?? 'PENDING']} size="sm">
                          {DECISION_LABEL[job.decision ?? 'PENDING']}
                        </Badge>
                      )}
                      <Badge tone={STATUS_TONE[job.status]} size="sm">
                        {STATUS_LABEL[job.status]}
                      </Badge>
                    </div>
                  </div>

                  {/* 증강 유형 칩들 */}
                  <div className="flex flex-wrap gap-1">
                    {job.types.map((t) => (
                      <span
                        key={t}
                        className="inline-flex items-center text-xs bg-purple-50 text-purple-700 px-2 py-0.5 rounded-full"
                      >
                        {TYPE_CHIP[t]}
                      </span>
                    ))}
                  </div>

                  {/* 영상 건수 + 생성 시각 */}
                  <div className="flex items-center justify-between text-xs text-gray-500">
                    <span>{job.videoIds.length}건 영상</span>
                    <span>
                      {new Date(job.createdAt).toLocaleString('ko-KR', {
                        month: '2-digit',
                        day: '2-digit',
                        hour: '2-digit',
                        minute: '2-digit',
                      })}
                    </span>
                  </div>

                  {/* 진행률 */}
                  {job.status === 'FAILED' ? (
                    <p className="text-xs font-medium text-red-500">처리 실패</p>
                  ) : (
                    <div className="space-y-1">
                      <div className="flex items-center justify-between text-xs text-gray-400">
                        <span>진행률</span>
                        <span className="tabular-nums">
                          {job.status === 'COMPLETED' ? 100 : job.progress}%
                        </span>
                      </div>
                      <ProgressBar
                        value={job.status === 'COMPLETED' ? 100 : job.progress}
                        tone={progressTone}
                        size="sm"
                      />
                    </div>
                  )}
                </button>
              );
            })}
          </div>
        )}
      </section>

      {/* Bottom bar */}
      <div className="fixed bottom-0 left-0 right-0 z-20 bg-white border-t border-gray-200 px-6 py-4">
        <div className="max-w-6xl mx-auto flex items-center justify-between gap-4">
          <div className="text-sm text-gray-600">
            선택:{' '}
            <span className="font-semibold text-primary-600">
              {selectedTypes.size}종
            </span>{' '}
            ×{' '}
            <span className="font-semibold text-primary-600">
              {selectedVideoIds.size}건
            </span>{' '}
            = 예상{' '}
            <span className="font-bold text-gray-900">{totalEstimated}건</span>
          </div>
          <div className="flex items-center gap-3">
            <Button
              variant="secondary"
              size="md"
              onClick={() => navigate(-1)}
              disabled={isSubmitting}
            >
              취소
            </Button>
            <Button
              variant="primary"
              size="md"
              leftIcon={Wand2}
              loading={isSubmitting}
              disabled={!canSubmit}
              onClick={() => { void handleSubmit(); }}
            >
              증강 요청
            </Button>
          </div>
        </div>
      </div>
    </div>
  );
}

export default AugmentRequest;
