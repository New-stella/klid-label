import { useState } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import { ArrowLeft, RefreshCw, Check } from 'lucide-react';
import { useFetch } from '../../api/queries';
import { api } from '../../api/client';
import type { DeidentDto } from '../../api/types';
import { Badge } from '../../components/ui/Badge';
import { Button } from '../../components/ui/Button';
import { Skeleton } from '../../components/ui/Skeleton';
import { SideBySideCompare } from '../../components/common/SideBySideCompare';
import { useToast } from '../../components/common/Toast';

const TOTAL_FRAMES = 12;

const PRIVACY_LABEL: Record<string, string> = {
  PRVC: '개인영상',
  PSDO: '가명처리',
  ANONY: '익명화',
};

const STATUS_TONE: Record<string, 'success' | 'danger' | 'info' | 'neutral'> = {
  SUCCESS: 'success',
  FAIL: 'danger',
  PENDING: 'info',
  'N/A': 'neutral',
};

const STATUS_LABEL: Record<string, string> = {
  SUCCESS: '성공',
  FAIL: '실패',
  PENDING: '처리중',
  'N/A': 'N/A',
};

/** 프레임 인덱스 → 시간 라벨 (5초 간격) */
function frameTimeLabel(idx: number): string {
  const secs = idx * 5;
  const mm = String(Math.floor(secs / 60)).padStart(2, '0');
  const ss = String(secs % 60).padStart(2, '0');
  return `${mm}:${ss}`;
}

// Static fake timeline per deident record
function getTimeline(item: DeidentDto): { time: string; message: string }[] {
  const base = item.processedAt
    ? new Date(item.processedAt).getTime()
    : Date.now();
  const fmt = (d: Date) =>
    d.toLocaleString('ko-KR', {
      year: 'numeric',
      month: '2-digit',
      day: '2-digit',
      hour: '2-digit',
      minute: '2-digit',
    });
  return [
    { time: fmt(new Date(base - 120_000)), message: '비식별 요청' },
    { time: fmt(new Date(base - 90_000)), message: '처리 대기열 등록' },
    { time: fmt(new Date(base - 30_000)), message: '블러 박스 감지 시작' },
    {
      time: fmt(new Date(base)),
      message:
        item.status === 'SUCCESS'
          ? `처리 완료 (${TOTAL_FRAMES}프레임)`
          : '처리 실패',
    },
  ];
}

