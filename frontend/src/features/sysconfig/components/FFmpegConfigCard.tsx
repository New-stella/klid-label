import { zodResolver } from '@hookform/resolvers/zod';
import { useEffect } from 'react';
import { useForm } from 'react-hook-form';

import { Button } from '@/components/common/Button';
import { Card } from '@/components/common/Card';
import { Input } from '@/components/common/Input';
import { useUiStore } from '@/stores/useUiStore';

import { useUpdateConfig } from '../hooks/useUpdateConfig';
import { ffmpegConfigSchema, type FFmpegConfigForm } from '../schemas';
import type { ConfigMap } from '../types';

interface Props {
  configs: ConfigMap;
}

/**
 * UI/UX §4-16 ① FFmpeg 설정 카드.
 *
 * 보안: zod 스키마로 입력 범위 검증 (Critical) → BE 전송 전 1차 차단.
 * 두 키(FFMPEG_THREADS, FFMPEG_OUTPUT_FPS) 동시 검증, 독립 카드 단위 저장.
 */
export function FFmpegConfigCard({ configs }: Props) {
  const pushToast = useUiStore((s) => s.pushToast);
  const { mutate, isPending } = useUpdateConfig({
    onSuccess: () => pushToast({ variant: 'success', message: 'FFmpeg 설정 저장됨' }),
    onError: () => pushToast({ variant: 'error', message: '저장에 실패했습니다' }),
  });

  const {
    register,
    handleSubmit,
    formState: { errors },
    reset,
  } = useForm<FFmpegConfigForm>({
    resolver: zodResolver(ffmpegConfigSchema),
    defaultValues: {
      FFMPEG_THREADS: configs.FFMPEG_THREADS ?? 1,
      FFMPEG_OUTPUT_FPS: configs.FFMPEG_OUTPUT_FPS ?? 1,
    },
  });

  // 서버 값 도착 시 폼 동기화
  useEffect(() => {
    reset({
      FFMPEG_THREADS: configs.FFMPEG_THREADS ?? 1,
      FFMPEG_OUTPUT_FPS: configs.FFMPEG_OUTPUT_FPS ?? 1,
    });
  }, [configs.FFMPEG_THREADS, configs.FFMPEG_OUTPUT_FPS, reset]);

  const onSubmit = (values: FFmpegConfigForm) => {
    // 두 키를 순차 PUT (BE는 단건 단위 업데이트)
    mutate({ key: 'FFMPEG_THREADS', value: values.FFMPEG_THREADS });
    mutate({ key: 'FFMPEG_OUTPUT_FPS', value: values.FFMPEG_OUTPUT_FPS });
  };

  return (
    <Card title="FFmpeg 설정">
      <form onSubmit={handleSubmit(onSubmit)} className="flex flex-col gap-4" noValidate>
        <Input
          type="number"
          label="FFMPEG_THREADS (1~16)"
          min={1}
          max={16}
          step={1}
          error={errors.FFMPEG_THREADS?.message}
          {...register('FFMPEG_THREADS', { valueAsNumber: true })}
        />
        <Input
          type="number"
          label="FFMPEG_OUTPUT_FPS (1~30)"
          min={1}
          max={30}
          step={1}
          error={errors.FFMPEG_OUTPUT_FPS?.message}
          {...register('FFMPEG_OUTPUT_FPS', { valueAsNumber: true })}
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
