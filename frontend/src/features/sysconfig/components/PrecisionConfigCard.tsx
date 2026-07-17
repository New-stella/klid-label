import { zodResolver } from '@hookform/resolvers/zod';
import { AlertCircle, Save } from 'lucide-react';
import { useEffect } from 'react';
import { useForm } from 'react-hook-form';

import { Button } from '@/components/common/Button';
import { useUiStore } from '@/stores/useUiStore';

import { useUpdateConfig } from '../hooks/useUpdateConfig';
import { precisionConfigSchema, type PrecisionConfigForm } from '../schemas';
import type { ConfigMap } from '../types';

interface Props {
  configs: ConfigMap;
}

const DEFAULT_SENSITIVITY = 40;
const DEFAULT_TOLERANCE = 1;

/**
 * FEAT-007 (SFR-08-03) 라벨링 정밀도 카드 — 작업자가 정밀도를 직접 조절.
 *
 * 두 컨트롤 모두 조절:
 *  ① 인식 민감도  : YOLO_CONF_THRESHOLD 25~80 → BE 가 /100 (0.25~0.80) — 슬라이더
 *  ② 경계 세밀함  : POLYGON_SIMPLIFY_TOLERANCE 0.0~50.0 (Douglas-Peucker epsilon px) — 슬라이더
 *
 * 정밀도 옵션은 시스템 설정(sysconfig)으로 관리 — 기존 useUpdateConfig 훅 재사용.
 * 보안: zod 스키마로 범위 검증(1차) → BE 재검증(2차). 접근성: label htmlFor 연결.
 */
export function PrecisionConfigCard({ configs }: Props) {
  const pushToast = useUiStore((s) => s.pushToast);
  const { mutate, isPending } = useUpdateConfig({
    onSuccess: () => pushToast({ variant: 'success', message: '정밀도 설정 저장됨' }),
    onError: () => pushToast({ variant: 'error', message: '저장에 실패했습니다' }),
  });

  const {
    register,
    handleSubmit,
    watch,
    formState: { isDirty, errors },
    reset,
  } = useForm<PrecisionConfigForm>({
    resolver: zodResolver(precisionConfigSchema),
    defaultValues: {
      YOLO_CONF_THRESHOLD: configs.YOLO_CONF_THRESHOLD ?? DEFAULT_SENSITIVITY,
      POLYGON_SIMPLIFY_TOLERANCE: configs.POLYGON_SIMPLIFY_TOLERANCE ?? DEFAULT_TOLERANCE,
    },
  });

  // 서버 값 도착 시 폼 동기화
  useEffect(() => {
    reset({
      YOLO_CONF_THRESHOLD: configs.YOLO_CONF_THRESHOLD ?? DEFAULT_SENSITIVITY,
      POLYGON_SIMPLIFY_TOLERANCE: configs.POLYGON_SIMPLIFY_TOLERANCE ?? DEFAULT_TOLERANCE,
    });
  }, [configs.YOLO_CONF_THRESHOLD, configs.POLYGON_SIMPLIFY_TOLERANCE, reset]);

  const sensitivity = watch('YOLO_CONF_THRESHOLD');
  const tolerance = watch('POLYGON_SIMPLIFY_TOLERANCE');

  const onSubmit = (values: PrecisionConfigForm) => {
    mutate({ key: 'YOLO_CONF_THRESHOLD', value: values.YOLO_CONF_THRESHOLD });
    mutate({ key: 'POLYGON_SIMPLIFY_TOLERANCE', value: values.POLYGON_SIMPLIFY_TOLERANCE });
  };

  return (
    <form onSubmit={handleSubmit(onSubmit)} noValidate>
      <div className="bg-white border border-gray-200 rounded-lg p-5 space-y-5">
        <div className="flex items-center justify-between border-b border-gray-100 pb-3">
          <h3 className="text-sm font-semibold text-gray-700">라벨링 정밀도</h3>
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

        {/* ① 인식 민감도 (YOLO_CONF_THRESHOLD 25~80 → 0.25~0.80) */}
        <div className="space-y-2">
          <label className="flex items-center justify-between text-sm" htmlFor="precision-sensitivity">
            <span className="font-medium text-gray-700">인식 민감도</span>
            <span className="text-primary-600 font-semibold tabular-nums">
              {(sensitivity / 100).toFixed(2)}
            </span>
          </label>
          <input
            id="precision-sensitivity"
            type="range"
            min={25}
            max={80}
            step={1}
            aria-invalid={errors.YOLO_CONF_THRESHOLD ? 'true' : 'false'}
            className="w-full h-2 bg-gray-200 rounded-full appearance-none cursor-pointer accent-primary-600"
            {...register('YOLO_CONF_THRESHOLD', { valueAsNumber: true })}
          />
          <div className="flex justify-between text-xs text-gray-400">
            <span>낮음 (0.25)</span>
            <span>높음 (0.80)</span>
          </div>
          <p className="text-xs text-gray-400">
            값이 높을수록 확신도가 높은 객체만 인식해 오탐이 줄지만 놓치는 객체가 늘 수 있습니다.
            (AI 탐지 추론의 Confidence Threshold 와 동일한 설정값입니다.)
          </p>
          {errors.YOLO_CONF_THRESHOLD && (
            <p className="flex items-center gap-1 text-xs text-danger" role="alert">
              <AlertCircle className="h-3.5 w-3.5 shrink-0" aria-hidden="true" />
              {errors.YOLO_CONF_THRESHOLD.message}
            </p>
          )}
        </div>

        {/* ② 경계 세밀함 (POLYGON_SIMPLIFY_TOLERANCE 0.0~50.0) */}
        <div className="space-y-2">
          <label className="flex items-center justify-between text-sm" htmlFor="precision-tolerance">
            <span className="font-medium text-gray-700">경계 세밀함</span>
            <span className="text-primary-600 font-semibold tabular-nums">
              {Number(tolerance).toFixed(1)}px
            </span>
          </label>
          <input
            id="precision-tolerance"
            type="range"
            min={0}
            max={50}
            step={0.5}
            aria-invalid={errors.POLYGON_SIMPLIFY_TOLERANCE ? 'true' : 'false'}
            className="w-full h-2 bg-gray-200 rounded-full appearance-none cursor-pointer accent-primary-600"
            {...register('POLYGON_SIMPLIFY_TOLERANCE', { valueAsNumber: true })}
          />
          <div className="flex justify-between text-xs text-gray-400">
            <span>세밀 (0.0)</span>
            <span>거침 (50.0)</span>
          </div>
          <p className="text-xs text-gray-400">
            값이 작을수록 폴리곤 경계가 원본에 가깝게 세밀해지고(점 수 증가), 클수록 경계가 단순해져
            점 수가 줄어듭니다. (0~50px)
          </p>
          {errors.POLYGON_SIMPLIFY_TOLERANCE && (
            <p className="flex items-center gap-1 text-xs text-danger" role="alert">
              <AlertCircle className="h-3.5 w-3.5 shrink-0" aria-hidden="true" />
              {errors.POLYGON_SIMPLIFY_TOLERANCE.message}
            </p>
          )}
        </div>
      </div>
    </form>
  );
}