export function DeidentCompare() {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const { showToast } = useToast();
  const [retrying, setRetrying] = useState(false);
  const [selectedFrameIdx, setSelectedFrameIdx] = useState(0);

  const { data: item, isLoading, error } = useFetch<DeidentDto>(`/deident/${id ?? ''}`);

  const handleRetry = async () => {
    if (!id) return;
    setRetrying(true);
    try {
      await api.post(`/deident/${id}/retry`);
      showToast('재처리 요청됨', 'success');
    } catch {
      showToast('재처리 요청 실패', 'error');
    } finally {
      setRetrying(false);
    }
  };

  if (isLoading) {
    return (
      <div className="p-6 space-y-4">
        <Skeleton height="2rem" width="40%" />
        <Skeleton height="12rem" />
        <Skeleton height="8rem" />
        <Skeleton height="24rem" />
      </div>
    );
  }

  if (error || !item) {
    return (
      <div className="p-6 flex flex-col items-center justify-center min-h-64 gap-4">
        <p className="text-gray-500 text-sm">비식별 항목을 찾을 수 없습니다. (404)</p>
        <Button variant="secondary" size="sm" onClick={() => navigate('/deident')}>
          목록으로
        </Button>
      </div>
    );
  }

  const timeline = getTimeline(item);
  const processingTime = item.processedAt ? 90 : null; // fake seconds

  const hasDeid = item.status !== 'FAIL' && !!item.deidentifiedUrl;

  // 선택된 프레임의 picsum URL (큰 비교 뷰용)
  const origSrc = `https://picsum.photos/seed/orig-${item.id}-${selectedFrameIdx}/1280/720`;
  const deidSrc = hasDeid
    ? `https://picsum.photos/seed/deid-${item.id}-${selectedFrameIdx}/1280/720?blur=2`
    : null;

  return (
    <div className="p-6 space-y-6">
      {/* Header */}
      <div className="flex items-center gap-3">
        <button
          onClick={() => navigate('/deident')}
          className="p-2 rounded-lg hover:bg-gray-100 text-gray-500 transition-colors"
          aria-label="뒤로가기"
        >
          <ArrowLeft size={18} />
        </button>
        <div>
          <h1 className="text-xl font-bold text-gray-900">비식별 결과 비교</h1>
          <p className="text-sm text-gray-500 mt-0.5">
            {item.videoName}
            <span className="ml-2 text-gray-400">· 영상 단위 비식별</span>
          </p>
        </div>
        <div className="ml-auto flex items-center gap-2">
          {item.status === 'FAIL' && (
            <Button
              variant="secondary"
              size="sm"
              leftIcon={RefreshCw}
              loading={retrying}
              onClick={handleRetry}
            >
              재처리 요청
            </Button>
          )}
        </div>
      </div>

      {/* Processing info card */}
      <div className="bg-white border border-gray-200 rounded-lg p-5">
        <h2 className="text-sm font-semibold text-gray-700 mb-3">처리 정보</h2>
        <dl className="grid grid-cols-2 md:grid-cols-4 gap-x-6 gap-y-3 text-sm">
          <div>
            <dt className="text-xs text-gray-500 mb-0.5">처리 상태</dt>
            <dd>
              <Badge tone={STATUS_TONE[item.status] ?? 'neutral'} size="sm">
                {STATUS_LABEL[item.status] ?? item.status}
              </Badge>
            </dd>
          </div>
          <div>
            <dt className="text-xs text-gray-500 mb-0.5">처리 소요시간</dt>
            <dd className="font-medium text-gray-800">
              {processingTime != null ? `${processingTime}초` : '—'}
            </dd>
          </div>
          <div>
            <dt className="text-xs text-gray-500 mb-0.5">개인정보유형</dt>
            <dd className="font-medium text-gray-800">
              {PRIVACY_LABEL[item.privacyType] ?? item.privacyType}
            </dd>
          </div>
          <div>
            <dt className="text-xs text-gray-500 mb-0.5">총 프레임 수</dt>
            <dd className="font-medium text-gray-800">{TOTAL_FRAMES}프레임 (영상 단위 처리)</dd>
          </div>
        </dl>
      </div>

      {/* Frame grid section */}
      <div className="bg-white border border-gray-200 rounded-lg p-5">
        <div className="flex items-center justify-between mb-3">
          <h2 className="text-sm font-semibold text-gray-700">프레임 비교</h2>
          <Badge tone="neutral" size="sm">
            {selectedFrameIdx + 1}/{TOTAL_FRAMES} 프레임
          </Badge>
        </div>
        <div className="grid grid-cols-3 md:grid-cols-6 gap-2">
          {Array.from({ length: TOTAL_FRAMES }, (_, idx) => {
            const isSelected = selectedFrameIdx === idx;
            const thumbOrig = `https://picsum.photos/seed/orig-${item.id}-${idx}/160/90`;
            const thumbDeid = hasDeid
              ? `https://picsum.photos/seed/deid-${item.id}-${idx}/160/90?blur=2`
              : null;

            return (
              <button
                key={idx}
                onClick={() => setSelectedFrameIdx(idx)}
                className={[
                  'relative rounded-lg overflow-hidden border-2 transition-all text-left',
                  isSelected
                    ? 'border-primary-500 shadow-md'
                    : 'border-gray-200 hover:border-gray-400',
                ].join(' ')}
                aria-label={`프레임 ${frameTimeLabel(idx)} 선택`}
              >
                {/* Selected check icon */}
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

                {/* Deidentified thumbnail or failure notice */}
                <div className="bg-gray-800">
                  {thumbDeid ? (
                    <img
                      src={thumbDeid}
                      alt={`비식별 프레임 ${frameTimeLabel(idx)}`}
                      className="w-full block"
                      loading="lazy"
                      width={160}
                      height={90}
                    />
                  ) : (
                    <div className="w-full flex items-center justify-center text-gray-400 text-[10px] py-2 px-1">
                      비식별 실패
                    </div>
                  )}
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
          상단: 원본 / 하단: 비식별 — 프레임 클릭 시 아래 비교 뷰가 전환됩니다.
        </p>
      </div>

      {/* Selected frame caption */}
      <div className="flex items-center gap-2">
        <span className="text-xs text-gray-400">
          프레임 {frameTimeLabel(selectedFrameIdx)} 기준 · 좌우 비교
        </span>
      </div>

      {/* Image comparison — selected frame (좌우 비교) */}
      {deidSrc ? (
        <SideBySideCompare
          beforeSrc={origSrc}
          afterSrc={deidSrc}
          height={420}
          label={{ before: '원본', after: '비식별' }}
        />
      ) : (
        <div className="grid grid-cols-2 gap-2 w-full" style={{ height: 420 }}>
          <div className="relative overflow-hidden rounded-lg bg-gray-900">
            <img
              src={origSrc}
              alt="원본"
              className="absolute inset-0 w-full h-full object-cover"
              loading="lazy"
            />
            <span className="absolute top-3 left-3 bg-black/60 text-white text-xs font-semibold px-2.5 py-1 rounded">
              원본
            </span>
          </div>
          <div className="relative overflow-hidden rounded-lg bg-gray-900 flex items-center justify-center">
            <span className="text-gray-400 text-sm">비식별 이미지 없음</span>
            <span className="absolute top-3 left-3 bg-blue-600/90 text-white text-xs font-semibold px-2.5 py-1 rounded">
              비식별
            </span>
          </div>
        </div>
      )}

      {/* Processing timeline */}
      <div className="bg-white border border-gray-200 rounded-lg p-5">
        <h2 className="text-sm font-semibold text-gray-700 mb-4">처리 이력</h2>
        <ul className="relative border-l-2 border-gray-200 space-y-4 pl-5">
          {timeline.map((entry, idx) => (
            <li key={idx} className="relative">
              <span className="absolute -left-[1.4rem] top-0.5 w-3 h-3 rounded-full bg-gray-300 border-2 border-white" />
              <p className="text-xs text-gray-400 mb-0.5">{entry.time}</p>
              <p className="text-sm text-gray-700">{entry.message}</p>
            </li>
          ))}
        </ul>
      </div>
    </div>
  );
}

export default DeidentCompare;
