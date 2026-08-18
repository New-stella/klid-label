import { zodResolver } from '@hookform/resolvers/zod';
import { AlertCircle } from 'lucide-react';
import { useEffect } from 'react';
import { useForm } from 'react-hook-form';

import { Button } from '@/components/common/Button';
import { useUiStore } from '@/stores/useUiStore';

import { useUpdateConfig } from '../hooks/useUpdateConfig';
import { yoloConfigSchema, type YoloConfigForm } from '../schemas';
import type { ConfigMap } from '../types';

interface Props {
  configs: ConfigMap;
}

/**
 * Phase 1/5: YOLO 추론 파라미터 카드 (BE ConfigKeys YOLO_CONF_THRESHOLD/YOLO_IOU 와 1:1).
 *
 * - confThreshold: slider 25~80 → BE 가 /100 (0.25~0.80)
 * - iou         : slider 25~80 → BE 가 /100
 *
 * ⚠ 구 항목 「이미지 크기(imgsz)」는 폐지됐다(사양 SCREEN-025 · UC-031) — 추론 서버가 입력 크기를
 *   640 으로 고정해 쓰므로 화면에서 바꿔도 결과가 달라지지 않았다. 조정되는 척하는 입력칸이라
 *   정밀도가 오르지 않을 때 운영자가 원인을 이 값에서 찾게 만들었다. 되살리려면 추론 서버가
 *   요청값을 실제로 쓰도록 먼저 고칠 것. (BE 가드: ConfigKeysTest.imgszKeyIsNotConfigurable)
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
    formState: { isDirty, errors, dirtyFields },
    reset,
  } = useForm<YoloConfigForm>({
    resolver: zodResolver(yoloConfigSchema),
    defaultValues: {
      YOLO_CONF_THRESHOLD: configs.YOLO_CONF_THRESHOLD ?? 40,
      YOLO_IOU: configs.YOLO_IOU ?? 50,
    },
  });

  // 서버 값 도착 시 폼 동기화
  useEffect(() => {
    reset({
      YOLO_CONF_THRESHOLD: configs.YOLO_CONF_THRESHOLD ?? 40,
      YOLO_IOU: configs.YOLO_IOU ?? 50,
    });
  }, [configs.YOLO_CONF_THRESHOLD, configs.YOLO_IOU, reset]);

  const conf = watch('YOLO_CONF_THRESHOLD');
  const iou = watch('YOLO_IOU');

  // 변경된 키만 전송한다 — BatchConfigCard 와 동일 원칙(§B9#60).
  const onSubmit = (values: YoloConfigForm) => {
    if (dirtyFields.YOLO_CONF_THRESHOLD) {
      mutate({ key: 'YOLO_CONF_THRESHOLD', value: values.YOLO_CONF_THRESHOLD });
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
