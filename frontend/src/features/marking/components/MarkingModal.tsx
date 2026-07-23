import { useEffect, useState } from 'react';
import { AlertCircle, Sparkles, MousePointerClick } from 'lucide-react';

import { Button } from '@/components/common/Button';
import { Modal } from '@/components/common/Modal';
import { AssignModal } from '@/features/task/components/AssignModal';
import { cn } from '@/lib/cn';
import { KRDS_FOCUS } from '@/lib/focusRing';
import { useUiStore } from '@/stores/useUiStore';

import { useCreateMarking } from '../hooks/useMarkings';
import { useMarkingStore } from '../store';
import { MarkingMode } from '../types';

export interface MarkingModalProps {
  rawSn: number;
  videoName?: string;
  open: boolean;
  onClose: () => void;
  /** 자동마킹 트리거 성공 또는 수동 배정 성공 콜백 (목록 무효화용) */
  onMarked?: () => void;
}

type Step = 'select' | 'auto' | 'manual';

/**
 * BE 가 ApiResponse.message 로 내려준 사람 친화적 에러 메시지를 안전하게 추출한다.
 * axios 에러 형태(`err.response.data.message`)만 신뢰하고 그 외에는 fallback 사용.
 * (AssignModal.extractBeMessage 와 동일 패턴 — 토스트 노출 시 JSX 자동 이스케이프로 XSS 없음)
 */
function extractBeMessage(err: unknown, fallback: string): string {
  if (typeof err === 'object' && err !== null && 'response' in err) {
    const resp = (err as { response?: { data?: unknown } }).response;
    const data = resp?.data;
    if (typeof data === 'object' && data !== null && 'message' in data) {
      const m = (data as { message?: unknown }).message;
      if (typeof m === 'string' && m.trim() !== '') return m;
    }
  }
  return fallback;
}

const CHOICE_BTN =
  'flex items-start gap-3 rounded-lg border border-gray-200 p-4 text-left transition-colors hover:border-primary-400 hover:bg-primary-50';

/**
 * SCR 영상 처리 현황 — 마킹 진입 팝업.
 * - 자동: 프레임 간격 입력 → createMarking(AUTO) 트리거
 * - 수동: 기존 작업자 배정(AssignModal, mode='assign') 흐름으로 전환
 */
export function MarkingModal({
  rawSn,
  videoName,
  open,
  onClose,
  onMarked,
}: MarkingModalProps) {
  const pushToast = useUiStore((s) => s.pushToast);

  // 자동 마킹 프레임 간격 기본값은 스토어 단일 진실원(intervalFrames=300)에서 가져온다.
  // (하드코딩 대신 store 상수 재사용 — MarkingToolbar 프리필과 동일 소스)
  const defaultInterval = String(useMarkingStore((s) => s.intervalFrames));

  const [step, setStep] = useState<Step>('select');
  const [intervalInput, setIntervalInput] = useState(defaultInterval);
  const [error, setError] = useState<string | null>(null);

  const { mutate, isPending } = useCreateMarking(rawSn, {
    onSuccess: () => {
      pushToast({ variant: 'success', message: '자동 마킹을 시작했습니다.' });
      onMarked?.();
      onClose();
    },
    onError: (err) => {
      pushToast({
        variant: 'error',
        message: extractBeMessage(err, '자동 마킹에 실패했습니다'),
      });
    },
  });

  // 모달이 닫히면 단계/입력 초기화 (다음 오픈 시 항상 'select' 부터 시작)
  useEffect(() => {
    if (!open) {
      setStep('select');
      setIntervalInput(defaultInterval);
      setError(null);
    }
  }, [open, defaultInterval]);

  const handleAutoSubmit = () => {
    const trimmed = intervalInput.trim();
    const num = Number(trimmed);
    // 하한(1 이상 정수)만 검증한다. 상한 미검증은 의도 — 값이 클수록 서버가 생성하는
    // 마킹 수가 줄어 리소스 위험이 없고, 상한 백스톱은 BE(MarkingService)가 담당한다.
    if (trimmed === '' || !Number.isInteger(num) || num < 1) {
      setError('1 이상의 정수를 입력해주세요.');
      return;
    }
    setError(null);
    mutate({ mode: MarkingMode.AUTO, intervalFrames: num });
  };

  const handleManualSuccess = () => {
    onMarked?.();
    onClose();
  };

  // 'select' 로 되돌아갈 때 auto 단계의 입력/검증오류를 함께 초기화 (재진입 시 잔존 방지)
  const handleBackToSelect = () => {
    setStep('select');
    setIntervalInput(defaultInterval);
    setError(null);
  };

  // 수동 선택 → 작업자 배정 흐름(AssignModal)으로 전환. 자체 Modal 은 렌더하지 않는다.
  if (open && step === 'manual') {
    return (
      <AssignModal
        open={open}
        onClose={onClose}
        task={null}
        mode="assign"
        videoId={rawSn}
        videoName={videoName}
        onSuccess={handleManualSuccess}
      />
    );
  }

  if (!open) return null;

  const footer =
    step === 'auto' ? (
      <>
        <Button variant="outline" onClick={handleBackToSelect} disabled={isPending}>
          이전
        </Button>
        <Button
          variant="primary"
          onClick={handleAutoSubmit}
          loading={isPending}
          disabled={isPending}
        >
          자동 마킹 시작
        </Button>
      </>
    ) : undefined;

  return (
    <Modal
      open={open}
      onClose={onClose}
      title="마킹 방식 선택"
      description={videoName ? `영상: ${videoName}` : undefined}
      footer={footer}
    >
      {step === 'select' ? (
        <div className="flex flex-col gap-3">
          <button
            type="button"
            onClick={() => setStep('auto')}
            className={cn(CHOICE_BTN, KRDS_FOCUS)}
          >
            <Sparkles className="mt-0.5 h-5 w-5 shrink-0 text-primary-600" aria-hidden="true" />
            <span>
              <span className="block text-sm font-semibold text-gray-900">자동</span>
              <span className="block text-xs text-gray-500">
                프레임 간격을 지정해 자동으로 마킹을 생성합니다.
              </span>
            </span>
          </button>
          <button
            type="button"
            onClick={() => setStep('manual')}
            className={cn(CHOICE_BTN, KRDS_FOCUS)}
          >
            <MousePointerClick
              className="mt-0.5 h-5 w-5 shrink-0 text-primary-600"
              aria-hidden="true"
            />
            <span>
              <span className="block text-sm font-semibold text-gray-900">수동</span>
              <span className="block text-xs text-gray-500">
                작업자에게 배정하여 직접 마킹하도록 합니다.
              </span>
            </span>
          </button>
        </div>
      ) : (
        <div className="space-y-1">
          <label
            htmlFor="marking-interval-frames"
            className="text-sm font-medium text-gray-700"
          >
            프레임 간격 <span className="text-danger">*</span>
          </label>
          <input
            id="marking-interval-frames"
            type="text"
            inputMode="numeric"
            value={intervalInput}
            onChange={(e) => {
              setIntervalInput(e.target.value);
              setError(null);
            }}
            disabled={isPending}
            placeholder="예: 300 (프레임마다)"
            className={cn(
              'w-full rounded-md border border-gray-300 px-3 py-2 text-sm disabled:bg-gray-50 disabled:text-gray-400',
              KRDS_FOCUS,
            )}
          />
          {error && (
            <p className="flex items-center gap-1 text-xs text-danger" role="alert">
              <AlertCircle className="h-3.5 w-3.5 shrink-0" aria-hidden="true" />
              {error}
            </p>
          )}
        </div>
      )}
    </Modal>
  );
}
