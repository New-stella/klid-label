// DeidentReportButton — 라벨러 비식별 누락 신고 UI (Phase 3).
//
// 사용 시나리오:
//   라벨러가 프레임을 보다가 얼굴/번호판 등 비식별 누락을 발견하면 본 버튼으로 신고.
//   BE 는 신고를 접수하고 영상을 'LOCKED_FOR_REDEIDENT' 상태로 잠가 라벨 수정을 막은 뒤
//   배치 파이프라인이 비식별 재처리를 수행한다.
//
// 보안:
//  - reason 입력은 zod 로 길이 검증 (1~1000자)
//  - 사용자 입력은 textarea 에만 사용 — dangerouslySetInnerHTML 등 XSS 위험 표현 금지
//  - srcSn 은 number — axios path 자동 인코딩 + BE 가 권한/IDOR 검증

import { zodResolver } from '@hookform/resolvers/zod';
import { AlertTriangle } from 'lucide-react';
import { useState } from 'react';
import { useForm } from 'react-hook-form';
import { z } from 'zod';

import { Button } from '@/components/common/Button';
import { Modal } from '@/components/common/Modal';
import { Textarea } from '@/components/common/Textarea';
import { resolveApiMessage } from '@/lib/api/resolveApiMessage';
import { isEditBlockedNow, useLabelStore } from '@/stores/useLabelStore';
import { useUiStore } from '@/stores/useUiStore';

import { reportDeidentMiss } from '../api';
import { busyRejectedMessage } from '../hooks/useBusyTask';

const schema = z.object({
  reason: z
    .string()
    .min(1, '신고 사유를 입력하세요')
    .max(1000, '신고 사유는 1000자 이내로 입력하세요'),
});
type FormValues = z.infer<typeof schema>;

export interface DeidentReportButtonProps {
  /** 현재 프레임의 srcSn (LS_DATA_SRC.SRC_SN). */
  srcSn: number;
  /** 잠금/RAW 보기/포털 모드 등에서 비활성화 시 true */
  disabled?: boolean;
  /**
   * 이 영상에서는 신고 자체가 불가능할 때의 <b>사유</b>(예: 파생영상). 지정하면 버튼을 비활성화하고
   * 툴팁(title)으로 사유를 보여준다.
   *
   * 왜 필요한가: 파생영상은 BE 가 412 로 거부하는데, 그 사실을 제출 시점에야 알리면 사용자는 사유를
   * 다 적은 뒤에야 "안 된다"를 보게 된다. 알 수 있는 시점에 미리 막는다(취소 버튼 옆 안내와 같은
   * 기존 관례 — title 툴팁 사용).
   */
  unsupportedReason?: string;
  /** 신고 성공 후 콜백 (예: 라벨 목록 invalidate, 페이지 잠금 state 마킹). */
  onSuccess?: () => void;
}

interface ApiErrorLike {
  response?: { status?: number };
  status?: number;
}

function statusOf(e: unknown): number | undefined {
  if (e && typeof e === 'object') {
    const a = e as ApiErrorLike;
    return a.status ?? a.response?.status;
  }
  return undefined;
}

