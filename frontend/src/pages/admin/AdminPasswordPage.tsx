import { zodResolver } from '@hookform/resolvers/zod';
import { AlertCircle } from 'lucide-react';
import { useState } from 'react';
import { useForm } from 'react-hook-form';

import { Alert } from '@/components/common/Alert';
import { Button } from '@/components/common/Button';
import { PageHeader } from '@/components/common/PageHeader';
import { changeAdminPassword } from '@/features/adminSession/api';
import { AdminSessionDialog } from '@/features/adminSession/components/AdminSessionDialog';
import { AdminSessionStatus } from '@/features/adminSession/components/AdminSessionStatus';
import {
  ADMIN_SESSION_TTL_MINUTES_HINT,
  useAdminSessionWindow,
} from '@/features/adminSession/hooks/useAdminSessionWindow';
import {
  adminPasswordChangeSchema,
  type AdminPasswordChangeForm,
} from '@/features/adminSession/schema';
import { ApiError } from '@/lib/api/errors';
import { KRDS_FOCUS } from '@/lib/focusRing';

const INPUT_CLASS = `w-full rounded-md border border-gray-300 bg-white px-3 py-2 text-body ${KRDS_FOCUS}`;

interface FieldSpec {
  name: keyof AdminPasswordChangeForm;
  label: string;
  hint: string;
}

const FIELDS: FieldSpec[] = [
  {
    name: 'currentPassword',
    label: '현재 패스워드',
    hint: '관리자 확인이 열려 있어도 현재 값을 한 번 더 대조합니다.',
  },
  {
    name: 'newPassword',
    label: '새 패스워드',
    hint: '8자 이상이어야 하며 현재 값과 같으면 저장되지 않습니다.',
  },
  {
    name: 'newPasswordConfirm',
    label: '새 패스워드 확인',
    hint: '두 칸이 서로 다르면 제출되지 않습니다.',
  },
];

/**
 * 관리자 패스워드 교체 화면(`/admin/password`). [@design SCREEN-041] [@design API-223]
 *
 * <h3>요건이 셋이다</h3>
 * 검수자 권한 · 유효한 관리자 유효창 · 현재 패스워드 재확인. 유효창만으로 바꿀 수 있으면 잠깐
 * 열린 창을 가로챈 사람이 자격 자체를 갈아 치워 정당한 운영자를 잠글 수 있으므로 현재 값을 한
 * 번 더 받는다.
 *
 * <h3>★교체가 성공하면 유효창을 즉시 버린다</h3>
 * 서명키가 현재 패스워드 해시에 의존하므로 <b>방금 이 요청에 쓴 토큰까지 그 순간부터 전부 403</b>
 * 이다. 버리지 않으면 사용자가 이어지는 관리 조작마다 영문 모를 403 을 받는다. 그래서 성공
 * 처리에서 {@code lock()} 을 부르고, 그것이 <b>의도된 결과</b>임을 성공 안내에서 함께 알린다 —
 * 오류처럼 보이면 사람이 방금 바꾼 값을 의심해 되돌리려 든다.
 *
 * <h3>여기서 화면 밖으로 쫓아내지 않는다</h3>
 * 진입 게이트는 <b>첫 렌더 한 번</b>만 판정하므로(`AdminSessionGuard`), 성공 직후 유효창을 버려도
 * 이 화면이 유지되어 성공 안내를 볼 수 있다.
 *
 * <h3>실패 사유를 지나치게 가르지 않는다</h3>
 * 권한이 없는 것과 유효창이 끝난 것을 구분해 알리지 않는다(둘 다 403) — 구분하면 그 응답이
 * 유효창 상태를 알려주는 신호가 된다. 어떤 안내에도 입력한 값을 싣지 않는다.
 */
