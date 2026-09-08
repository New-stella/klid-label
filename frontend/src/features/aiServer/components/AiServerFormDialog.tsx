import { zodResolver } from '@hookform/resolvers/zod';
import { AlertCircle } from 'lucide-react';
import { useEffect } from 'react';
import { useForm } from 'react-hook-form';

import { Button } from '@/components/common/Button';
import { Modal } from '@/components/common/Modal';
import { KRDS_FOCUS } from '@/lib/focusRing';

import { aiServerCreateSchema, type AiServerCreateForm } from '../schemas';
import { AiSrvrType, AI_SRVR_TYPE_LABEL, type AiSrvr } from '../types';

const INPUT_CLASS = `w-full rounded-md border border-gray-300 px-3 py-2 text-body ${KRDS_FOCUS}`;

export interface AiServerFormDialogProps {
  open: boolean;
  /** `null` 이면 등록, 값이 있으면 그 장비 수정. */
  target: AiSrvr | null;
  /** 등록 시 미리 골라 둘 유형 — 지금 보고 있는 탭. */
  defaultType: AiSrvrType;
  onClose: () => void;
  onSubmit: (values: AiServerCreateForm) => void;
  isSubmitting: boolean;
  /** 서버가 준 거부 사유. 문구를 그대로 보여준다. */
  error?: string;
}

/**
 * 장비 등록·수정 다이얼로그. [@design SCREEN-042] [@design API-227] [@design API-228]
 *
 * <h3>수정에서는 이름과 주소만 열린다</h3>
 * 식별자는 원장의 열쇠이고, 유형이 바뀌면 그 장비를 고르던 축이 통째로 바뀌어 이미 배정된
 * 영상의 근거가 사라진다. 그래서 서버가 그 둘을 아예 받지 않으며, 화면도 <b>비활성 입력이 아니라
 * 읽기 전용 표시</b>로 둔다 — 고칠 수 있을 것처럼 보이는 칸을 남기지 않는다.
 *
 * <h3>주소를 미리 막지 않는다</h3>
 * 평문 http 와 사설 대역은 <b>정상 입력</b>이다. 화면 검증은 스킴·형식·길이까지만 보고, 예약
 * 대역 판정은 서버가 소유한다. 거부 문구에는 입력값이나 그 해석 결과를 되비추지 않는다.
 */
