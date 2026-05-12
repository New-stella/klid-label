import { zodResolver } from '@hookform/resolvers/zod';
import { Save } from 'lucide-react';
import { useEffect } from 'react';
import { useForm } from 'react-hook-form';

import { Button } from '@/components/common/Button';
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
    watch,
    formState: { isDirty },
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

  const threads = watch('FFMPEG_THREADS');

  const onSubmit = (values: FFmpegConfigForm) => {
    // 두 키를 순차 PUT (BE는 단건 단위 업데이트)
    mutate({ key: 'FFMPEG_THREADS', value: values.FFMPEG_THREADS });
    mutate({ key: 'FFMPEG_OUTPUT_FPS', value: values.FFMPEG_OUTPUT_FPS });
  };

  return (
    <form onSubmit={handleSubmit(onSubmit)} noValidate>
      <div className="bg-white border border-gray-200 rounded-lg p-5 space-y-5">
        <div className="flex items-center justify-between border-b border-gray-100 pb-3">
          <h3 className="text-sm font-semibold text-gray-700">FFmpeg 설정</h3>
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

        {/* FFmpeg 스레드 수 */}
        <div className="space-y-2">
          <label className="flex items-center justify-between text-sm" htmlFor="ffmpeg-threads">
            <span className="font-medium text-gray-700">스레드 수</span>
            <span className="text-primary-600 font-semibold tabular-nums">{threads}</span>
          </label>
          <input
            id="ffmpeg-threads"
            type="range"
            min={1}
            max={16}
            step={1}
            className="w-full h-2 bg-gray-200 rounded-full appearance-none cursor-pointer accent-primary-600"
            {...register('FFMPEG_THREADS', { valueAsNumber: true })}
          />
          <div className="flex justify-between text-xs text-gray-400">
            <span>1</span>
            <span>16</span>
          </div>
        </div>

        {/* 프레임 추출 간격 */}
        <div className="space-y-2">
          <label className="block text-sm font-medium text-gray-700" htmlFor="output-fps">
            프레임 추출 간격 (fps)
          </label>
          <select
            id="output-fps"
            className="w-full text-sm border border-gray-300 rounded-lg px-3 py-2 bg-white focus:outline-none focus:ring-2 focus:ring-primary-500"
            {...register('FFMPEG_OUTPUT_FPS', { valueAsNumber: true })}
          >
            <option value={1}>1 fps (1초당 1프레임)</option>
            <option value={2}>2 fps</option>
            <option value={5}>5 fps</option>
            <option value={10}>10 fps</option>
          </select>
        </div>
      </div>
    </form>
  );
}
