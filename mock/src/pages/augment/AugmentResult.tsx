import { useState, useEffect } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import { ArrowLeft, RefreshCw, Layers, DatabaseZap, Check, ChevronDown, ThumbsUp, ThumbsDown, RotateCcw } from 'lucide-react';
import { useFetch } from '../../api/queries';
import { api } from '../../api/client';
import type { AugmentJob, AugmentDecision } from '../../api/types';
import { Badge } from '../../components/ui/Badge';
import { Button } from '../../components/ui/Button';
import { Modal } from '../../components/ui/Modal';
import { ProgressBar } from '../../components/ui/ProgressBar';
import { Skeleton } from '../../components/ui/Skeleton';
import { CompareSlider } from '../../components/common/CompareSlider';
import { useToast } from '../../components/common/Toast';

type AugmentType = 'WINTER' | 'NIGHT' | 'RAIN' | 'RESOLUTION';

const TOTAL_FRAMES = 12;
const INITIAL_VIDEO_LIMIT = 2;

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

const TYPE_LABEL: Record<AugmentType, string> = {
  WINTER: '❄️ 겨울',
  NIGHT: '🌙 야간',
  RAIN: '🌧 비',
  RESOLUTION: '📐 해상도',
};

const DECISION_LABEL: Record<AugmentDecision, string> = {
  PENDING: '결정 대기',
  ACCEPTED: '학습데이터로 활용됨',
  REJECTED: '활용 거부됨',
};

const DECISION_TONE: Record<AugmentDecision, 'warning' | 'success' | 'danger'> = {
  PENDING: 'warning',
  ACCEPTED: 'success',
  REJECTED: 'danger',
};

/** 프레임 인덱스 → 시간 라벨 (5초 간격) */
function frameTimeLabel(idx: number): string {
  const secs = idx * 5;
  const mm = String(Math.floor(secs / 60)).padStart(2, '0');
  const ss = String(secs % 60).padStart(2, '0');
  return `${mm}:${ss}`;
}

function IntegrityNumber({ value }: { value: number }) {
  const colorClass =
    value >= 95
      ? 'text-green-600'
      : value >= 85
        ? 'text-yellow-500'
        : 'text-red-500';

  return (
    <span className={['text-5xl font-black tabular-nums', colorClass].join(' ')}>
      {value}%
    </span>
  );
}