export function DeidentReportButton({
  srcSn,
  disabled = false,
  unsupportedReason,
  onSuccess,
}: DeidentReportButtonProps) {
  const [open, setOpen] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [serverError, setServerError] = useState<string | null>(null);
  const pushToast = useUiStore((s) => s.pushToast);

  const {
    register,
    handleSubmit,
    reset,
    formState: { errors, isValid },
  } = useForm<FormValues>({
    resolver: zodResolver(schema),
    mode: 'onChange',
    defaultValues: { reason: '' },
  });

  const closeModal = () => {
    if (submitting) return;
    setOpen(false);
    setServerError(null);
    reset({ reason: '' });
  };

  const onSubmit = async (values: FormValues) => {
    // 이중 방어 — 버튼(disabled)은 "모달을 여는 시점"만 막는다. 모달을 먼저 연 뒤 저장/AI 가
    // 시작되면 제출이 그대로 성공하고, 성공 후처리(라벨 스토어 reset)가 진행 중 작업을 조용히
    // 취소한다. 발사 직전 실시간 재판정으로 막는다(fail-closed).
    if (isEditBlockedNow(srcSn)) {
      setServerError(busyRejectedMessage(useLabelStore.getState().busy?.kind ?? null));
      return;
    }
    setSubmitting(true);
    setServerError(null);
    try {
      await reportDeidentMiss(srcSn, values.reason);
      pushToast({
        variant: 'success',
        message: '비식별 누락 신고가 접수되었습니다. 재처리가 완료될 때까지 잠시 기다려주세요.',
      });
      reset({ reason: '' });
      setOpen(false);
      onSuccess?.();
    } catch (e) {
      const status = statusOf(e);
      if (status === 412) {
        // 파생영상(증강·해상도 변환본)처럼 이 영상에서는 신고를 받지 않는 경우 — 서버 안내문이
        // "왜 안 되는지"를 담고 있으므로 그대로 노출한다(일반 문구로 덮으면 아무 반응 없이 실패하는
        // 것과 같다). 파생영상은 위 unsupportedReason 으로 버튼 단계에서 이미 막히며, 이 분기는
        // 화면이 파생 여부를 모르는 경우(구 응답 등)의 안전망이다.
        setServerError(
          resolveApiMessage(e, '현재 상태에서는 비식별 누락 신고를 할 수 없습니다.'),
        );
      } else if (status === 409) {
        setServerError('이미 비식별 재처리 중인 영상입니다.');
      } else if (status === 403) {
        setServerError('본인에게 배정된 영상이 아닙니다.');
      } else if (status === 404) {
        setServerError('영상을 찾을 수 없습니다.');
      } else {
        setServerError('신고 처리 중 오류가 발생했습니다. 잠시 후 다시 시도해주세요.');
      }
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <>
      <button
        type="button"
        onClick={() => setOpen(true)}
        disabled={disabled || Boolean(unsupportedReason)}
        title={unsupportedReason}
        aria-label={
          unsupportedReason ? `비식별 누락 신고 — ${unsupportedReason}` : '비식별 누락 신고'
        }
        data-testid="deident-report-button"
        className="flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-xs text-white bg-warning hover:bg-warning/90 disabled:opacity-50 disabled:cursor-not-allowed transition-colors"
      >
        <AlertTriangle size={14} aria-hidden="true" />
        비식별 누락 신고
      </button>

      <Modal
        open={open}
        onClose={closeModal}
        title="비식별 누락 신고"
        description="현재 프레임에서 얼굴/번호판 등 비식별이 누락된 영역을 발견했다면 사유를 적어 신고해주세요. 신고 시 영상이 잠기고 비식별 재처리가 시작됩니다."
        size="md"
      >
        <form
          onSubmit={handleSubmit(onSubmit)}
          className="flex flex-col gap-3"
          noValidate
          data-testid="deident-report-form"
        >
          <Textarea
            label="신고 사유"
            rows={5}
            placeholder="예: 오른쪽 보행자 얼굴 블러 처리 누락"
            error={errors.reason?.message}
            aria-required="true"
            disabled={submitting}
            {...register('reason')}
          />
          {serverError && (
            <p
              role="alert"
              className="text-sub text-danger"
              data-testid="deident-report-server-error"
            >
              {serverError}
            </p>
          )}
          <div className="flex justify-end gap-2">
            <Button
              variant="outline"
              onClick={closeModal}
              disabled={submitting}
              data-testid="deident-report-cancel"
            >
              취소
            </Button>
            <Button
              type="submit"
              variant="danger"
              disabled={!isValid || submitting}
              loading={submitting}
              data-testid="deident-report-submit"
            >
              신고하기
            </Button>
          </div>
        </form>
      </Modal>
    </>
  );
}
