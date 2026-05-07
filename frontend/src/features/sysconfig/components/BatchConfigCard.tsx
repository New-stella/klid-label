import { zodResolver } from '@hookform/resolvers/zod';
import { useEffect } from 'react';
import { useForm } from 'react-hook-form';

import { Button } from '@/components/common/Button';
import { Card } from '@/components/common/Card';
import { Input } from '@/components/common/Input';
import { useUiStore } from '@/stores/useUiStore';

import { useUpdateConfig } from '../hooks/useUpdateConfig';
import { batchConfigSchema, type BatchConfigForm } from '../schemas';
import type { ConfigMap } from '../types';

interface Props {
  configs: ConfigMap;
}

/**
 * UI/UX §4-16 ① 배치 설정 카드 (FFmpeg와 독립 저장).
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
    formState: { errors },
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

  const onSubmit = (values: BatchConfigForm) => {
    mutate({ key: 'BATCH_INTERVAL_SEC', value: values.BATCH_INTERVAL_SEC });
    mutate({ key: 'BATCH_CONCURRENCY', value: values.BATCH_CONCURRENCY });
  };

  return (
    <Card title="배치 설정">
      <form onSubmit={handleSubmit(onSubmit)} className="flex flex-col gap-4" noValidate>
        <Input
          type="number"
          label="BATCH_INTERVAL_SEC (10~3600)"
          min={10}
          max={3600}
          step={1}
          error={errors.BATCH_INTERVAL_SEC?.message}
          {...register('BATCH_INTERVAL_SEC', { valueAsNumber: true })}
        />
        <Input
          type="number"
          label="BATCH_CONCURRENCY (1~10)"
          min={1}
          max={10}
          step={1}
          error={errors.BATCH_CONCURRENCY?.message}
          {...register('BATCH_CONCURRENCY', { valueAsNumber: true })}
        />
        <div className="flex justify-end">
          <Button type="submit" variant="primary" loading={isPending} disabled={isPending}>
            저장
          </Button>
        </div>
      </form>
    </Card>
  );
}
