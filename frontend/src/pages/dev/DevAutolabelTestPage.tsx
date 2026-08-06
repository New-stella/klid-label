import { useEffect, useMemo, useRef, useState, type ChangeEvent, type FormEvent } from 'react';

import { Button } from '@/components/common/Button';
import { Card } from '@/components/common/Card';
import { FileInput } from '@/components/common/FileInput';
import { Input } from '@/components/common/Input';
import { PageHeader } from '@/components/common/PageHeader';
import { Radio } from '@/components/common/Radio';
import { Select, type SelectOption } from '@/components/common/Select';
import { AutolabelResultCard } from '@/features/dev/components/AutolabelResultCard';
import {
  PRVC_OPTIONS,
  initialDevUploadForm,
  toIsoInstant,
  type DevUploadFormState,
} from '@/features/dev/components/devUploadForm';
import {
  extractBeMessage,
  useAutolabelTest,
} from '@/features/dev/hooks/useAutolabelTest';
import { useAutolabelStatus } from '@/features/dev/hooks/useAutolabelStatus';
import { useEventTypes } from '@/features/eventType/hooks';
import { TusUploadPanel } from '@/features/upload/components/TusUploadPanel';
import {
  type AutolabelTestMeta,
  type AutolabelTestResult,
} from '@/features/dev/types';

// 허용 확장자 (BE 와 동일) — `accept` 속성으로 1차 가드. BE 가 본 검증 수행.
const ACCEPT_MIME =
  'video/mp4,video/webm,video/quicktime,video/x-msvideo,.mp4,.webm,.mov,.avi';

/**
 * [개발/검수 전용] 영상 업로드 화면 (`/dev/autolabel-test`).
 *
 * REVIEWER 전용. dev 업로드는 운영 시나리오 1:1 고정 플로우다 — 업로드 → 비식별(무조건)
 * → MARKING_READY 정지. 단계 토글/마킹 직접 수행 분기는 없으며, 잔여 배치는 사용자가
 * 마킹 화면에서 마킹→완료할 때만 트리거된다. 업로드 후 영상 상세를 2초 간격으로 polling 하여
 * 상태를 가시화하며, 폴링 terminal 은 MARKING_READY(마킹 대기) 또는 FAILED 다.
 *
 * 보안:
 * - 파일 input `accept` 로 확장자 화이트리스트 1차 가드. BE 가 본 검증을 수행.
 * - 사용자 입력은 평문 `console.log` 금지. 에러 메시지는 BE 가 내려준 텍스트를 JSX 자동
 *   이스케이프로 표시 (XSS 방어).
 */