/** 영상 1건 × 활성 유형 × 선택 프레임 비교 섹션 */
function VideoSection({
  vid,
  types,
  activeType,
  onTypeChange,
  selectedFrameIdx,
  onFrameSelect,
}: {
  vid: string;
  types: AugmentType[];
  activeType: AugmentType;
  onTypeChange: (t: AugmentType) => void;
  selectedFrameIdx: number;
  onFrameSelect: (idx: number) => void;
}) {
  const beforeLarge = `https://picsum.photos/seed/orig-${vid}-${selectedFrameIdx}/1280/720`;
  const afterLarge = `https://picsum.photos/seed/${activeType.toLowerCase()}-${vid}-${selectedFrameIdx}/1280/720`;

  return (
    <div className="bg-white border border-gray-200 rounded-lg p-5 space-y-4">
      {/* Video header */}
      <div className="flex items-center justify-between flex-wrap gap-2">
        <span className="font-mono text-xs text-gray-500 truncate">{vid}</span>
        <div className="flex flex-wrap gap-1.5">
          {types.map((t) => (
            <span
              key={t}
              className="text-xs px-2 py-0.5 rounded-full bg-blue-50 text-blue-700 border border-blue-200"
            >
              {TYPE_LABEL[t]}
            </span>
          ))}
        </div>
      </div>

      {/* Type tabs (only when more than one type) */}
      {types.length > 1 && (
        <div className="flex gap-2 flex-wrap">
          {types.map((t) => (
            <button
              key={t}
              onClick={() => onTypeChange(t)}
              className={[
                'text-sm px-3 py-1.5 rounded-lg border transition-colors',
                activeType === t
                  ? 'bg-primary-600 text-white border-primary-600'
                  : 'bg-white text-gray-600 border-gray-300 hover:bg-gray-50',
              ].join(' ')}
            >
              {TYPE_LABEL[t]}
            </button>
          ))}
        </div>
      )}

      {/* Frame grid */}
      <div>
        <div className="flex items-center justify-between mb-2">
          <p className="text-xs font-semibold text-gray-600">프레임 그리드</p>
          <Badge tone="neutral" size="sm">
            {selectedFrameIdx + 1}/{TOTAL_FRAMES} 선택
          </Badge>
        </div>
        <div className="grid grid-cols-3 md:grid-cols-6 gap-2">
          {Array.from({ length: TOTAL_FRAMES }, (_, idx) => {
            const isSelected = selectedFrameIdx === idx;
            const thumbOrig = `https://picsum.photos/seed/orig-${vid}-${idx}/160/90`;
            const thumbAug = `https://picsum.photos/seed/${activeType.toLowerCase()}-${vid}-${idx}/160/90`;

            return (
              <button
                key={idx}
                onClick={() => onFrameSelect(idx)}
                className={[
                  'relative rounded-lg overflow-hidden border-2 transition-all text-left',
                  isSelected
                    ? 'border-primary-500 shadow-md'
                    : 'border-gray-200 hover:border-gray-400',
                ].join(' ')}
                aria-label={`프레임 ${frameTimeLabel(idx)} 선택`}
              >
                {isSelected && (
                  <span className="absolute top-1 right-1 z-10 w-4 h-4 bg-primary-500 rounded-full flex items-center justify-center">
                    <Check size={10} className="text-white" strokeWidth={3} />
                  </span>
                )}
                {/* Original thumbnail */}
                <div className="bg-gray-900">
                  <img
                    src={thumbOrig}
                    alt={`원본 프레임 ${frameTimeLabel(idx)}`}
                    className="w-full block"
                    loading="lazy"
                    width={160}
                    height={90}
                  />
                </div>
                {/* Augmented thumbnail */}
                <div className="bg-gray-800">
                  <img
                    src={thumbAug}
                    alt={`${TYPE_LABEL[activeType]} 프레임 ${frameTimeLabel(idx)}`}
                    className="w-full block"
                    loading="lazy"
                    width={160}
                    height={90}
                  />
                </div>
                {/* Frame time label */}
                <div className="bg-gray-900 text-center py-0.5">
                  <span className="text-[10px] text-gray-400 font-mono">
                    {frameTimeLabel(idx)}
                  </span>
                </div>
              </button>
            );
          })}
        </div>
        <p className="text-xs text-gray-400 mt-2">
          상단: 원본 / 하단: {TYPE_LABEL[activeType]} 증강 — 프레임 클릭 시 아래 비교 뷰가 전환됩니다.
        </p>
      </div>

      {/* Large CompareSlider for selected frame */}
      <CompareSlider
        beforeSrc={beforeLarge}
        afterSrc={afterLarge}
        height={380}
        label={{ before: '원본', after: `${TYPE_LABEL[activeType]} 증강` }}
      />
    </div>
  );
}