export function AdminPasswordPage() {
  const session = useAdminSessionWindow();
  const [dialogOpen, setDialogOpen] = useState(false);
  const [submitError, setSubmitError] = useState<string | undefined>();
  const [changed, setChanged] = useState(false);
  const [isSubmitting, setIsSubmitting] = useState(false);

  const {
    register,
    handleSubmit,
    reset,
    formState: { errors, isValid },
  } = useForm<AdminPasswordChangeForm>({
    resolver: zodResolver(adminPasswordChangeSchema),
    defaultValues: { currentPassword: '', newPassword: '', newPasswordConfirm: '' },
    mode: 'onChange',
  });

  const clearForm = () =>
    reset({ currentPassword: '', newPassword: '', newPasswordConfirm: '' });

  const submit = handleSubmit(async (values) => {
    setSubmitError(undefined);
    // 유효창이 없으면 요청을 보내기 전에 확인 창을 먼저 연다 — 보내 봐야 403 이고,
    // 그 거부는 화면에서 「이유를 알 수 없는 실패」로 보인다.
    if (!session.unlocked) {
      setDialogOpen(true);
      return;
    }
    setIsSubmitting(true);
    try {
      await changeAdminPassword(
        { currentPassword: values.currentPassword, newPassword: values.newPassword },
        session.token,
      );
      // ★성공하면 방금 쓴 토큰까지 무효다 — 서명키가 현재 패스워드에 의존한다.
      session.lock();
      setChanged(true);
      clearForm();
    } catch (e) {
      setSubmitError(describeChangeFailure(e));
      if (e instanceof ApiError && e.status === 403) {
        // 서버가 이미 만료로 봤다 — 화면 상태를 서버 판정에 맞춘다.
        session.lock();
      }
    } finally {
      setIsSubmitting(false);
    }
  });

  return (
    <section className="flex flex-col gap-6">
      <PageHeader
        title="관리자 패스워드 교체"
        description="관리 기능 진입에 쓰는 공유 패스워드를 운영 중에 바꿉니다."
      />

      <div className="flex max-w-2xl flex-col gap-4">
        <AdminSessionStatus
          unlocked={session.unlocked}
          remainingLabel={session.remainingLabel}
          onReauthenticate={() => setDialogOpen(true)}
          scopeLabel="패스워드 교체에는 관리자 확인이 필요합니다."
        />

        {/* ① 교체 전 안내 */}
        <div className="flex flex-col gap-3 rounded-lg border border-gray-200 bg-white p-5">
          <h2 className="text-title-sm font-semibold text-gray-800">바꾸기 전에 확인하세요</h2>
          <ul className="list-disc space-y-1 pl-5 text-body text-gray-700">
            <li>검수자 권한이 있어야 합니다 — 관리자 확인이 역할을 대신하지 않습니다.</li>
            <li>관리자 확인이 열려 있어야 합니다.</li>
            <li>현재 패스워드를 다시 입력해야 합니다.</li>
          </ul>
          <Alert variant="info" title="바꾸면 열려 있던 관리자 확인이 모두 끊깁니다">
            지금 이 화면을 열어 준 확인도 함께 끊깁니다. 오류가 아니라 정상 동작이며, 이어서 관리
            기능을 쓰려면 새 패스워드로 다시 확인해야 합니다.
          </Alert>
          <p className="text-caption text-gray-600">
            현재 패스워드도 새 패스워드도 화면에 다시 나타나지 않고 기록에도 남지 않습니다. 실패
            안내에도 값을 싣지 않습니다.
          </p>
        </div>

        {/* ③ 교체 후 안내 — 성공했을 때만 보인다. */}
        {changed && (
          <div
            className="flex flex-col gap-3 rounded-lg border border-gray-200 bg-white p-5"
            data-testid="admin-password-changed"
          >
            <Alert variant="info" title="관리자 패스워드를 바꿨습니다">
              열려 있던 관리자 확인이 모두 끊겼습니다. 이 교체가 의도한 결과입니다.
            </Alert>
            <p className="text-body text-gray-700">
              관리 기능의 저장은 새 패스워드로 관리자 확인을 다시 거쳐야 열립니다. 조회는 검수자
              권한만으로 계속 됩니다.
            </p>
            <div>
              <Button
                type="button"
                variant="primary"
                size="sm"
                onClick={() => setDialogOpen(true)}
              >
                새 패스워드로 다시 확인
              </Button>
            </div>
          </div>
        )}

        {/* ② 패스워드 교체 폼 */}
        <form
          onSubmit={submit}
          noValidate
          className="flex flex-col gap-4 rounded-lg border border-gray-200 bg-white p-5"
        >
          {FIELDS.map((field) => (
            <div className="space-y-2" key={field.name}>
              <label
                className="block text-label font-medium text-gray-700"
                htmlFor={`admin-password-${field.name}`}
              >
                {field.label}
              </label>
              <input
                id={`admin-password-${field.name}`}
                type="password"
                autoComplete="off"
                aria-invalid={errors[field.name] ? 'true' : 'false'}
                className={INPUT_CLASS}
                {...register(field.name)}
              />
              <p className="text-caption text-gray-600">{field.hint}</p>
              {errors[field.name] && (
                <p className="flex items-center gap-1 text-caption text-danger" role="alert">
                  <AlertCircle className="h-3.5 w-3.5 shrink-0" aria-hidden="true" />
                  {errors[field.name]?.message}
                </p>
              )}
            </div>
          ))}

          {submitError && (
            <div data-testid="admin-password-error">
              <Alert variant="error" title="패스워드를 바꾸지 못했습니다">
                {submitError}
              </Alert>
            </div>
          )}

          <div className="flex justify-end gap-2">
            <Button
              type="button"
              variant="secondary"
              size="sm"
              onClick={() => {
                clearForm();
                setSubmitError(undefined);
              }}
              disabled={isSubmitting}
            >
              입력 지우기
            </Button>
            <Button
              type="submit"
              variant="primary"
              size="sm"
              loading={isSubmitting}
              disabled={!isValid || isSubmitting}
            >
              패스워드 바꾸기
            </Button>
          </div>
        </form>
      </div>

      <AdminSessionDialog
        open={dialogOpen}
        onClose={() => setDialogOpen(false)}
        onSubmit={session.open}
        isSubmitting={session.isOpening}
        error={session.error}
        ttlMinutesHint={ADMIN_SESSION_TTL_MINUTES_HINT}
        unlockTargetLabel="관리 기능"
      />
    </section>
  );
}

/**
 * 교체 실패 사유 — <b>권한 없음과 유효창 만료를 구분해 알리지 않는다</b>(둘 다 403).
 *
 * 구분하면 그 응답이 유효창 상태를 알려주는 신호가 된다. 어느 경우에도 입력한 값을 싣지 않는다.
 */
function describeChangeFailure(error: unknown): string {
  if (error instanceof ApiError) {
    if (error.status === 403) {
      return '관리자 확인이 필요합니다. 「관리자 확인」으로 다시 확인한 뒤 시도해 주세요.';
    }
    if (error.status === 401) {
      return '현재 패스워드를 다시 확인해 주세요.';
    }
    if (error.status === 429) {
      return '시도가 너무 잦습니다. 잠시 뒤에 다시 시도해 주세요.';
    }
    if (error.status === 400) {
      return error.userMessage || '새 패스워드가 규칙에 맞지 않습니다.';
    }
    return error.userMessage || '패스워드를 바꾸지 못했습니다.';
  }
  return '패스워드를 바꾸지 못했습니다.';
}
