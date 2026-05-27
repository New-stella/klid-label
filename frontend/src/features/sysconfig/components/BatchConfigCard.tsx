import { zodResolver } from '@hookform/resolvers/zod';
import { Save } from 'lucide-react';
import { useEffect } from 'react';
import { useForm } from 'react-hook-form';

import { Button } from '@/components/common/Button';
import { useUiStore } from '@/stores/useUiStore';

import { useUpdateConfig } from '../hooks/useUpdateConfig';
import { batchConfigSchema, type BatchConfigForm } from '../schemas';
import type { ConfigMap } from '../types';

interface Props {
  configs: ConfigMap;
}

/**
 * UI/UX §4-16 ① 배치 처리 카드 (독립 저장).
 *
 * 보안: zod 스키마로 입력 범위 검증.
 */
export function BatchConfigCard({ configs }: Props) {
  const pushToast = useUiStore((s) => s.pushToast);
  const { mutate, isPending } = useUpdateConfig({
    onSuccess: () => pushToast({ variant: 'success', message: '배치 설정 저장됨' }),
    onError: () => pushToast({ variant: 'error', message: '저장에 실패했습니다' }),
  });

  const {
    register,
    handleSubmit,
    watch,
    formState: { isDirty },
    reset,
  } = useForm<BatchConfigForm>({
    resolver: zodResolver(batchConfigSchema),
    defaultValues: {
      BATCH_INTERVAL_SEC: configs.BATCH_INTERVAL_SEC ?? 60,
      BATCH_CONCURRENCY: configs.BATCH_CONCURRENCY ?? 1,
    },
  });

  useEffect(() => {
    reset({
      BATCH_INTERVAL_SEC: configs.BATCH_INTERVAL_SEC ?? 60,
      BATCH_CONCURRENCY: configs.BATCH_CONCURRENCY ?? 1,
    });
  }, [configs.BATCH_INTERVAL_SEC, configs.BATCH_CONCURRENCY, reset]);

  const batchInterval = watch('BATCH_INTERVAL_SEC');
  const concurrency = watch('BATCH_CONCURRENCY');

  const onSubmit = (values: BatchConfigForm) => {
    mutate({ key: 'BATCH_INTERVAL_SEC', value: values.BATCH_INTERVAL_SEC });
    mutate({ key: 'BATCH_CONCURRENCY', value: values.BATCH_CONCURRENCY });
  };

  return (
    <form onSubmit={handleSubmit(onSubmit)} noValidate>
      <div className="bg-white border border-gray-200 rounded-lg p-5 space-y-5">
        <div className="flex items-center justify-between border-b border-gray-100 pb-3">
          <h3 className="text-sm font-semibold text-gray-700">배치 처리</h3>
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

        {/* 처리 주기 */}
        <div className="space-y-2">
          <label className="flex items-center justify-between text-sm" htmlFor="batch-interval">
            <span className="font-medium text-gray-700">처리 주기 (초)</span>
            <span className="text-primary-600 font-semibold tabular-nums">{batchInterval}s</span>
          </label>
          <input
            id="batch-interval"
            type="range"
            min={10}
            max={300}
            step={10}
            className="w-full h-2 bg-gray-200 rounded-full appearance-none cursor-pointer accent-primary-600"
            {...register('BATCH_INTERVAL_SEC', { valueAsNumber: true })}
          />
          <div className="flex justify-between text-xs text-gray-400">
            <span>10s</span>
            <span>300s</span>
          </div>
        </div>

        {/* 동시 처리 수 */}
        <div className="space-y-2">
          <label className="flex items-center justify-between text-sm" htmlFor="concurrent-jobs">
            <span className="font-medium text-gray-700">동시 처리 수</span>
            <span className="text-primary-600 font-semibold tabular-nums">{concurrency}</span>
          </label>
          <input
            id="concurrent-jobs"
            type="range"
            min={1}
            max={8}
            step={1}
            className="w-full h-2 bg-gray-200 rounded-full appearance-none cursor-pointer accent-primary-600"
            {...register('BATCH_CONCURRENCY', { valueAsNumber: true })}
          />
          <div className="flex justify-between text-xs text-gray-400">
            <span>1</span>
            <span>8</span>
          </div>
        </div>
      </div>
    </form>
  );
}