export function DevAutolabelTestPage() {
  const [file, setFile] = useState<File | null>(null);
  const [form, setForm] = useState<DevUploadFormState>(initialDevUploadForm);
  const [errorMessage, setErrorMessage] = useState<string | null>(null);
  const [result, setResult] = useState<AutolabelTestResult | null>(null);
  const fileInputRef = useRef<HTMLInputElement | null>(null);

  // 관제 이벤트 타입 카테고리 옵션(9종). 사용자는 카테고리를 고르고, 제출 시 대표 EV-코드를 보낸다.
  const eventTypesQuery = useEventTypes();
  const eventOptions = useMemo(
    () => eventTypesQuery.data ?? [],
    [eventTypesQuery.data],
  );

  // 옵션 로드 완료 시 첫 카테고리로 기본 선택 (선택 전 빈 값 → 제출 불가 가드).
  useEffect(() => {
    if (eventOptions.length > 0 && !form.categoryKey) {
      setForm((s) => ({ ...s, categoryKey: eventOptions[0].categoryKey }));
    }
  }, [eventOptions, form.categoryKey]);

  // 로딩 중에는 select 를 비워 두지 않고 안내 옵션 1건을 보여준다(빈 select 는 고장처럼 보인다).
  const eventSelectOptions = useMemo<SelectOption[]>(
    () =>
      eventOptions.length === 0
        ? [{ value: '', label: '이벤트 타입 로딩 중…' }]
        : eventOptions.map((o) => ({ value: o.categoryKey, label: o.label })),
    [eventOptions],
  );

  // 선택된 카테고리의 대표 EV-코드 — 제출 payload 의 eventTypeCd.
  const selectedEventCode = useMemo(() => {
    const opt = eventOptions.find((o) => o.categoryKey === form.categoryKey);
    return opt?.memberCodes[0] ?? '';
  }, [eventOptions, form.categoryKey]);

  const mutation = useAutolabelTest({
    onSuccess: (data) => {
      setResult(data);
      setErrorMessage(null);
    },
    onError: (err) => {
      setErrorMessage(
        extractBeMessage(err, '업로드에 실패했습니다. 입력값을 확인해주세요.'),
      );
    },
  });

  // 결과 영역 polling — rawSn 받은 이후, 영상 상태가 terminal(MARKING_READY/FAILED) 도달 전까지.
  // 고정 플로우라 파이프라인은 MARKING_READY 에서 정지하며, 비식별 실패 시 FAILED 로 끝난다.
  const [terminalReached, setTerminalReached] = useState(false);
  const pollingEnabled = useMemo(() => {
    if (!result) return false;
    return !terminalReached;
  }, [result, terminalReached]);

  const statusQuery = useAutolabelStatus(result?.rawSn ?? null, pollingEnabled);
  const videoDetail = statusQuery.data ?? null;
  // 성공 terminal: MARKING_READY — 고정 플로우의 정지점이다. dev 업로드 경로는 비식별
  // 완료 후 MARKING_READY 에서 멈추며, 잔여 배치는 사용자가 마킹 화면에서 마킹→완료할 때만
  // 진행된다. 따라서 업로드 경로에서 영상은 COMPLETED 로 가지 않는다. MARKING_READY 도달 시
  // 마킹 대기 안내를 노출하고 폴링을 종료한다(무한 스피너 방지).
  const reachedMarkingReady = videoDetail?.status === 'MARKING_READY';
  const reachedTerminal =
    reachedMarkingReady || videoDetail?.status === 'FAILED';

  // terminal 도달 시 polling 즉시 중단 (불필요한 트래픽 차단)
  useEffect(() => {
    if (reachedTerminal && !terminalReached) {
      setTerminalReached(true);
    }
  }, [reachedTerminal, terminalReached]);

  const isValid = useMemo(() => {
    if (!file) return false;
    if (!form.vmsClipId.trim()) return false;
    if (!form.cctvId.trim()) return false;
    // 카테고리 선택 + 대표 EV-코드 해석 가능해야 제출 허용.
    if (!form.categoryKey || !selectedEventCode) return false;
    if (!form.localGovCd.trim()) return false;
    if (!form.capturedAtLocal) return false;
    return true;
  }, [file, form, selectedEventCode]);

  const handleFileChange = (e: ChangeEvent<HTMLInputElement>) => {
    const next = e.target.files?.[0] ?? null;
    setFile(next);
    setErrorMessage(null);
  };

  const handleSubmit = (e: FormEvent<HTMLFormElement>) => {
    e.preventDefault();
    if (!file || !isValid) return;
    setErrorMessage(null);
    setResult(null);
    setTerminalReached(false);

    const meta: AutolabelTestMeta = {
      vmsClipId: form.vmsClipId.trim(),
      cctvId: form.cctvId.trim(),
      // 카테고리 → 대표 EV-코드 변환 후 전송 (관제 영상과 동일한 상세 코드).
      eventTypeCd: selectedEventCode,
      localGovCd: form.localGovCd.trim(),
      prvcTypeCd: form.prvcTypeCd,
      capturedAt: toIsoInstant(form.capturedAtLocal),
    };
    mutation.mutate({ file, meta });
  };

  const handleReset = () => {
    setFile(null);
    setForm(initialDevUploadForm());
    setErrorMessage(null);
    setResult(null);
    setTerminalReached(false);
    if (fileInputRef.current) {
      fileInputRef.current.value = '';
    }
  };

  return (
    <main className="space-y-6">
      <PageHeader
        title="영상 업로드"
        description="영상 파일과 메타데이터를 업로드하면 비식별 후 마킹 대기 상태로 진입합니다. 마킹 화면에서 마킹을 진행하면 잔여 배치가 실행됩니다. (개발/검수 전용)"
      />

      <Card title="파일 + 메타 입력" padding="lg">
        <form className="space-y-5" onSubmit={handleSubmit} noValidate>
          <FileInput
            ref={fileInputRef}
            id="autolabel-test-file"
            label="영상 파일 *"
            hint="허용 확장자: mp4 / webm / mov / avi · 최대 500MB"
            accept={ACCEPT_MIME}
            onChange={handleFileChange}
            disabled={mutation.isPending}
            selectedFile={file}
            selectedFileTestId="autolabel-selected-file"
          />

          <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
            <Input
              label="vmsClipId"
              value={form.vmsClipId}
              onChange={(e) =>
                setForm((s) => ({ ...s, vmsClipId: e.target.value }))
              }
              placeholder="test-xxxx"
              hint="영문/숫자/-/_ 1~64자"
              disabled={mutation.isPending}
              autoComplete="off"
              required
            />
            <Input
              label="cctvId"
              value={form.cctvId}
              onChange={(e) =>
                setForm((s) => ({ ...s, cctvId: e.target.value }))
              }
              placeholder="CCTV-001"
              hint="영상에 기록할 CCTV 식별자 · 영문/숫자/-/_ 1~64자 (사전 등록 불필요)"
              disabled={mutation.isPending}
              autoComplete="off"
              required
            />
          </div>

          <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
            <Select
              id="autolabel-test-event"
              label="이벤트 타입 *"
              hint={`관제 카테고리 선택 → 대표 EV-코드${
                selectedEventCode ? ` (${selectedEventCode})` : ''
              } 전송`}
              options={eventSelectOptions}
              value={form.categoryKey}
              onChange={(e) =>
                setForm((s) => ({ ...s, categoryKey: e.target.value }))
              }
              disabled={mutation.isPending || eventOptions.length === 0}
            />
            <Input
              label="localGovCd"
              value={form.localGovCd}
              onChange={(e) =>
                setForm((s) => ({ ...s, localGovCd: e.target.value }))
              }
              placeholder="11680"
              hint="숫자 1~10자리 (기본 11680=강남구)"
              disabled={mutation.isPending}
              inputMode="numeric"
              autoComplete="off"
              required
            />
          </div>

          <fieldset className="flex flex-col gap-2">
            {/* 필수 표시는 화면 전체를 라벨 문자열의 ` *` 로 통일한다(색만으로 정보 전달 금지). */}
            <legend className="text-body font-medium text-gray-700">
              prvcTypeCd *
            </legend>
            <div
              role="radiogroup"
              aria-label="개인정보 유형"
              className="flex flex-wrap gap-4 rounded-lg border border-gray-200 bg-white p-3"
            >
              {PRVC_OPTIONS.map((o) => (
                <Radio
                  key={o.value}
                  name="prvcTypeCd"
                  value={o.value}
                  label={
                    <span>
                      {o.label}
                      <span className="ml-1 text-sub text-gray-400">
                        — {o.hint}
                      </span>
                    </span>
                  }
                  checked={form.prvcTypeCd === o.value}
                  onChange={() =>
                    setForm((s) => ({ ...s, prvcTypeCd: o.value }))
                  }
                  disabled={mutation.isPending}
                />
              ))}
            </div>
          </fieldset>

          <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
            <Input
              id="autolabel-test-capturedAt"
              label="capturedAt *"
              hint="촬영 시각 (브라우저 로컬 → BE 전송 시 ISO-8601 UTC 로 변환)"
              type="datetime-local"
              value={form.capturedAtLocal}
              onChange={(e) =>
                setForm((s) => ({ ...s, capturedAtLocal: e.target.value }))
              }
              disabled={mutation.isPending}
              required
            />
            <div className="flex flex-col gap-1">
              <span className="text-body font-medium text-gray-700">
                영상 길이 (durationSec)
              </span>
              {/* 입력이 아니라 안내 — 값은 BE 가 ffprobe 로 산출한다. 높이는 Input(h-11)에 맞춘다. */}
              <div className="flex h-11 items-center rounded-lg border border-dashed border-gray-300 bg-gray-50 px-3 text-sub text-gray-600">
                업로드 후 ffprobe 로 자동 추출됩니다
              </div>
              <span className="text-sub text-gray-500">
                영상 파일에서 BE 가 자동 산출 (1~7200초 범위 검증)
              </span>
            </div>
          </div>

          {errorMessage && (
            <div
              role="alert"
              data-testid="autolabel-error"
              className="rounded-md border border-danger/30 bg-danger/10 px-3 py-2 text-sub text-danger"
            >
              {errorMessage}
            </div>
          )}

          <div className="flex items-center gap-2">
            <Button
              type="submit"
              variant="primary"
              loading={mutation.isPending}
              disabled={!isValid || mutation.isPending}
            >
              업로드
            </Button>
            <Button
              type="button"
              variant="secondary"
              onClick={handleReset}
              disabled={mutation.isPending}
            >
              초기화
            </Button>
          </div>
        </form>
      </Card>

      <TusUploadPanel />

      {result && (
        <AutolabelResultCard
          result={result}
          status={videoDetail?.status ?? null}
          frameCount={videoDetail?.frameCount ?? null}
          reachedTerminal={reachedTerminal}
          reachedMarkingReady={reachedMarkingReady}
        />
      )}
    </main>
  );
}

export default DevAutolabelTestPage;
