import { zodResolver } from '@hookform/resolvers/zod';
import { Save } from 'lucide-react';
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
    onSuccess: () => pushToast({ variant: 'success', message: 'YOLO 설정 저장됨' }),
    onError: () => pushToast({ variant: 'error', message: '저장에 실패했습니다' }),
  });

  const {
    register,
    handleSubmit,
    watch,
    formState: { isDirty, errors },
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

  const onSubmit = (values: YoloConfigForm) => {
    mutate({ key: 'YOLO_CONF_THRESHOLD', value: values.YOLO_CONF_THRESHOLD });
    mutate({ key: 'YOLO_IMGSZ', value: values.YOLO_IMGSZ });
    mutate({ key: 'YOLO_IOU', value: values.YOLO_IOU });
  };

  return (
    <form onSubmit={handleSubmit(onSubmit)} noValidate>
      <div className="bg-white border border-gray-200 rounded-lg p-5 space-y-5">
        <div className="flex items-center justify-between border-b border-gray-100 pb-3">
          <h3 className="text-sm font-semibold text-gray-700">YOLO 추론 파라미터</h3>
          <Button
            type="submit"
            variant="primary"
            size="sm"
            leftIcon={Save}
            loading={isPending}
            disabled={!isDirty || isPending}
          >
            저장
          </Button>
        </div>

        {/* Confidence Threshold (25~80 → 0.25~0.80) */}
        <div className="space-y-2">
          <label className="flex items-center justify-between text-sm" htmlFor="yolo-conf">
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
          <div className="flex justify-between text-xs text-gray-400">
            <span>0.25</span>
            <span>0.80</span>
          </div>
          {errors.YOLO_CONF_THRESHOLD && (
            <p className="text-xs text-red-500" role="alert">
              {errors.YOLO_CONF_THRESHOLD.message}
            </p>
          )}
        </div>

        {/* Image Size (320~1920, step 32) */}
        <div className="space-y-2">
          <label className="flex items-center justify-between text-sm" htmlFor="yolo-imgsz">
            <span className="font-medium text-gray-700">이미지 크기 (imgsz)</span>
            <span className="text-primary-600 font-semibold tabular-nums">{imgsz}px</span>
          </label>
          <input
            id="yolo-imgsz"
            type="number"
            min={320}
            max={1920}
            step={32}
            aria-invalid={errors.YOLO_IMGSZ ? 'true' : 'false'}
            className="w-full text-sm border border-gray-300 rounded-lg px-3 py-2 bg-white focus:outline-none focus:ring-2 focus:ring-primary-500"
            {...register('YOLO_IMGSZ', { valueAsNumber: true })}
          />
          <p className="text-xs text-gray-400">320 ~ 1920, 32 의 배수 (ultralytics 권장)</p>
          {errors.YOLO_IMGSZ && (
            <p className="text-xs text-red-500" role="alert">
              {errors.YOLO_IMGSZ.message}
            </p>
          )}
        </div>

        {/* IoU Threshold (25~80 → 0.25~0.80) */}
        <div className="space-y-2">
          <label className="flex items-center justify-between text-sm" htmlFor="yolo-iou">
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
          <div className="flex justify-between text-xs text-gray-400">
            <span>0.25</span>
            <span>0.80</span>
          </div>
          {errors.YOLO_IOU && (
            <p className="text-xs text-red-500" role="alert">
              {errors.YOLO_IOU.message}
            </p>
          )}
        </div>
      </div>
    </form>
  );
}
