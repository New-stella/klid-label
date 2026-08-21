import { zodResolver } from '@hookform/resolvers/zod';
import { useEffect } from 'react';
import { useForm } from 'react-hook-form';

import { Button } from '@/components/common/Button';
import { Textarea } from '@/components/common/Textarea';
import { useUiStore } from '@/stores/useUiStore';

import { useUpdateConfig } from '../hooks/useUpdateConfig';
import { batchConfigSchema, VLM_SKIP_REASON_MAX, type BatchConfigForm } from '../schemas';
import {
  ConfigKey,
  isConfigOn,
  type ConfigMap,
  type ConfigStringMap,
  type ConfigUpdateRequest,
} from '../types';

interface Props {
  configs: ConfigMap;
  /**
   * 문자열 원문 설정 — 시계열 전체 건너뛰기 스위치·사유가 여기 담긴다. [@design ADR-050]
   *
   * ⚠ `configs`(숫자 맵)로는 읽히지 않는다. 그쪽은 `Number()` 변환에 실패한 값을 **버리므로**
   * `'true'`·사유 문구가 도달하지 못한다. 두 맵은 같은 응답을 서로 다른 select 로 본 것이다.
   */
  configStrings?: ConfigStringMap;
}

/** 스위치가 켜져 있음을 알리는 보조 문단 id — 버튼이 `aria-describedby` 로 가리킨다. */
const SKIP_ON_NOTICE_ID = 'vlm-skip-default-on-notice';
/** 사유가 필수임을 알리는 문단 id. */
const SKIP_REASON_HINT_ID = 'vlm-skip-default-reason-hint';

/**
 * UI/UX §4-16 ① 배치 처리 카드 (독립 저장).
 *
 * 보안: zod 스키마로 입력 범위 검증.
 *
 * [@design SCREEN-025] [@design ADR-050] 처리 주기·동시 처리 수에 더해 **시계열 위탁 전체
 * 건너뛰기** 스위치와 그 사유를 담는다. 두 값은 dotted 키(BOOLEAN·STRING)라 폼에서는 점 없는
 * 별칭을 쓰고 전송 시점에만 실제 키로 매핑한다(비식별·연동 주소 카드와 같은 관례).
 */
