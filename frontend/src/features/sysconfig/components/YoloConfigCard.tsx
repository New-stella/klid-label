import { zodResolver } from '@hookform/resolvers/zod';
import { AlertCircle } from 'lucide-react';
import { useEffect, type FocusEvent } from 'react';
import { useForm } from 'react-hook-form';

import { Button } from '@/components/common/Button';
import { KRDS_FOCUS } from '@/lib/focusRing';
import { useUiStore } from '@/stores/useUiStore';

import { useUpdateConfig } from '../hooks/useUpdateConfig';
import { yoloConfigSchema, type YoloConfigForm } from '../schemas';
import type { ConfigMap } from '../types';

interface Props {
  configs: ConfigMap;
}

const IMGSZ_MIN = 320;
const IMGSZ_MAX = 1920;
const IMGSZ_STEP = 32;

/** 가까운 32의 배수로 조용히 보정한다(사양 SCREEN-025) — 범위 밖 값은 먼저 클램프한다. */
function snapImgsz(raw: number): number {
  if (!Number.isFinite(raw)) return IMGSZ_MIN;
  const clamped = Math.min(IMGSZ_MAX, Math.max(IMGSZ_MIN, raw));
  return Math.round(clamped / IMGSZ_STEP) * IMGSZ_STEP;
}

/**
 * Phase 1/5: YOLO 추론 파라미터 카드 (BE ConfigKeys YOLO_CONF_THRESHOLD/YOLO_IMGSZ/YOLO_IOU 와 1:1).
 *
 * - confThreshold: slider 25~80 → BE 가 /100 (0.25~0.80)
 * - imgsz       : number 320~1920, step 32 (ultralytics 권장 32 배수)
 * - iou         : slider 25~80 → BE 가 /100
 *
 * 보안: zod 스키마로 입력 범위 검증 (Critical) → BE 전송 전 1차 차단. BE 도 재검증 (이중 방어).
 */
