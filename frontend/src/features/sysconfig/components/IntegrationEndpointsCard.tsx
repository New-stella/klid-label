import { zodResolver } from '@hookform/resolvers/zod';
import { AlertCircle, Info, Lock, Unlock } from 'lucide-react';
import { useEffect, useState } from 'react';
import { useForm } from 'react-hook-form';

import { Button } from '@/components/common/Button';
import { AdminSessionDialog } from '@/features/adminSession/components/AdminSessionDialog';
import {
  ADMIN_SESSION_TTL_MINUTES_HINT,
  useAdminSessionWindow,
} from '@/features/adminSession/hooks/useAdminSessionWindow';
import { ApiError } from '@/lib/api/errors';
import { KRDS_FOCUS } from '@/lib/focusRing';
import { useUiStore } from '@/stores/useUiStore';

import { useUpdateConfig } from '../hooks/useUpdateConfig';
import { integrationEndpointsSchema, type IntegrationEndpointsForm } from '../schemas';
import { ConfigKey, type ConfigStringMap } from '../types';

interface Props {
  configs: ConfigStringMap;
}

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
    placeholder: '미설정 — 배포 기본값 사용 중',
  },
  {
    name: 'augment',
    key: ConfigKey.AUGMENT_EXTERNAL_BASE_URL,
    label: '외부 증강 벤더',
    hint: '생성형 AI 증강을 위탁하는 외부 서버 주소입니다. 연동이 확정되기 전에는 비워 둡니다 — 비어 있는 것이 아직 연동하지 않았다는 뜻입니다.',
    // ⚠ 여기에 «배포 기본값 사용 중» 을 쓰지 않는다 — 이 칸의 빈 값은 «기본값으로 도는 중» 이
    //   아니라 «아직 연동하지 않음» 이다. 두 뜻을 같은 문구로 덮으면 미연동이 정상 가동으로 읽힌다.
    placeholder: '연동 전에는 비워 둡니다',
  },
  {
    name: 'controlNotify',
    key: ConfigKey.CONTROL_NOTIFY_URL,
    label: '관제 통지 수신처',
    hint: '작업 완료·수정 통지를 받는 관제 창구 주소입니다. 관제 웹서버 주소 또는 데이터셋 창구를 가진 WAS 주소를 넣습니다.',
    placeholder: '미설정 — 배포 기본값 사용 중',
  },
  {
    name: 'controlAccount',
    key: ConfigKey.CONTROL_ACCOUNT_URL,
    label: '관제 계정 창구',
    hint: '관제 채널 세션 연장·로그아웃을 중계할 관제 창구 주소입니다. 관제 웹서버 주소 또는 계정 창구를 가진 WAS 주소를 넣습니다. 비어 있으면 세션 연장이 동작하지 않습니다.',
    // ⚠ 여기에 «배포 기본값 사용 중» 을 쓰지 않는다 — 이 칸은 배포 기본값이 **비어 있어**, 빈 칸은
    //   «기본값으로 도는 중» 이 아니라 «세션 연장이 동작하지 않음» 이다. 그 문구는 사실과 달라진다.
    placeholder: '미설정 — 세션 연장 불가',
  },
] as const satisfies ReadonlyArray<{
  name: keyof IntegrationEndpointsForm;
  key: string;
  label: string;
  hint: string;
  placeholder: string;
}>;

/**
 * 증강 주소를 채웠을 때 함께 알리는 **짝** — 콜백 허용 주소 목록.
 *
 * ★ 주소만 채우면 위탁은 나가는데 **결과를 못 받는다**. 한쪽만 채운 상태는 저장 시점에 드러나지
 * 않고 위탁을 걸려는 순간에야 거부되므로, 채우는 자리에서 미리 알린다.
 *
 * ⚠ 그 허용 목록 자체를 이 카드에 합치지 말 것 — **다른 축**이다. 여기서는 안내만 한다.
 */
const AUGMENT_CALLBACK_PAIR_NOTICE =
  '증강 주소를 채우면 결과를 되받을 콜백 허용 주소 목록도 함께 채워야 합니다';

const INPUT_CLASS = `w-full rounded-md border border-gray-300 px-3 py-2 text-body ${KRDS_FOCUS}`;

