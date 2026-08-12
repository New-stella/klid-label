import { zodResolver } from '@hookform/resolvers/zod';
import { AlertCircle, Lock, Unlock } from 'lucide-react';
import { useEffect, useState } from 'react';
import { useForm } from 'react-hook-form';

import { Button } from '@/components/common/Button';
import { ApiError } from '@/lib/api/errors';
import { KRDS_FOCUS } from '@/lib/focusRing';
import { useUiStore } from '@/stores/useUiStore';

import { useAdminSession } from '../hooks/useAdminSession';
import { useUpdateConfig } from '../hooks/useUpdateConfig';
import { integrationEndpointsSchema, type IntegrationEndpointsForm } from '../schemas';
import { ConfigKey, type ConfigStringMap } from '../types';

import { AdminSessionDialog } from './AdminSessionDialog';

interface Props {
  configs: ConfigStringMap;
}

/** 관리자 인증 후 열리는 유효창 길이 안내(분) — BE 기본값과 같다. */
const TTL_MINUTES_HINT = 10;

/**
 * 화면 필드 ↔ BE 설정 키 매핑.
 *
 * ⚠ **폼 필드 이름에 점(.)을 쓰지 않는다** — react-hook-form 이 점을 중첩 객체 경로로 해석한다.
 * 그래서 점 없는 별칭을 쓰고 **전송 시점에만** 실제 dotted 키로 매핑한다(`DeidentConfigCard` 와 동일).
 */
const FIELDS = [
  {
    name: 'deidentify',
    key: ConfigKey.KPST_DEID_BASE_URL,
    label: '비식별 서버',
    hint: '영상 비식별 처리를 위탁하는 서버 주소입니다.',
  },
  {
    name: 'aiServer',
    key: ConfigKey.INTEGRATION_AI_SERVER_BASE_URL,
    label: 'AI 추론 서버',
    hint: '자동 라벨링(객체 탐지·분할) 추론을 처리하는 서버 주소입니다.',
  },
  {
    name: 'vlm',
    key: ConfigKey.VLM_CLIENT_URL,
    label: '외부 시계열 분석 벤더',
    hint: '영상 시계열 메타 분석을 위탁하는 외부 서버 주소입니다.',
  },
  {
    name: 'controlNotify',
    key: ConfigKey.CONTROL_NOTIFY_URL,
    label: '관제 통지 수신처',
    hint: '작업 완료·수정 통지를 받는 관제서버 주소입니다.',
  },
] as const satisfies ReadonlyArray<{
  name: keyof IntegrationEndpointsForm;
  key: string;
  label: string;
  hint: string;
}>;

const INPUT_CLASS = `w-full rounded-md border border-gray-300 px-3 py-2 text-body ${KRDS_FOCUS}`;

/**
 * R11 — 연동 서버 주소 카드.
 *
 * <h3>잠금이 기본이다</h3>
 * 이 값들은 잘못 바꾸면 학습데이터가 통째로 다른 서버로 나갈 수 있다. 그래서 REVIEWER 로 로그인한
 * 것만으로는 열리지 않고, **「관리자 설정」으로 그 순간 패스워드를 다시 확인**한 짧은 창에서만
 * 편집할 수 있다. 새로고침하면 다시 잠긴다(토큰을 브라우저 저장소에 두지 않는다).
 *
 * <h3>실패 사유를 구분해 안내한다</h3>
 * <ul>
 *   <li><b>형식·스킴 위반</b> — 화면이 1차로 거르고, 통과한 값도 서버가 다시 본다(400).</li>
 *   <li><b>인증 만료</b> — 유효창이 지났다(403). 다시 인증하면 이어서 수정할 수 있다.</li>
 * </ul>
 * <p>⚠ <b>내부망 대역 차단 안내는 폐지됐다</b>(2026-08-10 확정) — 서버가 IP 대역으로 막지 않기
 * 때문이다. 이 연동들은 내부망에 있을 수 있고 망 통제는 인프라 계층 책임이다.
 *
 * <h3>저장은 즉시 반영된다</h3>
 * 서버가 호출 시점마다 주소를 다시 읽으므로 재기동이 필요 없다. 다만 서버가 2노드라 다른 노드는
 * 설정 캐시 TTL(최대 60초) 뒤에 새 주소를 쓴다.
 */
