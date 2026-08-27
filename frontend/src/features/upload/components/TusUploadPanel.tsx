import {
  useEffect,
  useMemo,
  useRef,
  useState,
  type ChangeEvent,
  type FormEvent,
  type ReactNode,
} from 'react';

import { Field, FieldDescription, FieldLabel } from '@/components/common/Field';
import { Button } from '@/components/common/Button';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/common/Card';
import { FileInput } from '@/components/common/FileInput';
import { ProgressBar } from '@/components/common/ProgressBar';
import type { AutolabelTestMeta } from '@/features/dev/types';
import {
  EVENT_TYPE_MANUAL_OPTION,
  UploadRoute,
  canStartUpload,
  initialUnifiedUploadForm,
  multipartLimitMessage,
  resolveEventTypeCd,
  toImmediatePayload,
  toIngestPayload,
  type UnifiedUploadFormState,
} from '@/features/dev/components/unifiedUploadForm';
import { useTusUpload } from '@/features/upload/hooks/useTusUpload';
import { CollapsibleFieldGroup } from '@/features/upload/components/CollapsibleFieldGroup';
import {
  EventFieldset,
  IdentityFieldset,
  LocationFieldset,
  TechnicalMetaFieldset,
} from '@/features/upload/components/TusMetaFieldsets';
import {
  EventTypeField,
  PrivacyTypeField,
  UnsentNotice,
  UploadRouteField,
  VideoLengthNotice,
  type UploadEventTypeOption,
} from '@/features/upload/components/UploadRouteFields';

// 허용 확장자 — `accept` 는 고르기를 돕는 보조 안내일 뿐 신뢰 경계가 아니다. 실제 검증은 서버가 한다.
const ACCEPT_MIME = 'video/mp4,video/webm,video/quicktime,video/x-msvideo,.mp4,.webm,.mov,.avi';

export interface TusUploadPanelProps {
  /** 이벤트유형 옵션 — 마스터 조회 결과. 조회는 화면(페이지)이 소유하고 여기서는 값만 받는다. */
  eventOptions?: ReadonlyArray<UploadEventTypeOption>;
  /**
   * 파이프라인 즉시 실행 경로의 제출 — 그 경로의 요청은 페이지가 소유한 mutation 이 보낸다.
   * 미지정이면 그 경로 제출은 일어나지 않는다(폼만 단독으로 쓰는 경우).
   */
  onImmediateSubmit?: (args: { file: File; meta: AutolabelTestMeta }) => void;
  /** 즉시 실행 경로 전송 중 — 폼 전체를 잠그는 데 쓴다. */
  immediatePending?: boolean;
  /** 즉시 실행 경로의 오류 표시 슬롯 — 색상 클래스를 가진 마크업은 호출부가 소유한다. */
  errorSlot?: ReactNode;
  /** 초기화 — 폼·파일 외에 호출부가 들고 있는 결과·오류도 함께 비우게 알린다. */
  onReset?: () => void;
  /**
   * 관리자 단기 유효창 토큰 — 업로드 **시작**에만 실린다. [@design API-158] [@design ADR-046]
   *
   * 이 폼은 관리자 페이지 소속 화면(`/admin/uploads`)이 쓰므로 호출부가 값을 갖고 있다. 청크·취소
   * 에는 실리지 않아 유효창이 끝나도 진행 중이던 업로드가 끊기지 않는다.
   */
  adminSessionToken?: string;
  /**
   * 지금 관리자 유효창이 잠겨 있는가 — **판정은 호출부가 소유한다.**
   *
   * 이 폼이 남은 시간·만료 시각을 직접 들여다보지 않는 이유는 그러면 유효창 판정이 두 곳에 생겨
   * 한쪽만 갱신되기 때문이다. 폼은 «잠겼는가» 라는 결과만 받는다.
   */
  adminSessionLocked?: boolean;
  /**
   * 잠긴 상태에서 업로드를 시작하려 했을 때 — 호출부가 관리자 확인 창을 연다.
   *
   * 보내 봐야 403 이고 그 거부는 화면에서 「이유를 알 수 없는 실패」(또는 권한 문제)로 읽힌다.
   */
  onAdminSessionRequired?: () => void;
}