/**
 * R11 — 연동 서버 주소 카드. [@design SCREEN-042] [@design API-069]
 *
 * <h3>여기 있는 것은 «저장한 값이 곧 진실원» 인 축뿐이다</h3>
 * 비식별 서버 · 외부 증강 벤더 · 관제 통지 수신처 · 관제 계정 창구다. 모두 <b>보낼 곳이 한 곳뿐이라
 * 고를 일이 없어</b> 칸 하나가 곧 진실원이다.
 *
 * <p>★ 관제 계정 창구는 관제 통지 수신처와 <b>별개 값</b>이다(세션 연장·로그아웃 중계용). 배포
 * 기본값이 비어 있어, 비면 관제 채널 세션 연장이 동작하지 않는다 — 그래서 그 칸의 빈 값 안내에는
 * «배포 기본값» 문구를 쓰지 않는다.
 *
 * <p>⚠ <b>AI 추론 서버·외부 시계열 분석 벤더 칸을 여기에 되살리지 말 것.</b> 그 두 축은 장비를
 * 여러 대 두고 골라 보내므로 주소의 진실원이 <b>장비 원장</b>이고 위탁도 원장 주소로 나간다.
 * 칸을 두면 저장은 되는데 위탁 주소는 그대로여서 <b>오류도 경고도 없이 아무 일이 안 일어난다</b>
 * (조용한 실패). 주소를 바꾸는 자리는 아래 「추론·외부 시계열 분석 장비 목록」 하나다.
 *
 * <p>⚠ 칸을 없앤 것이지 <b>설정 키를 없앤 것이 아니다</b> — 배포 설정값은 그 유형의 장비가 원장에
 * 하나도 없을 때 최초 1회 씨앗으로 계속 쓰인다.
 *
 * <p>★ 두 축을 가르는 것은 「AI 위탁인가」가 아니라 <b>「고를 대상이 여럿인가」</b>다. 외부 증강도
 * AI 위탁이지만 보낼 곳이 한 곳이라 이 카드가 다룬다.
 *
 * <h3>증강 칸에만 있는 두 성질</h3>
 * <ul>
 *   <li><b>비어 있는 것이 정상</b> — 그 빈 값이 «아직 연동하지 않았다»의 유일한 표현이다.
 *       미리 채우면 연동된 것으로 판정돼 아무도 받지 않는 주소로 위탁이 나가고, 그 실패가
 *       벤더 장애처럼 보인다.</li>
 *   <li><b>콜백 허용 목록과 짝</b> — 주소만 채우면 위탁은 나가는데 결과를 못 받는다. 채우는
 *       순간 짝을 안내한다(허용 목록 자체는 이 카드가 다루는 값이 아니다).</li>
 * </ul>
 *
 * <h3>잠금이 기본이다</h3>
 * 이 값들은 잘못 바꾸면 학습데이터가 통째로 다른 서버로 나갈 수 있다. 그래서 REVIEWER 로 로그인한
 * 것만으로는 열리지 않고, **「관리자 확인」으로 그 순간 패스워드를 다시 확인**한 짧은 창에서만
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
  const session = useAdminSessionWindow();
  const [dialogOpen, setDialogOpen] = useState(false);
  const [saveError, setSaveError] = useState<string | undefined>();

  const { mutateAsync, isPending } = useUpdateConfig();

  const stored: IntegrationEndpointsForm = {
    deidentify: configs[ConfigKey.KPST_DEID_BASE_URL] ?? '',
    augment: configs[ConfigKey.AUGMENT_EXTERNAL_BASE_URL] ?? '',
    controlNotify: configs[ConfigKey.CONTROL_NOTIFY_URL] ?? '',
    controlAccount: configs[ConfigKey.CONTROL_ACCOUNT_URL] ?? '',
  };

  const {
    register,
    handleSubmit,
    reset,
    watch,
    formState: { isDirty, errors, dirtyFields },
  } = useForm<IntegrationEndpointsForm>({
    resolver: zodResolver(integrationEndpointsSchema),
    defaultValues: stored,
  });

  // 서버 값 도착 시 폼 동기화 (다른 카드와 같은 관례).
  useEffect(() => {
    reset(stored);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [stored.deidentify, stored.augment, stored.controlNotify, stored.controlAccount, reset]);

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

  // 증강 주소를 채우는 «순간» 짝을 알린다 — 저장한 뒤에 알리면 이미 한쪽만 채운 상태로 나간다.
  const augmentFilled = (watch('augment') ?? '').trim() !== '';

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
                  관리자 확인
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
                placeholder={field.placeholder}
                aria-invalid={errors[field.name] ? 'true' : 'false'}
                className={`${INPUT_CLASS} ${
                  session.unlocked ? 'bg-white' : 'bg-gray-50 text-gray-600'
                }`}
                {...register(field.name)}
              />
              <p className="text-caption text-gray-600">{field.hint}</p>
              {field.name === 'augment' && augmentFilled && (
                <p
                  className="flex items-center gap-1 text-caption text-gray-700"
                  role="status"
                  data-testid="augment-callback-pair-notice"
                >
                  <Info className="h-3.5 w-3.5 shrink-0" aria-hidden="true" />
                  {AUGMENT_CALLBACK_PAIR_NOTICE}
                </p>
              )}
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

          <p className="text-caption text-gray-600">
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
        ttlMinutesHint={ADMIN_SESSION_TTL_MINUTES_HINT}
        unlockTargetLabel="연동 서버 주소 수정"
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
      return '관리자 확인이 만료되었습니다. 「관리자 확인」으로 다시 확인해 주세요.';
    }
    if (error.status === 400) {
      return error.userMessage || '사용할 수 없는 주소입니다. 주소를 다시 확인해 주세요.';
    }
    return error.userMessage || '저장에 실패했습니다.';
  }
  return '저장에 실패했습니다.';
}