export function BatchConfigCard({ configs, configStrings = {} }: Props) {
  const pushToast = useUiStore((s) => s.pushToast);
  const { mutateAsync, isPending } = useUpdateConfig({
    onSuccess: () => pushToast({ variant: 'success', message: '배치 설정 저장됨' }),
    onError: () => pushToast({ variant: 'error', message: '저장에 실패했습니다' }),
  });

  const defaults = (): BatchConfigForm => ({
    BATCH_INTERVAL_SEC: configs.BATCH_INTERVAL_SEC ?? 60,
    BATCH_CONCURRENCY: configs.BATCH_CONCURRENCY ?? 1,
    vlmSkipByDefault: isConfigOn(configStrings[ConfigKey.BATCH_VLM_SKIP_BY_DEFAULT]),
    vlmSkipReason: configStrings[ConfigKey.BATCH_VLM_SKIP_BY_DEFAULT_REASON] ?? '',
  });

  const {
    register,
    handleSubmit,
    watch,
    setValue,
    formState: { isDirty, dirtyFields },
    reset,
  } = useForm<BatchConfigForm>({
    resolver: zodResolver(batchConfigSchema),
    defaultValues: defaults(),
  });

  const skipByDefaultValue = configStrings[ConfigKey.BATCH_VLM_SKIP_BY_DEFAULT];
  const skipReasonValue = configStrings[ConfigKey.BATCH_VLM_SKIP_BY_DEFAULT_REASON];
  useEffect(() => {
    reset({
      BATCH_INTERVAL_SEC: configs.BATCH_INTERVAL_SEC ?? 60,
      BATCH_CONCURRENCY: configs.BATCH_CONCURRENCY ?? 1,
      vlmSkipByDefault: isConfigOn(skipByDefaultValue),
      vlmSkipReason: skipReasonValue ?? '',
    });
  }, [
    configs.BATCH_INTERVAL_SEC,
    configs.BATCH_CONCURRENCY,
    skipByDefaultValue,
    skipReasonValue,
    reset,
  ]);

  const batchInterval = watch('BATCH_INTERVAL_SEC');
  const concurrency = watch('BATCH_CONCURRENCY');
  const skipByDefault = watch('vlmSkipByDefault');
  const skipReason = watch('vlmSkipReason');

  const reasonBlank = skipReason.trim().length === 0;

  /**
   * 저장을 막을 것인가 — **사유 축의 단일 판정**.
   *
   * ★ 사유가 비어 있어도 막지 않는 경우가 하나 있다: **스위치가 꺼져 있고 사유를 손대지도 않은**
   * 상태다. 대부분의 설치가 그 상태이므로, 여기서 막으면 처리 주기 하나를 고치려던 사람까지
   * 저장하지 못한다(이 기능과 무관한 사람을 막는 검증은 검증이 아니라 고장이다).
   */
  const reasonBlocked = reasonBlank && (skipByDefault || dirtyFields.vlmSkipReason === true);

  /**
   * 스위치 토글 — **사유가 비어 있으면 켜지지 않는다**(끄는 것은 언제나 가능하다).
   *
   * ★ 이 문구가 건너뜀 표식의 사유로 그대로 기록되어, 나중에 그 영상의 시계열이 왜 비어 있는지
   * 되짚는 유일한 근거가 된다. 서버도 400 으로 막지만 화면에서 먼저 막아, 다 적고 저장한 뒤에야
   * 거부를 알게 되는 동선을 없앤다.
   */
  const toggleSkipByDefault = () => {
    if (!skipByDefault && reasonBlank) return;
    setValue('vlmSkipByDefault', !skipByDefault, { shouldDirty: true });
  };

  /**
   * 저장 — 변경된 키만, **순서를 지켜 하나씩** 보낸다. [@design ADR-050]
   *
   * <p>변경된 키만 보내는 이유: 카드 내 다른 값을 만지지 않았는데도 항상 전체를 보내면 동시 편집 시
   * 남의 변경을 되돌리는 write-write 충돌을 만든다.
   *
   * <h3>★왜 순서가 필요한가 (스위치·사유 두 키)</h3>
   * <p>서버는 두 키를 <b>따로 받으면서 서로를 검사</b>한다 — 켜는 저장은 <b>저장된</b> 사유를 읽고,
   * 사유를 비우는 저장은 <b>저장된</b> 스위치를 읽는다. 그래서 두 요청을 동시에 던지면 도착 순서에
   * 따라 한쪽이 <b>400</b> 으로 떨어진다. 구 구현은 스위치를 먼저, 그것도 기다리지 않고 던져서
   * <b>활성화 정상 동선이 1회차에 반드시 실패</b>했다(사유만 저장되고 스위치는 꺼진 채로 남으며,
   * 사용자는 원인을 알 수 없는 실패 토스트만 본다).
   *
   * <ul>
   *   <li><b>켤 때</b> — 사유를 먼저 저장하고, <b>성공한 뒤</b> 스위치를 저장한다.</li>
   *   <li><b>끌 때(그 밖의 모든 경우)</b> — 스위치를 먼저 저장하고 그 뒤 사유를 저장한다. 스위치가
   *       켜져 있는 동안에는 사유를 비울 수 없으므로(400), 순서가 반대면 두 검사 사이에 갇힌다.</li>
   * </ul>
   *
   * <p>앞 요청이 실패하면 <b>뒤 요청을 보내지 않는다</b> — 보내면 어차피 400 이거나, 더 나쁘게는
   * 절반만 적용된 상태가 남는다. 실패 안내는 훅의 `onError` 가 이미 띄운다.
   *
   * ⚠ 숫자 키(처리 주기·동시 처리 수)의 동작은 <b>바꾸지 않는다</b> — 서로 의존이 없어 순서가 무관하며,
   *   여기서는 선언 순서를 그대로 유지해 직렬로 흘려보낼 뿐이다.
   */
  const onSubmit = async (values: BatchConfigForm) => {
    const requests: ConfigUpdateRequest[] = [];
    if (dirtyFields.BATCH_INTERVAL_SEC) {
      requests.push({ key: 'BATCH_INTERVAL_SEC', value: values.BATCH_INTERVAL_SEC });
    }
    if (dirtyFields.BATCH_CONCURRENCY) {
      requests.push({ key: 'BATCH_CONCURRENCY', value: values.BATCH_CONCURRENCY });
    }

    // BOOLEAN 은 서버 저장 형식이 'true'/'false' 문자열이다(판독은 `isConfigOn` 이 단독으로 한다).
    const switchRequest: ConfigUpdateRequest | null = dirtyFields.vlmSkipByDefault
      ? {
          key: ConfigKey.BATCH_VLM_SKIP_BY_DEFAULT,
          value: values.vlmSkipByDefault ? 'true' : 'false',
        }
      : null;
    const reasonRequest: ConfigUpdateRequest | null = dirtyFields.vlmSkipReason
      ? {
          key: ConfigKey.BATCH_VLM_SKIP_BY_DEFAULT_REASON,
          value: values.vlmSkipReason.trim(),
        }
      : null;

    // ★ 켜는 저장일 때만 사유가 앞선다. 그 밖(끄는 저장·스위치 미변경)은 스위치가 앞선다.
    const turningOn = switchRequest !== null && values.vlmSkipByDefault;
    const skipPair = turningOn ? [reasonRequest, switchRequest] : [switchRequest, reasonRequest];
    skipPair.forEach((req) => {
      if (req) requests.push(req);
    });

    for (const req of requests) {
      try {
        await mutateAsync(req);
      } catch {
        // 앞이 실패하면 뒤를 보내지 않는다(부분 적용 방지). 사용자 안내는 훅의 onError 가 진다.
        return;
      }
    }
  };

  return (
    <form onSubmit={handleSubmit(onSubmit)} noValidate>
      <div className="bg-white border border-gray-200 rounded-lg p-5 space-y-5">
        <div className="flex items-center justify-between border-b border-gray-100 pb-3">
          <h3 className="text-title-sm font-semibold text-gray-700">배치 처리</h3>
          <Button
            type="submit"
            variant="primary"
            size="sm"
            loading={isPending}
            disabled={!isDirty || isPending || reasonBlocked}
          >
            저장
          </Button>
        </div>

        {/* 처리 주기 */}
        <div className="space-y-2">
          <label className="flex items-center justify-between text-label" htmlFor="batch-interval">
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
          <div className="flex justify-between text-caption text-gray-400">
            <span>10s</span>
            <span>300s</span>
          </div>
          <p className="text-caption text-gray-400">
            배치 파이프라인이 신규 영상을 픽업해 처리하는 주기입니다. 짧을수록 새 영상이 빨리
            처리되지만 서버·GPU 부하가 커집니다. (10~300초)
          </p>
        </div>

        {/* 동시 처리 수 */}
        <div className="space-y-2">
          <label className="flex items-center justify-between text-label" htmlFor="concurrent-jobs">
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
          <div className="flex justify-between text-caption text-gray-400">
            <span>1</span>
            <span>8</span>
          </div>
          <p className="text-caption text-gray-400">
            동시에 병렬 처리할 영상 수입니다. 높일수록 처리량이 늘지만 GPU 메모리·자원 경합이
            커집니다. (1~8)
          </p>
        </div>

        {/* 시계열 위탁 전체 건너뛰기 — [@design SCREEN-025] [@design ADR-050].
            ★ 켜짐 상태는 색이 아니라 **문구**가 말한다(아래 안내 문단). 켜져 있는 동안 들어오는
              영상은 전건이 시계열 없이 확정되므로, 끄는 것을 잊었을 때의 결과까지 함께 알린다. */}
        <div
          className={`space-y-2 rounded-md border p-3 ${
            skipByDefault ? 'border-danger-200 bg-danger-50' : 'border-gray-200 bg-white'
          }`}
          data-testid="vlm-skip-default-section"
        >
          <label
            className="flex items-center justify-between gap-3 text-label"
            htmlFor="vlm-skip-default"
          >
            <span className="font-medium text-gray-700">시계열 위탁 전체 건너뛰기</span>
            <input
              id="vlm-skip-default"
              type="checkbox"
              role="switch"
              className="h-4 w-8 shrink-0 cursor-pointer accent-primary-600"
              checked={skipByDefault}
              onChange={toggleSkipByDefault}
              aria-describedby={skipByDefault ? SKIP_ON_NOTICE_ID : SKIP_REASON_HINT_ID}
            />
          </label>

          {skipByDefault && (
            <p
              id={SKIP_ON_NOTICE_ID}
              data-testid="vlm-skip-default-on-notice"
              className="text-caption text-danger-700"
            >
              지금 들어오는 영상은 외부 시계열 분석에 위탁하지 않고 건너뛴 것으로 기록됩니다. 벤더
              연동이 끝나면 이 스위치를 꺼 주세요 — 끄지 않으면 이후 영상도 계속 건너뜁니다.
            </p>
          )}

          <label className="block text-label font-medium text-gray-700" htmlFor="vlm-skip-reason">
            건너뛰기 사유
          </label>
          <Textarea
            id="vlm-skip-reason"
            maxLength={VLM_SKIP_REASON_MAX}
            placeholder="예) 외부 시계열 분석 벤더 연동 전"
            aria-describedby={SKIP_REASON_HINT_ID}
            aria-invalid={reasonBlocked || undefined}
            {...register('vlmSkipReason')}
          />
          <p id={SKIP_REASON_HINT_ID} className="text-caption text-gray-400">
            {reasonBlocked
              ? '건너뛰기 사유를 입력해야 저장할 수 있습니다. 이 문구가 건너뜀 기록의 사유로 남습니다.'
              : `필수입니다. 이 문구가 건너뜀 기록의 사유로 그대로 남아, 나중에 그 영상의 시계열이 왜 비어 있는지 되짚는 근거가 됩니다. (최대 ${VLM_SKIP_REASON_MAX}자)`}
          </p>
        </div>
      </div>
    </form>
  );
}