/**
 * 파일 업로드 폼 — **적재 경로 선택 + 두 경로가 공유하는 단일 입력 폼**. [@design SCREEN-027]
 *
 * <p>이 화면의 입력 폼은 한 벌뿐이다. 최상단 라디오로 고른 «적재 경로» 가 **보내는 곳과 그 뒤
 * 흐름만** 바꾸며 입력 필드 구성은 두 경로가 완전히 같다.
 *
 * <p><b>전송 계층은 경로별로 유지한다</b> — 두 경로의 전송 메커니즘과 payload 계약이 실제로 다르다.
 * 즉시 실행은 파일을 한 번에 통째로 보내고(그래서 500MB 한도를 받는다), 인입 재현은 청크로 나눠
 * 보내 끊긴 지점부터 이어 보낼 수 있다(일시정지·재개·취소가 성립한다). 폼 상태 → 경로별 payload
 * 변환만 갈리고(`unifiedUploadForm`), 전송을 하나로 합치지 않는다.
 *
 * <p><b>조용한 손실을 만들지 않는다</b> — 한쪽 계약에만 있는 항목은 «지금 고른 경로에서는 전송되지
 * 않는다» 고 화면이 알린다({@link UnsentNotice}). 그렇다고 입력칸을 잠그지는 않는다 — 경로에 따라
 * 잠기면 «두 경로가 같은 폼» 이라는 계약이 깨진다.
 *
 * <p>입력 부담을 낮추는 것이 이 폼의 설계 기준이다: 필수는 식별 정보 네 항목뿐이고 나머지 묶음은
 * 처음에 접혀 있으며, 기술메타는 비우면 서버가 파일에서 읽어 채운다. 영상 길이는 입력칸 자체가 없다.
 *
 * <p>보안: 파일명은 표시용이며 저장명은 서버가 정한다(CWE-22). 오류 문구는 서버가 내려준 텍스트를
 * JSX 자동 이스케이프로 표시한다(XSS 방어).
 *
 * <p>⚠ 파일·심볼명이 전송 방식(TUS) 시절 이름 그대로인 것은 의도다 — 개명은 별도 라운드로 미뤘다.
 */
