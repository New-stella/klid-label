import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Sparkles } from 'lucide-react';
import { Modal } from '../ui/Modal';
import { Button } from '../ui/Button';
import { EventTypeBadge } from './EventTypeBadge';
import { useToast } from '../common/Toast';
import { api } from '../../api/client';
import type { VideoDto } from '../../api/types';

interface Props {
  open: boolean;
  video: VideoDto | null;
  onClose: () => void;
}

type GenerateType = 'FIRE' | 'FLOOD';

const FRAME_COUNT = 12;

const formatFrameTime = (idx: number): string => {
  const totalSeconds = idx * 5;
  const minutes = Math.floor(totalSeconds / 60);
  const seconds = totalSeconds % 60;
  return `${minutes.toString().padStart(2, '0')}:${seconds.toString().padStart(2, '0')}`;
};

const TYPE_OPTIONS: { value: GenerateType; icon: string; label: string; desc: string }[] = [
  { value: 'FIRE', icon: '🔥', label: '산불 배경', desc: '산불 이벤트용 배경 영상 생성' },
  { value: 'FLOOD', icon: '🌊', label: '침수 배경', desc: '침수 이벤트용 배경 영상 생성' },
];

export function BackgroundGenerateModal({ open, video, onClose }: Props) {
  const navigate = useNavigate();
  const { showToast } = useToast();

  const [selectedFrame, setSelectedFrame] = useState<number | null>(null);
  const [selectedType, setSelectedType] = useState<GenerateType | null>(null);
  const [submitting, setSubmitting] = useState(false);

  // Reset selection whenever the modal opens or the source video changes
  useEffect(() => {
    if (open) {
      setSelectedFrame(null);
      setSelectedType(null);
      setSubmitting(false);
    }
  }, [open, video?.id]);

  if (!video) return null;

  const canSubmit = selectedFrame !== null && selectedType !== null;

  const handleSubmit = async () => {
    if (!canSubmit) {
      if (selectedFrame === null) showToast('프레임을 선택하세요.', 'info');
      else showToast('생성 유형을 선택하세요.', 'info');
      return;
    }
    if (selectedFrame === null || selectedType === null) return;
    setSubmitting(true);
    try {
      const result = await api.post<{ jobId: string }>('/generate/background', {
        videoId: video.id,
        frameIndex: selectedFrame,
        type: selectedType,
      });
      showToast('관제서버로 배경영상 생성 요청이 전달되었습니다', 'success');
      onClose();
      navigate(`/generate/result/${result.jobId}`);
    } catch (err) {
      const message = err instanceof Error ? err.message : '요청 전송에 실패했습니다.';
      showToast(message, 'error');
      setSubmitting(false);
    }
  };

  const footer = (
    <>
      <Button variant="secondary" size="md" onClick={onClose} disabled={submitting}>
        취소
      </Button>
      <Button
        variant="primary"
        size="md"
        leftIcon={Sparkles}
        onClick={handleSubmit}
        disabled={!canSubmit || submitting}
        loading={submitting}
      >
        요청 보내기
      </Button>
    </>
  );

  return (
    <Modal
      open={open}
      onClose={submitting ? () => undefined : onClose}
      title="✨ 배경영상 요청"
      size="2xl"
      footer={footer}
    >
      <div className="space-y-5">
        {/* 영상 정보 */}
        <section className="flex items-center gap-3 bg-gray-50 rounded-lg p-3 border border-gray-200">
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
          <div className="flex-1 min-w-0">
            <p className="text-sm font-semibold text-gray-800 truncate">{video.cctvName}</p>
            <div className="mt-1 flex items-center gap-2">
              <EventTypeBadge eventType={video.eventType} />
              <span className="text-xs text-gray-400 font-mono">{video.id}</span>
            </div>
          </div>
        </section>

        {/* 프레임 선택 (단일) */}
        <section className="space-y-2">
          <div className="flex items-center justify-between">
            <h3 className="text-sm font-semibold text-gray-700">
              프레임 선택 <span className="text-xs text-gray-400 font-normal">(단일 선택)</span>
            </h3>
            {selectedFrame !== null && (
              <span className="text-xs text-primary-700 font-medium">
                프레임 {selectedFrame + 1} ({formatFrameTime(selectedFrame)}) 선택됨
              </span>
            )}
          </div>

          <div
            role="radiogroup"
            aria-label="프레임 선택"
            className="grid grid-cols-3 md:grid-cols-6 gap-2"
          >
            {Array.from({ length: FRAME_COUNT }, (_, idx) => {
              const isSelected = selectedFrame === idx;
              const thumbUrl = `https://picsum.photos/seed/frame-${video.id}-${idx}/320/180`;
              return (
                <button
                  key={idx}
                  type="button"
                  role="radio"
                  aria-checked={isSelected}
                  onClick={() => setSelectedFrame(idx)}
                  className={[
                    'relative text-left rounded-md border-2 overflow-hidden transition-all cursor-pointer',
                    'focus:outline-none focus:ring-2 focus:ring-primary-500 focus:ring-offset-1',
                    isSelected ? 'border-primary-500 shadow-sm' : 'border-gray-200 hover:border-gray-300',
                  ].join(' ')}
                >
                  <div className="aspect-video bg-gray-100 overflow-hidden">
                    <img
                      src={thumbUrl}
                      alt={`프레임 ${idx + 1}`}
                      className="w-full h-full object-cover"
                      loading="lazy"
                      width={160}
                      height={90}
                    />
                  </div>
                  <div
                    className={[
                      'px-2 py-1 flex items-center justify-between',
                      isSelected ? 'bg-primary-50' : 'bg-white',
                    ].join(' ')}
                  >
                    <span className="text-[11px] font-medium text-gray-700">
                      프레임 {idx + 1}
                    </span>
                    <span className="text-[11px] tabular-nums text-gray-400">
                      {formatFrameTime(idx)}
                    </span>
                  </div>
                  <span
                    className={[
                      'absolute top-1 right-1 w-5 h-5 rounded-full border-2 flex items-center justify-center',
                      isSelected ? 'bg-primary-600 border-primary-600' : 'bg-white/80 border-gray-300',
                    ].join(' ')}
                    aria-hidden="true"
                  >
                    {isSelected && <span className="block w-2 h-2 rounded-full bg-white" />}
                  </span>
                </button>
              );
            })}
          </div>
        </section>

        {/* 생성 유형 선택 */}
        <section className="space-y-2">
          <h3 className="text-sm font-semibold text-gray-700">
            생성 유형 선택{' '}
            <span className="text-xs text-gray-400 font-normal">(필수)</span>
          </h3>
          <div
            role="radiogroup"
            aria-label="생성 유형 선택"
            className="grid grid-cols-2 gap-3"
          >
            {TYPE_OPTIONS.map((opt) => {
              const isSelected = selectedType === opt.value;
              return (
                <button
                  key={opt.value}
                  type="button"
                  role="radio"
                  aria-checked={isSelected}
                  onClick={() => setSelectedType(opt.value)}
                  className={[
                    'flex items-center gap-3 rounded-lg border-2 px-4 py-3 text-left transition-all cursor-pointer',
                    'focus:outline-none focus:ring-2 focus:ring-primary-500 focus:ring-offset-1',
                    isSelected
                      ? 'border-primary-500 bg-primary-50 shadow-sm'
                      : 'border-gray-200 bg-white hover:border-gray-300',
                  ].join(' ')}
                >
                  <span className="text-2xl shrink-0" aria-hidden="true">
                    {opt.icon}
                  </span>
                  <div className="min-w-0">
                    <p className={[
                      'text-sm font-semibold',
                      isSelected ? 'text-primary-700' : 'text-gray-800',
                    ].join(' ')}>
                      {opt.label}
                    </p>
                    <p className="text-xs text-gray-500 mt-0.5">{opt.desc}</p>
                  </div>
                  <span
                    className={[
                      'ml-auto w-5 h-5 rounded-full border-2 flex items-center justify-center shrink-0',
                      isSelected ? 'bg-primary-600 border-primary-600' : 'bg-white border-gray-300',
                    ].join(' ')}
                    aria-hidden="true"
                  >
                    {isSelected && <span className="block w-2 h-2 rounded-full bg-white" />}
                  </span>
                </button>
              );
            })}
          </div>
        </section>

        {/* 요청 요약 */}
        <section className="rounded-md bg-gray-50 border border-gray-200 px-3 py-2.5 text-xs text-gray-600">
          이 요청은 관제서버로 전달됩니다. 결과 영상은 별도 채널로 회신됩니다.
        </section>
      </div>
    </Modal>
  );
}

export default BackgroundGenerateModal;