export function AiServerFormDialog({
  open,
  target,
  defaultType,
  onClose,
  onSubmit,
  isSubmitting,
  error,
}: AiServerFormDialogProps) {
  const editing = target !== null;

  const {
    register,
    handleSubmit,
    reset,
    formState: { errors },
  } = useForm<AiServerCreateForm>({
    resolver: zodResolver(aiServerCreateSchema),
    defaultValues: { srvrId: '', srvrNm: '', srvrAddr: '', srvrTypeCd: defaultType },
  });

  // 열릴 때마다 대상에 맞춰 되채운다 — 앞서 연 장비의 값이 남아 있으면 다른 장비를 고친다.
  useEffect(() => {
    if (!open) return;
    reset({
      srvrId: target?.srvrId ?? '',
      srvrNm: target?.srvrNm ?? '',
      srvrAddr: target?.srvrAddr ?? '',
      srvrTypeCd: target?.srvrTypeCd ?? defaultType,
    });
  }, [open, target, defaultType, reset]);

  return (
    <Modal
      open={open}
      onClose={onClose}
      size="md"
      title={editing ? 'AI 장비 수정' : 'AI 장비 등록'}
      description={
        editing
          ? '이름과 주소만 바꿀 수 있습니다. 식별자와 유형은 바꿀 수 없습니다.'
          : '등록한 장비는 가용 상태로 시작합니다.'
      }
    >
      <form
        id="ai-server-form"
        noValidate
        onSubmit={handleSubmit(onSubmit)}
        className="flex flex-col gap-4"
      >
        {editing ? (
          <div className="flex flex-col gap-1">
            <span className="text-label font-medium text-gray-700">장비 식별자</span>
            <span className="font-mono text-mono text-gray-800" data-testid="ai-server-form-id">
              {target.srvrId}
            </span>
            <span className="text-caption text-gray-600">
              유형 {AI_SRVR_TYPE_LABEL[target.srvrTypeCd]} · 바꿀 수 없습니다.
            </span>
          </div>
        ) : (
          <>
            <div className="flex flex-col gap-1">
              <label className="text-label font-medium text-gray-700" htmlFor="ai-server-srvrId">
                장비 식별자
              </label>
              <input
                id="ai-server-srvrId"
                className={INPUT_CLASS}
                autoComplete="off"
                aria-invalid={errors.srvrId ? 'true' : 'false'}
                {...register('srvrId')}
              />
              <p className="text-caption text-gray-600">
                소문자·숫자·하이픈(-)·밑줄(_)만 20자 이내입니다. 등록 뒤에는 바꿀 수 없습니다.
              </p>
              {errors.srvrId && (
                <p className="flex items-center gap-1 text-caption text-danger" role="alert">
                  <AlertCircle className="h-3.5 w-3.5 shrink-0" aria-hidden="true" />
                  {errors.srvrId.message}
                </p>
              )}
            </div>

            <fieldset className="flex flex-col gap-1">
              <legend className="text-label font-medium text-gray-700">장비 유형</legend>
              <div className="flex flex-wrap gap-4 pt-1">
                {[AiSrvrType.INFERENCE, AiSrvrType.TIMESERIES].map((type) => (
                  <label
                    key={type}
                    className="flex items-center gap-2 text-body text-gray-800"
                    htmlFor={`ai-server-type-${type}`}
                  >
                    <input
                      id={`ai-server-type-${type}`}
                      type="radio"
                      value={type}
                      className={KRDS_FOCUS}
                      {...register('srvrTypeCd')}
                    />
                    {AI_SRVR_TYPE_LABEL[type]}
                  </label>
                ))}
              </div>
              <p className="text-caption text-gray-600">
                유형은 등록 뒤에 바꿀 수 없습니다. 이미 배정된 영상의 근거가 사라지기 때문입니다.
              </p>
            </fieldset>
          </>
        )}

        <div className="flex flex-col gap-1">
          <label className="text-label font-medium text-gray-700" htmlFor="ai-server-srvrNm">
            이름
          </label>
          <input
            id="ai-server-srvrNm"
            className={INPUT_CLASS}
            autoComplete="off"
            aria-invalid={errors.srvrNm ? 'true' : 'false'}
            {...register('srvrNm')}
          />
          <p className="text-caption text-gray-600">
            사람이 읽는 이름입니다(실제 장비 호스트명 등). 모르면 비워 둡니다.
          </p>
          {errors.srvrNm && (
            <p className="flex items-center gap-1 text-caption text-danger" role="alert">
              <AlertCircle className="h-3.5 w-3.5 shrink-0" aria-hidden="true" />
              {errors.srvrNm.message}
            </p>
          )}
        </div>

        <div className="flex flex-col gap-1">
          <label className="text-label font-medium text-gray-700" htmlFor="ai-server-srvrAddr">
            주소
          </label>
          <input
            id="ai-server-srvrAddr"
            type="url"
            inputMode="url"
            className={INPUT_CLASS}
            autoComplete="off"
            aria-invalid={errors.srvrAddr ? 'true' : 'false'}
            {...register('srvrAddr')}
          />
          {/*
            평문 http·사설 대역이 «정상»이라는 사실을 화면에서 분명히 말한다 — 적어 두지 않으면
            운영자가 https 로 고쳐 적다가 닿지 않는 주소를 넣는다(이 장비들은 내부망에 있다).
          */}
          <p className="text-caption text-gray-600">
            호출 기준 주소입니다. 내부망 주소와 http 주소를 그대로 쓸 수 있습니다.
          </p>
          {errors.srvrAddr && (
            <p className="flex items-center gap-1 text-caption text-danger" role="alert">
              <AlertCircle className="h-3.5 w-3.5 shrink-0" aria-hidden="true" />
              {errors.srvrAddr.message}
            </p>
          )}
        </div>

        {error && (
          <p
            className="flex items-center gap-1 text-caption text-danger"
            role="alert"
            data-testid="ai-server-form-error"
          >
            <AlertCircle className="h-3.5 w-3.5 shrink-0" aria-hidden="true" />
            {error}
          </p>
        )}

        <div className="flex justify-end gap-2 pt-2">
          <Button type="button" variant="secondary" onClick={onClose}>
            취소
          </Button>
          <Button type="submit" variant="primary" loading={isSubmitting}>
            {editing ? '저장' : '등록'}
          </Button>
        </div>
      </form>
    </Modal>
  );
}
