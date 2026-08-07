// DeidentReportButton — 비식별 누락 신고 UI (마킹 화면 · 라벨링 화면 공용).
//
// 사용 시나리오:
//   ① 라벨링 화면 — 작업자가 프레임을 보다가 얼굴/번호판 등 비식별 누락을 발견하면 srcSn 으로 신고.
//   ② 마킹 화면   — 영상을 재생하다 같은 것을 발견하면 rawSn 으로 신고(프레임 컨텍스트가 없다).
//   두 경우 모두 BE 가 영상을 잠그고 비식별 재처리 흐름으로 넘긴다. 서버는 어느 화면에서 신고했는지를
//   함께 저장해, 재처리가 끝난 뒤 <다시 시작하는 지점>을 다르게 잡는다
//   (마킹 화면 → 마킹부터 다시 / 라벨링 화면 → 프레임 이미지만 다시 만들고 라벨링 계속).
//
// ⚠ 화면별로 컴포넌트를 복제하지 않는다 — 이 저장소는 복제 후 한쪽만 갱신돼 값이 어긋난 사고 이력이 있다.
//
// 보안:
//  - reason 입력은 zod 로 길이 검증 (1~1000자)
//  - 사용자 입력은 textarea 에만 사용 — dangerouslySetInnerHTML 등 XSS 위험 표현 금지
//  - srcSn/rawSn 은 number — axios path 자동 인코딩 + BE 가 권한/IDOR 검증

import { zodResolver } from '@hookform/resolvers/zod';
import { AlertTriangle } from 'lucide-react';
import { useState } from 'react';
import { useForm } from 'react-hook-form';
import { z } from 'zod';

import { Field, FieldError, FieldLabel } from '@/components/common/Field';
import { Button } from '@/components/common/Button';
import { Modal } from '@/components/common/Modal';
import { Textarea } from '@/components/common/Textarea';
import { resolveApiMessage } from '@/lib/api/resolveApiMessage';
import { isEditBlockedNow, useLabelStore } from '@/stores/useLabelStore';
import { useUiStore } from '@/stores/useUiStore';

import { reportDeidentMiss, reportDeidentMissByVideo } from '../api';
import { busyRejectedMessage } from '../hooks/useBusyTask';

const schema = z.object({
  reason: z
    .string()
    .min(1, '신고 사유를 입력하세요')
    .max(1000, '신고 사유는 1000자 이내로 입력하세요'),
});
type FormValues = z.infer<typeof schema>;

export interface DeidentReportButtonProps {
  /**
   * 라벨링 단계 — 현재 프레임의 srcSn (LS_DATA_SRC.SRC_SN).
   *
   * {@link DeidentReportButtonProps.rawSn} 과 <b>둘 중 하나만</b> 지정한다. 지정된 쪽에 따라 신고
   * 진입점이 갈리고, 서버가 신고 단계를 기록해 재처리 완료 후 <b>다시 시작하는 지점</b>이 달라진다.
   */
  srcSn?: number;
  /**
   * 마킹 단계 — 현재 영상의 rawSn (LS_DATA_RAW.RAW_SN).
   *
   * 마킹 화면은 영상을 재생할 뿐 프레임 단위 컨텍스트가 없으므로 영상 단위로 신고한다.
   * 컴포넌트를 복제하지 않고 이 컴포넌트를 그대로 재사용한다 — 복제하면 한쪽만 갱신되는 사고가 난다.
   */
  rawSn?: number;
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
  rawSn,
  disabled = false,
  unsupportedReason,
  onSuccess,
}: DeidentReportButtonProps) {
  // 마킹 단계(영상 단위)인가 — srcSn 이 없고 rawSn 만 있는 경우.
  const isVideoScope = srcSn === undefined && rawSn !== undefined;
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
    // 마킹 단계(영상 단위)는 라벨 편집 자체가 없어 이 판정 대상이 아니다.
    if (!isVideoScope && srcSn !== undefined && isEditBlockedNow(srcSn)) {
      setServerError(busyRejectedMessage(useLabelStore.getState().busy?.kind ?? null));
      return;
    }
    setSubmitting(true);
    setServerError(null);
    try {
      if (isVideoScope) {
        await reportDeidentMissByVideo(rawSn as number, values.reason);
      } else {
        await reportDeidentMiss(srcSn as number, values.reason);
      }
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
        setServerError(resolveApiMessage(e, '현재 상태에서는 비식별 누락 신고를 할 수 없습니다.'));
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
        className="flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-caption text-white bg-warning hover:bg-warning/90 disabled:opacity-50 disabled:cursor-not-allowed transition-colors"
      >
        <AlertTriangle size={14} aria-hidden="true" />
        비식별 누락 신고
      </button>

      <Modal
        open={open}
        onClose={closeModal}
        title="비식별 누락 신고"
        description={
          isVideoScope
            ? '재생 중인 영상에서 얼굴/번호판 등 비식별이 누락된 부분을 발견했다면 사유를 적어 신고해주세요. 신고 시 영상이 잠기고 비식별 재처리가 시작되며, 재처리가 끝나면 마킹부터 다시 진행합니다.'
            : '현재 프레임에서 얼굴/번호판 등 비식별이 누락된 영역을 발견했다면 사유를 적어 신고해주세요. 신고 시 영상이 잠기고 비식별 재처리가 시작되며, 재처리가 끝나면 기존 마킹과 라벨을 유지한 채 이어서 작업합니다.'
        }
        size="md"
      >
        <form
          onSubmit={handleSubmit(onSubmit)}
          className="flex flex-col gap-3"
          noValidate
          data-testid="deident-report-form"
        >
          <Field>
            <FieldLabel>신고 사유</FieldLabel>
            <Textarea
              className="min-h-[154px]"
              placeholder={
                isVideoScope
                  ? '예: 00:12 부근 오른쪽 보행자 얼굴 블러 처리 누락'
                  : '예: 오른쪽 보행자 얼굴 블러 처리 누락'
              }
              aria-required="true"
              disabled={submitting}
              {...register('reason')}
            />
            <FieldError>{errors.reason?.message}</FieldError>
          </Field>
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