export function YoloConfigCard({ configs }: Props) {
  const pushToast = useUiStore((s) => s.pushToast);
  const { mutate, isPending } = useUpdateConfig({
    onSuccess: () => pushToast({ variant: 'success', message: 'AI 탐지 설정 저장됨' }),
    onError: () => pushToast({ variant: 'error', message: '저장에 실패했습니다' }),
  });

  const {
    register,
    handleSubmit,
    watch,
    setValue,
    formState: { isDirty, errors, dirtyFields },
    reset,
  } = useForm<YoloConfigForm>({
    resolver: zodResolver(yoloConfigSchema),
    defaultValues: {
      YOLO_CONF_THRESHOLD: configs.YOLO_CONF_THRESHOLD ?? 40,
      YOLO_IMGSZ: configs.YOLO_IMGSZ ?? 1280,
      YOLO_IOU: configs.YOLO_IOU ?? 50,
    },
  });

  // 서버 값 도착 시 폼 동기화
  useEffect(() => {
    reset({
      YOLO_CONF_THRESHOLD: configs.YOLO_CONF_THRESHOLD ?? 40,
      YOLO_IMGSZ: configs.YOLO_IMGSZ ?? 1280,
      YOLO_IOU: configs.YOLO_IOU ?? 50,
    });
  }, [configs.YOLO_CONF_THRESHOLD, configs.YOLO_IMGSZ, configs.YOLO_IOU, reset]);

  const conf = watch('YOLO_CONF_THRESHOLD');
  const imgsz = watch('YOLO_IMGSZ');
  const iou = watch('YOLO_IOU');

  const imgszField = register('YOLO_IMGSZ', { valueAsNumber: true });
  const handleImgszBlur = (e: FocusEvent<HTMLInputElement>) => {
    imgszField.onBlur(e);
    const raw = Number(e.target.value);
    const snapped = snapImgsz(raw);
    if (Number.isFinite(raw) && snapped !== raw) {
      setValue('YOLO_IMGSZ', snapped, { shouldDirty: true, shouldValidate: true });
    }
  };

  // 변경된 키만 전송한다 — BatchConfigCard 와 동일 원칙(§B9#60).
  const onSubmit = (values: YoloConfigForm) => {
    if (dirtyFields.YOLO_CONF_THRESHOLD) {
      mutate({ key: 'YOLO_CONF_THRESHOLD', value: values.YOLO_CONF_THRESHOLD });
    }
    if (dirtyFields.YOLO_IMGSZ) {
      mutate({ key: 'YOLO_IMGSZ', value: values.YOLO_IMGSZ });
    }
    if (dirtyFields.YOLO_IOU) {
      mutate({ key: 'YOLO_IOU', value: values.YOLO_IOU });
    }
  };

  return (
    <form onSubmit={handleSubmit(onSubmit)} noValidate>
      <div className="bg-white border border-gray-200 rounded-lg p-5 space-y-5">
        <div className="flex items-center justify-between border-b border-gray-100 pb-3">
          <h3 className="text-title-sm font-semibold text-gray-700">AI 탐지 추론 파라미터</h3>
          <Button
            type="submit"
            variant="primary"
            size="sm"
            loading={isPending}
            disabled={!isDirty || isPending}
          >
            저장
          </Button>
        </div>

        {/* Confidence Threshold (25~80 → 0.25~0.80) */}
        <div className="space-y-2">
          <label className="flex items-center justify-between text-label" htmlFor="yolo-conf">
            <span className="font-medium text-gray-700">Confidence Threshold</span>
            <span className="text-primary-600 font-semibold tabular-nums">
              {(conf / 100).toFixed(2)}
            </span>
          </label>
          <input
            id="yolo-conf"
            type="range"
            min={25}
            max={80}
            step={1}
            aria-invalid={errors.YOLO_CONF_THRESHOLD ? 'true' : 'false'}
            className="w-full h-2 bg-gray-200 rounded-full appearance-none cursor-pointer accent-primary-600"
            {...register('YOLO_CONF_THRESHOLD', { valueAsNumber: true })}
          />
          <div className="flex justify-between text-caption text-gray-400">
            <span>0.25</span>
            <span>0.80</span>
          </div>
          <p className="text-caption text-gray-400">
            객체로 인식할 최소 확신도입니다. 높이면 확실한 객체만 잡아 오탐이 줄지만 놓침(미탐)이
            늘고, 낮추면 더 많이 잡지만 오탐이 늘어납니다. (0.25~0.80)
          </p>
          {errors.YOLO_CONF_THRESHOLD && (
            <p className="flex items-center gap-1 text-caption text-danger" role="alert">
              <AlertCircle className="h-3.5 w-3.5 shrink-0" aria-hidden="true" />
              {errors.YOLO_CONF_THRESHOLD.message}
            </p>
          )}
        </div>

        {/* Image Size (320~1920, step 32) */}
        <div className="space-y-2">
          <label className="flex items-center justify-between text-label" htmlFor="yolo-imgsz">
            <span className="font-medium text-gray-700">이미지 크기 (imgsz)</span>
            <span className="text-primary-600 font-semibold tabular-nums">{imgsz}px</span>
          </label>
          <input
            id="yolo-imgsz"
            type="number"
            min={IMGSZ_MIN}
            max={IMGSZ_MAX}
            step={IMGSZ_STEP}
            aria-invalid={errors.YOLO_IMGSZ ? 'true' : 'false'}
            className={`w-full text-body-md border border-gray-300 rounded-lg px-3 py-2 bg-white ${KRDS_FOCUS}`}
            {...imgszField}
            onBlur={handleImgszBlur}
          />
          <p className="text-caption text-gray-400">
            AI 탐지 모델에 입력하는 추론 해상도(px)입니다. 프레임이 이 크기로 리사이즈되어 추론되고
            결과 좌표는 원본 해상도로 환산됩니다. 크게 하면 작은 객체 탐지 정확도가 올라가지만 추론
            속도가 느려지고 GPU 메모리를 더 사용합니다. 탐지 모델 입력 규격상 32의 배수여야 하며,
            저장·내보내기 해상도와는 무관합니다. (320~1920px, 기본 1280)
          </p>
          {errors.YOLO_IMGSZ && (
            <p className="flex items-center gap-1 text-caption text-danger" role="alert">
              <AlertCircle className="h-3.5 w-3.5 shrink-0" aria-hidden="true" />
              {errors.YOLO_IMGSZ.message}
            </p>
          )}
        </div>

        {/* IoU Threshold (25~80 → 0.25~0.80) */}
        <div className="space-y-2">
          <label className="flex items-center justify-between text-label" htmlFor="yolo-iou">
            <span className="font-medium text-gray-700">IoU 임계값</span>
            <span className="text-primary-600 font-semibold tabular-nums">
              {(iou / 100).toFixed(2)}
            </span>
          </label>
          <input
            id="yolo-iou"
            type="range"
            min={25}
            max={80}
            step={1}
            aria-invalid={errors.YOLO_IOU ? 'true' : 'false'}
            className="w-full h-2 bg-gray-200 rounded-full appearance-none cursor-pointer accent-primary-600"
            {...register('YOLO_IOU', { valueAsNumber: true })}
          />
          <div className="flex justify-between text-caption text-gray-400">
            <span>0.25</span>
            <span>0.80</span>
          </div>
          <p className="text-caption text-gray-400">
            겹치는 박스를 중복으로 제거(NMS)하는 기준입니다. 낮추면 겹친 박스를 더 적극적으로 합쳐
            중복이 줄고, 높이면 인접한 객체를 더 많이 남깁니다. (0.25~0.80)
          </p>
          {errors.YOLO_IOU && (
            <p className="flex items-center gap-1 text-caption text-danger" role="alert">
              <AlertCircle className="h-3.5 w-3.5 shrink-0" aria-hidden="true" />
              {errors.YOLO_IOU.message}
            </p>
          )}
        </div>
      </div>
    </form>
  );
}