export function TusUploadPanel({
  eventOptions = [],
  onImmediateSubmit,
  immediatePending = false,
  errorSlot,
  onReset,
  adminSessionToken,
  adminSessionLocked = false,
  onAdminSessionRequired,
}: TusUploadPanelProps = {}) {
  const [route, setRoute] = useState<UploadRoute>(UploadRoute.IMMEDIATE);
  const [file, setFile] = useState<File | null>(null);
  const [form, setForm] = useState<UnifiedUploadFormState>(initialUnifiedUploadForm);
  const fileInputRef = useRef<HTMLInputElement | null>(null);
  const upload = useTusUpload({ adminSessionToken });

  const isUploading = upload.status === 'uploading';
  const isBusy = isUploading || immediatePending;
  const percent = Math.round(upload.progress * 100);
  const isIngest = route === UploadRoute.INGEST;

  // 옵션 로드 완료 시 첫 유형으로 기본 선택 — 즉시 실행 경로는 이벤트유형이 필수라 비어 있으면
  // 사용자가 아무것도 안 했는데 업로드 버튼이 잠긴 것처럼 보인다. 사용자가 이미 고른 값(직접 입력
  // 포함)은 건드리지 않는다.
  useEffect(() => {
    if (eventOptions.length > 0 && !form.categoryKey) {
      setForm((s) => (s.categoryKey ? s : { ...s, categoryKey: eventOptions[0].categoryKey }));
    }
  }, [eventOptions, form.categoryKey]);

  // 전송할 이벤트유형코드는 한 곳에서 확정한다 — 두 경로가 같은 값을 쓴다.
  const eventTypeCd = useMemo(() => resolveEventTypeCd(form, eventOptions), [form, eventOptions]);
  const sizeLimitMessage = multipartLimitMessage(file, route);
  const canStart = canStartUpload({ file, form, route, eventTypeCd });
  /**
   * 잠김이 시작 버튼을 «비활성» 으로 만드는 경우 — 확인 창을 열 통로가 없을 때뿐이다.
   *
   * 통로가 있으면(관리자 페이지가 쓰는 정상 경로) 버튼을 살려 둔다. 눌렀을 때 확인 창이 뜨는 편이
   * 반응 없는 비활성 버튼보다 «무슨 일인지» 를 훨씬 잘 알린다(사용자 관리 화면의 저장 버튼과 같은
   * 관례). 반대로 통로가 없으면 눌러도 아무 일도 안 일어나므로 그때는 잠가서 사유를 드러낸다.
   */
  const startBlockedByLock = adminSessionLocked && !onAdminSessionRequired;

  const setValue = (key: keyof UnifiedUploadFormState, value: string) =>
    setForm((s) => ({ ...s, [key]: value }));

  const setField = (key: keyof UnifiedUploadFormState) => (e: ChangeEvent<HTMLInputElement>) =>
    setValue(key, e.target.value);

  const fieldsetProps = { form, onField: setField, onValue: setValue, disabled: isBusy };

  const handleFileChange = (e: ChangeEvent<HTMLInputElement>) => {
    setFile(e.target.files?.[0] ?? null);
  };

  // 이벤트유형 select — 직접 입력으로 넘어갈 때 이전 코드를 비운다. 남겨두면 "직접 입력"인데
  // 앞서 고른 그룹의 코드가 그대로 전송된다(조용한 오전송).
  const handleEventCategoryChange = (next: string) => {
    setForm((s) => ({
      ...s,
      categoryKey: next,
      evntTypeCd: next === EVENT_TYPE_MANUAL_OPTION ? '' : s.evntTypeCd,
    }));
  };

  const handleSubmit = (e: FormEvent<HTMLFormElement>) => {
    e.preventDefault();
    if (!file || !canStart) return;
    // 업로드 «시작» 은 두 경로 모두 관리자 유효창을 요구한다. 잠겨 있으면 요청을 보내기 전에
    // 확인 창을 먼저 연다 — 보내 봐야 403 이고, 그 거부는 화면에서 「권한이 없다」로 읽혀
    // 사용자가 역할 문제로 오인한다. 두 경로가 여기서 갈리면 같은 화면이 다르게 동작한다.
    //
    // ⚠ 청크 이어보내기(재개)·취소·진행위치 조회에는 이 요구를 얹지 않는다 — 대용량 영상은
    //   유효창(기본 10분)을 넘기기 마련이라, 거기까지 요구하면 구조적으로 올릴 수 없게 된다.
    //   그 비대칭은 `adminSession/__tests__/adminSessionHeaderScope.test.ts` 가 지킨다.
    if (adminSessionLocked) {
      onAdminSessionRequired?.();
      return;
    }
    if (isIngest) {
      const payload = toIngestPayload(form, { fileName: file.name, eventTypeCd });
      void upload.start(file, { filename: file.name }, payload).catch(() => undefined);
      return;
    }
    onImmediateSubmit?.({ file, meta: toImmediatePayload(form, eventTypeCd) });
  };

  // 재개는 기존 세션을 이어받는다 — 세션이 없으면 훅이 거부하므로 페이로드를 함께 넘겨
  // "세션 없이 재개"가 바디 없는 POST 로 새지 않게 한다.
  const handleResume = () => {
    if (!file) return;
    const payload = toIngestPayload(form, { fileName: file.name, eventTypeCd });
    void upload.resume(file, { filename: file.name }, payload).catch(() => undefined);
  };

  const handleCancel = () => {
    void upload.cancel();
    setFile(null);
    if (fileInputRef.current) fileInputRef.current.value = '';
  };

  const handleReset = () => {
    void upload.cancel();
    setFile(null);
    setForm(initialUnifiedUploadForm());
    if (fileInputRef.current) fileInputRef.current.value = '';
    onReset?.();
  };

  return (
    <Card>
      <CardHeader>
        <CardTitle>영상 파일 · 메타 입력</CardTitle>
      </CardHeader>
      <CardContent>
        <form className="space-y-5" onSubmit={handleSubmit} noValidate>
          <UploadRouteField value={route} onChange={setRoute} disabled={isBusy} />

          <Field>
            <FieldLabel>영상 파일 *</FieldLabel>
            <FileInput
              ref={fileInputRef}
              id="dev-upload-file"
              accept={ACCEPT_MIME}
              onChange={handleFileChange}
              disabled={isBusy}
              selectedFile={file}
              selectedFileTestId="dev-upload-selected-file"
            />
            <FieldDescription>
              {isIngest
                ? '허용 확장자: mp4 / webm / mov / avi · 청크로 나눠 올리므로 끊긴 지점부터 이어 보낼 수 있고, 통째 전송 한도(500MB)를 받지 않습니다(전체 크기 상한은 서버 설정을 따릅니다).'
                : '허용 확장자: mp4 / webm / mov / avi · 한 번에 통째로 보내므로 최대 500MB 까지만 가능합니다.'}
            </FieldDescription>
          </Field>

          {sizeLimitMessage && (
            <div
              role="alert"
              data-testid="dev-upload-size-limit"
              className="rounded-md border border-warning/30 bg-warning/10 px-3 py-2 text-sub text-warning-700"
            >
              {sizeLimitMessage}
            </div>
          )}

          <div className="space-y-3 rounded-lg border border-gray-200 p-4">
            <IdentityFieldset {...fieldsetProps} />
            <UnsentNotice group="출처유형" route={route} />
          </div>

          <div className="grid grid-cols-1 gap-4 md:grid-cols-2">
            <EventTypeField
              categoryKey={form.categoryKey}
              manualCode={form.evntTypeCd}
              options={eventOptions}
              resolvedCode={eventTypeCd}
              onCategoryChange={handleEventCategoryChange}
              onManualCodeChange={(v) => setValue('evntTypeCd', v)}
              disabled={isBusy}
            />
            <VideoLengthNotice />
          </div>

          <PrivacyTypeField
            value={form.prvcTypeCd}
            onChange={(v) => setForm((s) => ({ ...s, prvcTypeCd: v }))}
            route={route}
            disabled={isBusy}
          />

          <CollapsibleFieldGroup
            title="위치 · CCTV 제원"
            summary={<UnsentNotice group="위치 · CCTV 제원" route={route} />}
          >
            <LocationFieldset {...fieldsetProps} />
          </CollapsibleFieldGroup>

          <CollapsibleFieldGroup
            title="이벤트 · 관제일지"
            summary={<UnsentNotice group="이벤트 · 관제일지" route={route} />}
          >
            <EventFieldset {...fieldsetProps} />
          </CollapsibleFieldGroup>

          <CollapsibleFieldGroup
            title="영상 기술메타"
            summary={<UnsentNotice group="영상 기술메타" route={route} />}
          >
            <TechnicalMetaFieldset {...fieldsetProps} />
          </CollapsibleFieldGroup>

          {/* 진행률은 청크 전송(인입 재현)에서만 의미가 있다 — 통째 전송은 중간 진행이 없다. */}
          {isIngest && (
            <div className="space-y-1">
              <div className="flex items-center justify-between text-sub text-gray-600">
                <span>진행률</span>
                <span data-testid="tus-progress-pct">{percent}%</span>
              </div>
              <ProgressBar value={percent} />
              <span className="text-sub text-gray-400">
                상태: {upload.status}
                {upload.totalBytes > 0 &&
                  ` · ${(upload.uploadedBytes / (1024 * 1024)).toFixed(1)}MB / ${(upload.totalBytes / (1024 * 1024)).toFixed(1)}MB`}
              </span>
            </div>
          )}

          {errorSlot}

          {upload.error && (
            <div
              role="alert"
              data-testid="tus-error"
              className="rounded-md border border-danger/30 bg-danger/10 px-3 py-2 text-sub text-danger-700"
            >
              {upload.error}
            </div>
          )}

          {upload.status === 'completed' && (
            <div
              data-testid="tus-completed"
              className={
                upload.ingestStatus === 'PENDING_SCAN_DISABLED'
                  ? 'rounded-md border border-danger/30 bg-danger/10 px-3 py-2 text-sub text-danger-700'
                  : 'rounded-md border border-success/30 bg-success/10 px-3 py-2 text-sub text-success-700'
              }
            >
              {upload.ingestStatus === 'PENDING_SCAN_DISABLED'
                ? '업로드 완료 — 인입 대기 중이나 인입 스캔이 꺼져 있어 적재되지 않습니다. 서버 설정(authoring.control.training-scan.enabled)을 확인하세요.'
                : '업로드 완료 — 인입 대기 중입니다. 인입 스캔이 픽업하면 영상이 등록됩니다.'}
            </div>
          )}

          <div className="flex items-center gap-2">
            {!isUploading && upload.status !== 'paused' && (
              <Button
                type="submit"
                variant="primary"
                loading={immediatePending}
                disabled={!canStart || isBusy || startBlockedByLock}
              >
                업로드 시작
              </Button>
            )}
            {isIngest && isUploading && (
              <Button type="button" variant="secondary" onClick={upload.pause}>
                일시정지
              </Button>
            )}
            {isIngest && upload.status === 'paused' && (
              <Button type="button" variant="primary" onClick={handleResume} disabled={!file}>
                재개
              </Button>
            )}
            {(isUploading || upload.status === 'paused' || upload.status === 'error') && (
              <Button type="button" variant="secondary" onClick={handleCancel}>
                취소
              </Button>
            )}
            <Button type="button" variant="secondary" onClick={handleReset} disabled={isBusy}>
              초기화
            </Button>
          </div>
        </form>
      </CardContent>
    </Card>
  );
}

export default TusUploadPanel;