export function IntegrationEndpointsCard({ configs }: Props) {
  const pushToast = useUiStore((s) => s.pushToast);
  const session = useAdminSession();
  const [dialogOpen, setDialogOpen] = useState(false);
  const [saveError, setSaveError] = useState<string | undefined>();

  const { mutateAsync, isPending } = useUpdateConfig();

  const stored: IntegrationEndpointsForm = {
    deidentify: configs[ConfigKey.KPST_DEID_BASE_URL] ?? '',
    aiServer: configs[ConfigKey.INTEGRATION_AI_SERVER_BASE_URL] ?? '',
    vlm: configs[ConfigKey.VLM_CLIENT_URL] ?? '',
    controlNotify: configs[ConfigKey.CONTROL_NOTIFY_URL] ?? '',
  };

  const {
    register,
    handleSubmit,
    reset,
    formState: { isDirty, errors, dirtyFields },
  } = useForm<IntegrationEndpointsForm>({
    resolver: zodResolver(integrationEndpointsSchema),
    defaultValues: stored,
  });

  // 서버 값 도착 시 폼 동기화 (다른 카드와 같은 관례).
  useEffect(() => {
    reset(stored);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [stored.deidentify, stored.aiServer, stored.vlm, stored.controlNotify, reset]);

  // 유효창이 닫히면 편집 중이던 내용을 되돌린다 — 저장되지 않을 값을 입력된 채로 두면
  // "저장된 줄 알았다"가 된다.
  //
  // ⚠ 이때 실패 안내(`saveError`)는 **지우지 않는다**. 서버가 403 을 준 직후 이 효과가 돌기 때문에,
  //    여기서 지우면 방금 띄운 "인증이 만료되었습니다"가 즉시 사라져 사용자가 이유를 못 본다.
  //    안내는 **다시 인증에 성공할 때** 지운다.
  useEffect(() => {
    if (session.unlocked) {
      setSaveError(undefined);
    } else {
      reset(stored);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [session.unlocked, reset]);

  const onSubmit = async (values: IntegrationEndpointsForm) => {
    setSaveError(undefined);
    const changed = FIELDS.filter((f) => dirtyFields[f.name] && values[f.name].trim() !== '');
    if (changed.length === 0) {
      return;
    }
    try {
      // 변경된 키만 전송한다 — 다른 설정 카드와 동일 원칙.
      for (const field of changed) {
        await mutateAsync({
          key: field.key,
          value: values[field.name].trim(),
          adminSessionToken: session.token,
        });
      }
      pushToast({ variant: 'success', message: '연동 서버 주소 저장됨' });
      reset(values);
    } catch (e) {
      setSaveError(describeSaveFailure(e));
      if (e instanceof ApiError && e.status === 403) {
        // 서버가 이미 만료로 봤다 — 화면 상태를 서버 판정에 맞춘다.
        session.lock();
      }
    }
  };

  return (
    <>
      <form onSubmit={handleSubmit(onSubmit)} noValidate>
        <div className="bg-white border border-gray-200 rounded-lg p-5 space-y-5">
          <div className="flex items-center justify-between border-b border-gray-100 pb-3">
            <div className="flex items-center gap-2">
              <h3 className="text-title-sm font-semibold text-gray-700">연동 서버 주소</h3>
              {session.unlocked ? (
                <span
                  className="flex items-center gap-1 text-caption text-primary-600"
                  data-testid="admin-session-remaining"
                >
                  <Unlock className="h-3.5 w-3.5" aria-hidden="true" />
                  수정 가능 {session.remainingLabel} 남음
                </span>
              ) : (
                <span className="flex items-center gap-1 text-caption text-gray-500">
                  <Lock className="h-3.5 w-3.5" aria-hidden="true" />
                  읽기 전용
                </span>
              )}
            </div>
            <div className="flex items-center gap-2">
              {!session.unlocked && (
                <Button
                  type="button"
                  variant="secondary"
                  size="sm"
                  onClick={() => setDialogOpen(true)}
                >
                  관리자 설정
                </Button>
              )}
              <Button
                type="submit"
                variant="primary"
                size="sm"
                loading={isPending}
                disabled={!session.unlocked || !isDirty || isPending}
              >
                저장
              </Button>
            </div>
          </div>

          {FIELDS.map((field) => (
            <div className="space-y-2" key={field.name}>
              <label
                className="block text-label font-medium text-gray-700"
                htmlFor={`endpoint-${field.name}`}
              >
                {field.label}
              </label>
              <input
                id={`endpoint-${field.name}`}
                type="url"
                inputMode="url"
                readOnly={!session.unlocked}
                aria-readonly={!session.unlocked}
                placeholder="미설정 — 배포 기본값 사용 중"
                aria-invalid={errors[field.name] ? 'true' : 'false'}
                className={`${INPUT_CLASS} ${
                  session.unlocked ? 'bg-white' : 'bg-gray-50 text-gray-600'
                }`}
                {...register(field.name)}
              />
              <p className="text-caption text-gray-400">{field.hint}</p>
              {errors[field.name] && (
                <p className="flex items-center gap-1 text-caption text-danger" role="alert">
                  <AlertCircle className="h-3.5 w-3.5 shrink-0" aria-hidden="true" />
                  {errors[field.name]?.message}
                </p>
              )}
            </div>
          ))}

          {saveError && (
            <p className="flex items-center gap-1 text-caption text-danger" role="alert">
              <AlertCircle className="h-3.5 w-3.5 shrink-0" aria-hidden="true" />
              {saveError}
            </p>
          )}

          <p className="text-caption text-gray-400">
            저장하면 재기동 없이 다음 호출부터 새 주소가 적용됩니다. 서버가 여러 대인 경우 다른
            서버에는 최대 1분 뒤에 반영됩니다.
          </p>
        </div>
      </form>

      <AdminSessionDialog
        open={dialogOpen}
        onClose={() => setDialogOpen(false)}
        onSubmit={session.open}
        isSubmitting={session.isOpening}
        error={session.error}
        ttlMinutesHint={TTL_MINUTES_HINT}
      />
    </>
  );
}

/**
 * 저장 실패 사유를 <b>두 갈래로</b> 나눈다 — 값 검증 실패(400) / 인증 만료(403).
 *
 * 400 문구는 서버가 준 것을 그대로 쓴다(서버는 입력 원문·호스트를 문구에 싣지 않는다).
 * 화면 zod 검증이 형식·스킴을 1차로 걸러 대부분은 여기까지 오지 않지만, 두 검증의 범위가 완전히
 * 같지는 않으므로 서버 거부를 그대로 보여줄 통로를 남긴다.
 */
function describeSaveFailure(error: unknown): string {
  if (error instanceof ApiError) {
    if (error.status === 403) {
      return '관리자 인증이 만료되었습니다. 「관리자 설정」으로 다시 인증해 주세요.';
    }
    if (error.status === 400) {
      return error.userMessage || '사용할 수 없는 주소입니다. 주소를 다시 확인해 주세요.';
    }
    return error.userMessage || '저장에 실패했습니다.';
  }
  return '저장에 실패했습니다.';
}