export function AugmentResult() {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const { showToast } = useToast();

  const { data: job, isLoading, error, refetch } = useFetch<AugmentJob>(
    `/augment/${id ?? ''}`,
  );

  // Shared active augment type across all video sections
  const [activeType, setActiveType] = useState<AugmentType>('WINTER');
  // Per-video selected frame index
  const [selectedFrameByVideo, setSelectedFrameByVideo] = useState<Record<string, number>>({});
  // Show all videos toggle
  const [showAll, setShowAll] = useState(false);

  // SFR-07 — 학습데이터 활용 결정
  const [rejectModalOpen, setRejectModalOpen] = useState(false);
  const [rejectReason, setRejectReason] = useState('');
  const [decisionSubmitting, setDecisionSubmitting] = useState(false);

  const submitDecision = async (
    decision: 'ACCEPTED' | 'REJECTED',
    reason?: string,
  ) => {
    if (!job) return;
    setDecisionSubmitting(true);
    try {
      await api.post(`/augment/${job.id}/decision`, { decision, reason });
      showToast(
        decision === 'ACCEPTED'
          ? '학습데이터로 활용 등록되었습니다.'
          : '활용이 거부되었습니다.',
        'success',
      );
      setRejectModalOpen(false);
      setRejectReason('');
      refetch();
    } catch {
      showToast('결정 저장에 실패했습니다.', 'error');
    } finally {
      setDecisionSubmitting(false);
    }
  };

  // Initialise activeType from job once loaded
  useEffect(() => {
    if (job?.types?.length) {
      setActiveType(job.types[0]);
    }
  }, [job?.types]);

  // Auto-refetch every 5s while PROCESSING
  useEffect(() => {
    if (job?.status !== 'PROCESSING' && job?.status !== 'PENDING') return;
    const timer = setInterval(() => refetch(), 5000);
    return () => clearInterval(timer);
  }, [job?.status, refetch]);

  if (isLoading) {
    return (
      <div className="p-6 space-y-4">
        <Skeleton height="2rem" width="40%" />
        <Skeleton height="8rem" />
        <Skeleton height="20rem" />
      </div>
    );
  }

  if (error || !job) {
    return (
      <div className="p-6 flex flex-col items-center justify-center min-h-64 gap-4">
        <p className="text-gray-500 text-sm">증강 작업을 찾을 수 없습니다. (404)</p>
        <Button variant="secondary" size="sm" onClick={() => navigate('/augment/request')}>
          목록으로
        </Button>
      </div>
    );
  }

  const progressTone =
    job.status === 'FAILED' ? 'danger' : job.status === 'COMPLETED' ? 'success' : 'primary';

  const totalProcessedImages = job.videoIds.length * TOTAL_FRAMES;
  const visibleVideos = showAll ? job.videoIds : job.videoIds.slice(0, INITIAL_VIDEO_LIMIT);
  const hiddenCount = job.videoIds.length - INITIAL_VIDEO_LIMIT;

  return (
    <div className="p-6 space-y-6">
      {/* Header */}
      <div className="flex items-center gap-3">
        <button
          onClick={() => navigate('/augment/request')}
          className="p-2 rounded-lg hover:bg-gray-100 text-gray-500 transition-colors"
          aria-label="뒤로가기"
        >
          <ArrowLeft size={18} />
        </button>
        <div className="flex-1">
          <h1 className="text-xl font-bold text-gray-900">증강 결과</h1>
          <p className="text-xs text-gray-400 mt-0.5">{job.id}</p>
        </div>
        <Badge tone={STATUS_TONE[job.status]} size="md">
          {STATUS_LABEL[job.status]}
        </Badge>
      </div>

      {/* Summary card */}
      <div className="bg-white border border-gray-200 rounded-lg p-5 space-y-4">
        <h2 className="text-sm font-semibold text-gray-700">작업 요약</h2>
        <dl className="grid grid-cols-2 md:grid-cols-5 gap-x-6 gap-y-3 text-sm">
          <div>
            <dt className="text-xs text-gray-500 mb-0.5">작업 ID</dt>
            <dd className="font-mono text-gray-800 text-xs">{job.id}</dd>
          </div>
          <div>
            <dt className="text-xs text-gray-500 mb-0.5">증강 유형</dt>
            <dd className="font-medium text-gray-800">
              {job.types.map((t) => TYPE_LABEL[t]).join(' · ')}
            </dd>
          </div>
          <div>
            <dt className="text-xs text-gray-500 mb-0.5">대상 영상</dt>
            <dd className="font-medium text-gray-800">{job.videoIds.length}건</dd>
          </div>
          <div>
            <dt className="text-xs text-gray-500 mb-0.5">총 처리 이미지</dt>
            <dd className="font-medium text-gray-800">
              {totalProcessedImages.toLocaleString()}장
              <span className="text-xs text-gray-400 ml-1">
                ({job.videoIds.length}건 × {TOTAL_FRAMES}프레임)
              </span>
            </dd>
          </div>
          <div>
            <dt className="text-xs text-gray-500 mb-0.5">생성일</dt>
            <dd className="font-medium text-gray-800">
              {new Date(job.createdAt).toLocaleString('ko-KR')}
            </dd>
          </div>
        </dl>

        {/* Progress bar */}
        <div className="space-y-1.5">
          <div className="flex items-center justify-between text-xs text-gray-500">
            <span>진행률</span>
            <span className="font-medium tabular-nums">{job.progress}%</span>
          </div>
          <ProgressBar value={job.progress} tone={progressTone} size="md" />
        </div>
      </div>

      {/* PROCESSING state */}
      {(job.status === 'PROCESSING' || job.status === 'PENDING') && (
        <div className="bg-blue-50 border border-blue-200 rounded-lg p-6 flex items-center gap-4">
          <RefreshCw size={22} className="text-blue-500 animate-spin shrink-0" />
          <div>
            <p className="text-sm font-semibold text-blue-700">처리 중...</p>
            <p className="text-xs text-blue-500 mt-0.5">5초마다 자동으로 상태를 갱신합니다.</p>
          </div>
        </div>
      )}

      {/* FAILED state */}
      {job.status === 'FAILED' && (
        <div className="bg-red-50 border border-red-200 rounded-lg p-6 flex items-center justify-between gap-4">
          <div>
            <p className="text-sm font-semibold text-red-700">증강 처리 실패</p>
            <p className="text-xs text-red-500 mt-0.5">
              AI 서버 응답 오류 또는 리소스 부족으로 처리가 중단되었습니다.
            </p>
          </div>
          <Button
            variant="secondary"
            size="sm"
            leftIcon={RefreshCw}
            onClick={() => {
              showToast('재시도 요청이 등록되었습니다.', 'info');
            }}
          >
            재시도
          </Button>
        </div>
      )}

      {/* COMPLETED state */}
      {job.status === 'COMPLETED' && (
        <>
          {/* Integrity */}
          <div className="bg-white border border-gray-200 rounded-lg p-6 flex flex-col items-center gap-2">
            <div className="flex items-center gap-2 text-sm font-semibold text-gray-600">
              <Layers size={16} />
              라벨 무결성
            </div>
            <IntegrityNumber value={job.labelIntegrity} />
            <p className="text-xs text-gray-400">
              {job.labelIntegrity >= 95
                ? '우수 — 라벨 정합성이 매우 높습니다'
                : job.labelIntegrity >= 85
                  ? '양호 — 일부 라벨을 검토해 주세요'
                  : '주의 — 라벨 검수가 필요합니다'}
            </p>
          </div>

          {/* Before/After comparison — per video */}
          <div className="space-y-4">
            <div>
              <h2 className="text-base font-semibold text-gray-800">원본 vs 증강 비교</h2>
              <p className="text-xs text-gray-400 mt-0.5">
                각 영상의 프레임 {TOTAL_FRAMES}장에 대해 증강이 적용됩니다.
              </p>
            </div>

            {visibleVideos.map((vid) => (
              <VideoSection
                key={vid}
                vid={vid}
                types={job.types}
                activeType={activeType}
                onTypeChange={setActiveType}
                selectedFrameIdx={selectedFrameByVideo[vid] ?? 0}
                onFrameSelect={(idx) =>
                  setSelectedFrameByVideo((prev) => ({ ...prev, [vid]: idx }))
                }
              />
            ))}

            {/* Show more button */}
            {!showAll && hiddenCount > 0 && (
              <button
                onClick={() => setShowAll(true)}
                className="w-full flex items-center justify-center gap-2 py-3 border border-dashed border-gray-300 rounded-lg text-sm text-gray-500 hover:bg-gray-50 hover:border-gray-400 transition-colors"
              >
                <ChevronDown size={16} />
                + {hiddenCount}건 더 보기
              </button>
            )}
          </div>

          {/* SFR-07 — 학습데이터 활용 결정 */}
          <div className="bg-white border border-gray-200 rounded-lg p-5 space-y-4">
            <div className="flex items-center justify-between flex-wrap gap-2">
              <h2 className="text-base font-semibold text-gray-800">학습데이터 활용 결정</h2>
              <Badge tone={DECISION_TONE[job.decision ?? 'PENDING']} size="md">
                {DECISION_LABEL[job.decision ?? 'PENDING']}
              </Badge>
            </div>

            {(job.decision ?? 'PENDING') === 'PENDING' ? (
              <>
                <p className="text-sm text-gray-600">
                  이 증강 결과를 검토하고 학습데이터로 활용할지 결정해주세요.
                </p>
                <div className="flex flex-wrap gap-3">
                  <Button
                    variant="primary"
                    size="md"
                    leftIcon={ThumbsUp}
                    loading={decisionSubmitting}
                    onClick={() => { void submitDecision('ACCEPTED'); }}
                  >
                    학습데이터로 활용
                  </Button>
                  <Button
                    variant="secondary"
                    size="md"
                    leftIcon={ThumbsDown}
                    disabled={decisionSubmitting}
                    onClick={() => setRejectModalOpen(true)}
                  >
                    거부
                  </Button>
                </div>
              </>
            ) : (
              <div className="space-y-2">
                <div className="text-sm text-gray-700">
                  {job.decision === 'ACCEPTED'
                    ? '이 증강 결과는 학습데이터로 활용 등록되었습니다.'
                    : '이 증강 결과는 활용 거부되었습니다.'}
                </div>
                {(job.decisionAt || job.decisionBy) && (
                  <div className="text-xs text-gray-500 flex flex-wrap gap-x-4 gap-y-1">
                    {job.decisionAt && (
                      <span>
                        결정 시각:{' '}
                        <span className="text-gray-700">
                          {new Date(job.decisionAt).toLocaleString('ko-KR')}
                        </span>
                      </span>
                    )}
                    {job.decisionBy && (
                      <span>
                        결정자: <span className="text-gray-700 font-mono">{job.decisionBy}</span>
                      </span>
                    )}
                  </div>
                )}
                {job.decision === 'REJECTED' && job.decisionReason && (
                  <div className="text-xs bg-red-50 border border-red-100 rounded px-3 py-2 text-red-700">
                    사유: {job.decisionReason}
                  </div>
                )}
                <div className="pt-1">
                  <Button
                    variant="secondary"
                    size="sm"
                    leftIcon={RotateCcw}
                    disabled={decisionSubmitting}
                    onClick={() => {
                      // 결정 변경: PENDING으로 초기화하고 싶지만 핸들러는 ACCEPTED/REJECTED만 허용
                      // → 반대 결정으로 토글 (UX: 재검토 진입)
                      const next = job.decision === 'ACCEPTED' ? 'REJECTED' : 'ACCEPTED';
                      if (next === 'REJECTED') {
                        setRejectModalOpen(true);
                      } else {
                        void submitDecision('ACCEPTED');
                      }
                    }}
                  >
                    결정 변경
                  </Button>
                </div>
              </div>
            )}
          </div>

          {/* CTA */}
          <div className="flex justify-end">
            <Button
              variant="primary"
              size="md"
              leftIcon={DatabaseZap}
              disabled={(job.decision ?? 'PENDING') !== 'ACCEPTED'}
              onClick={() => showToast('데이터마트 등록 요청이 전달되었습니다.', 'success')}
            >
              증강 결과 반영 — 데이터마트 등록
            </Button>
          </div>

          {/* 거부 사유 입력 모달 */}
          <Modal
            open={rejectModalOpen}
            onClose={() => {
              if (decisionSubmitting) return;
              setRejectModalOpen(false);
              setRejectReason('');
            }}
            title="활용 거부 사유"
            size="md"
            footer={
              <>
                <Button
                  variant="secondary"
                  size="sm"
                  onClick={() => {
                    setRejectModalOpen(false);
                    setRejectReason('');
                  }}
                  disabled={decisionSubmitting}
                >
                  취소
                </Button>
                <Button
                  variant="primary"
                  size="sm"
                  loading={decisionSubmitting}
                  onClick={() => { void submitDecision('REJECTED', rejectReason.trim()); }}
                >
                  거부 확정
                </Button>
              </>
            }
          >
            <div className="space-y-2">
              <p className="text-sm text-gray-600">
                거부 사유를 입력해주세요. (선택)
              </p>
              <textarea
                value={rejectReason}
                onChange={(e) => setRejectReason(e.target.value)}
                rows={4}
                placeholder="예: 라벨 무결성 부족 — 재증강 필요"
                className="w-full text-sm border border-gray-300 rounded-lg px-3 py-2 focus:outline-none focus:ring-2 focus:ring-primary-500 resize-none"
              />
            </div>
          </Modal>
        </>
      )}
    </div>
  );
}

export default AugmentResult;
