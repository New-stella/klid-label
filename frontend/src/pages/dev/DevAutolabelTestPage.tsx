import { useEffect, useMemo, useRef, useState, type ChangeEvent, type FormEvent } from 'react';
import { Link } from 'react-router-dom';

import { Button } from '@/components/common/Button';
import { Card } from '@/components/common/Card';
import { Input } from '@/components/common/Input';
import { PageHeader } from '@/components/common/PageHeader';
import { Radio } from '@/components/common/Radio';
import { Spinner } from '@/components/common/Spinner';
import { EVENT_TYPES } from '@/constants/eventTypes';
import {
  extractBeMessage,
  useAutolabelTest,
} from '@/features/dev/hooks/useAutolabelTest';
import { useAutolabelStatus } from '@/features/dev/hooks/useAutolabelStatus';
import {
  EventTypeCd,
  PrvcType,
  STAGE_KEYS,
  type AutolabelTestMeta,
  type AutolabelTestResult,
  type EnabledStages,
  type StageKey,
} from '@/features/dev/types';

// 허용 확장자 (BE 와 동일) — `accept` 속성으로 1차 가드. BE 가 본 검증 수행.
const ACCEPT_MIME =
  'video/mp4,video/webm,video/quicktime,video/x-msvideo,.mp4,.webm,.mov,.avi';

/** SFR 6종 이벤트 옵션 (코드 → 한글). SoT `EVENT_TYPES` 에서 도출. */
const EVENT_OPTIONS: ReadonlyArray<{ value: EventTypeCd; label: string }> =
  EVENT_TYPES.map((e) => ({
    value: e.code,
    label: `${e.label} (${e.code})`,
  }));

const PRVC_OPTIONS: ReadonlyArray<{ value: PrvcType; label: string; hint: string }> = [
  { value: PrvcType.ANONY, label: 'ANONY (비식별 대상 아님)', hint: '원본만 저장' },
  { value: PrvcType.PRVC, label: 'PRVC (개인정보 포함)', hint: '비식별 처리 대상' },
  { value: PrvcType.PSDO, label: 'PSDO (가명 정보)', hint: '비식별 처리 대상' },
];

/** ISO-8601 (Instant) — capturedAt 직렬화. `datetime-local` 값은 timezone 미포함이므로 보정. */
function toIsoInstant(localDateTime: string): string {
  if (!localDateTime) return '';
  const d = new Date(localDateTime);
  if (Number.isNaN(d.getTime())) return '';
  return d.toISOString();
}

/** datetime-local input 의 초기값 — `YYYY-MM-DDTHH:mm` (브라우저 로컬). */
function nowLocalDateTime(): string {
  const d = new Date();
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

/** vmsClipId 기본값 — 중복 방지를 위해 timestamp 사용. */
function defaultVmsClipId(): string {
  return `test-${Date.now()}`;
}

interface FormState {
  vmsClipId: string;
  cctvId: string;
  eventTypeCd: EventTypeCd;
  localGovCd: string;
  prvcTypeCd: PrvcType;
  /** datetime-local 형식 (`YYYY-MM-DDTHH:mm`) — 제출 시 ISO 로 변환. */
  capturedAtLocal: string;
  /** 4단계 토글 — 기본 모두 ON. */
  enabledStages: EnabledStages;
}

function initialForm(): FormState {
  return {
    vmsClipId: defaultVmsClipId(),
    cctvId: 'CCTV-001',
    eventTypeCd: EventTypeCd.EVT_FALL,
    localGovCd: '11680',
    prvcTypeCd: PrvcType.ANONY,
    capturedAtLocal: nowLocalDateTime(),
    enabledStages: {
      [STAGE_KEYS.FRAME_EXTRACT]: true,
      [STAGE_KEYS.DEIDENTIFY]: true,
      [STAGE_KEYS.YOLO]: true,
      [STAGE_KEYS.SAM2]: true,
    },
  };
}

/** 토글 라벨 (한글). */
const STAGE_LABELS: ReadonlyArray<{ key: StageKey; label: string; hint: string }> = [
  { key: STAGE_KEYS.FRAME_EXTRACT, label: '프레임 추출', hint: 'FFmpeg 로 영상에서 1초 단위 프레임 추출' },
  { key: STAGE_KEYS.DEIDENTIFY, label: '비식별', hint: '외부 비식별 API 호출 (V2: 무조건)' },
  { key: STAGE_KEYS.YOLO, label: 'YOLO 자동 라벨', hint: '객체 탐지 + 트래킹' },
  { key: STAGE_KEYS.SAM2, label: 'SAM2 세그멘테이션', hint: 'YOLO bbox 힌트 기반 세그' },
];

/**
 * [개발/검수 전용] 영상 업로드 화면 (`/dev/autolabel-test`).
 *
 * REVIEWER 전용. 영상 파일 + 메타데이터 + 단계 토글 입력 →
 * BE `POST /api/v1/dev/autolabel-test` 호출 → rawSn 수신 후 영상 상세를 2초 간격으로 polling 하여
 * 파이프라인 진행 상황을 가시화한다. 라우터 path 와 endpoint URL 은 URL 호환성을 위해 유지.
 *
 * 보안:
 * - 파일 input `accept` 로 확장자 화이트리스트 1차 가드. BE 가 본 검증을 수행.
 * - 사용자 입력은 평문 `console.log` 금지. 에러 메시지는 BE 가 내려준 텍스트를 JSX 자동
 *   이스케이프로 표시 (XSS 방어).
 */
export function DevAutolabelTestPage() {
  const [file, setFile] = useState<File | null>(null);
  const [form, setForm] = useState<FormState>(initialForm);
  const [errorMessage, setErrorMessage] = useState<string | null>(null);
  const [result, setResult] = useState<AutolabelTestResult | null>(null);
  const fileInputRef = useRef<HTMLInputElement | null>(null);

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

  // 결과 영역 polling — rawSn 받은 이후, 영상 상태가 COMPLETED/FAILED 도달 전까지
  // statusQuery 가 직전 응답을 보유하므로 terminal 도달 후 enabled 가 false 로 떨어져도
  // videoDetail 캐시는 유지되어 화면 표시는 정상이다.
  const [terminalReached, setTerminalReached] = useState(false);
  const pollingEnabled = useMemo(() => {
    if (!result) return false;
    return !terminalReached;
  }, [result, terminalReached]);

  const statusQuery = useAutolabelStatus(result?.rawSn ?? null, pollingEnabled);
  const videoDetail = statusQuery.data ?? null;
  const reachedTerminal =
    videoDetail?.status === 'COMPLETED' || videoDetail?.status === 'FAILED';

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
    if (!form.eventTypeCd) return false;
    if (!form.localGovCd.trim()) return false;
    if (!form.capturedAtLocal) return false;
    return true;
  }, [file, form]);

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
      eventTypeCd: form.eventTypeCd,
      localGovCd: form.localGovCd.trim(),
      prvcTypeCd: form.prvcTypeCd,
      capturedAt: toIsoInstant(form.capturedAtLocal),
      enabledStages: { ...form.enabledStages },
    };
    mutation.mutate({ file, meta });
  };

  const toggleStage = (key: StageKey) => {
    setForm((s) => ({
      ...s,
      enabledStages: {
        ...s.enabledStages,
        [key]: !s.enabledStages[key],
      },
    }));
  };

  const handleReset = () => {
    setFile(null);
    setForm(initialForm());
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
        description="영상 파일과 메타데이터를 입력해 프레임 추출 + 오토라벨링 파이프라인을 실행합니다. (개발/검수 전용)"
      />

      <Card title="파일 + 메타 입력" padding="lg">
        <form className="space-y-5" onSubmit={handleSubmit} noValidate>
          <div className="flex flex-col gap-1">
            <label
              htmlFor="autolabel-test-file"
              className="text-body font-medium text-gray-700"
            >
              영상 파일 <span className="text-danger">*</span>
            </label>
            <input
              ref={fileInputRef}
              id="autolabel-test-file"
              type="file"
              accept={ACCEPT_MIME}
              onChange={handleFileChange}
              disabled={mutation.isPending}
              className="text-sm text-gray-700 file:mr-3 file:rounded-md file:border-0 file:bg-primary-50 file:px-3 file:py-1.5 file:text-sm file:font-medium file:text-primary-700 hover:file:bg-primary-100 disabled:opacity-60"
            />
            <span className="text-sub text-gray-500">
              허용 확장자: mp4 / webm / mov / avi · 최대 500MB
            </span>
            {file && (
              <span
                data-testid="autolabel-selected-file"
                className="text-sub text-gray-700"
              >
                선택: {file.name} ({(file.size / (1024 * 1024)).toFixed(2)} MB)
              </span>
            )}
          </div>

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
              hint="MNG_RESOURCE_CCTV 에 등록된 VMS_CCTV_ID"
              disabled={mutation.isPending}
              autoComplete="off"
              required
            />
          </div>

          <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
            <div className="flex flex-col gap-1">
              <label
                htmlFor="autolabel-test-event"
                className="text-body font-medium text-gray-700"
              >
                eventTypeCd <span className="text-danger">*</span>
              </label>
              <select
                id="autolabel-test-event"
                value={form.eventTypeCd}
                onChange={(e) =>
                  setForm((s) => ({
                    ...s,
                    eventTypeCd: e.target.value as EventTypeCd,
                  }))
                }
                disabled={mutation.isPending}
                className="h-10 rounded-lg border border-gray-300 bg-white px-3 text-body text-gray-900 outline-none focus-visible:ring-2 focus-visible:ring-primary-500"
              >
                {EVENT_OPTIONS.map((o) => (
                  <option key={o.value} value={o.value}>
                    {o.label}
                  </option>
                ))}
              </select>
            </div>
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
            <legend className="text-body font-medium text-gray-700">
              prvcTypeCd <span className="text-danger">*</span>
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
            <div className="flex flex-col gap-1">
              <label
                htmlFor="autolabel-test-capturedAt"
                className="text-body font-medium text-gray-700"
              >
                capturedAt <span className="text-danger">*</span>
              </label>
              <input
                id="autolabel-test-capturedAt"
                type="datetime-local"
                value={form.capturedAtLocal}
                onChange={(e) =>
                  setForm((s) => ({ ...s, capturedAtLocal: e.target.value }))
                }
                disabled={mutation.isPending}
                className="h-10 rounded-lg border border-gray-300 bg-white px-3 text-body text-gray-900 outline-none focus-visible:ring-2 focus-visible:ring-primary-500"
              />
              <span className="text-sub text-gray-500">
                촬영 시각 (브라우저 로컬 → BE 전송 시 ISO-8601 UTC 로 변환)
              </span>
            </div>
            <div className="flex flex-col gap-1">
              <span className="text-body font-medium text-gray-700">
                영상 길이 (durationSec)
              </span>
              <div className="flex h-10 items-center rounded-lg border border-dashed border-gray-300 bg-gray-50 px-3 text-sub text-gray-600">
                업로드 후 ffprobe 로 자동 추출됩니다
              </div>
              <span className="text-sub text-gray-500">
                영상 파일에서 BE 가 자동 산출 (1~7200초 범위 검증)
              </span>
            </div>
          </div>

          <fieldset className="flex flex-col gap-2">
            <legend className="text-body font-medium text-gray-700">
              실행 단계 선택
            </legend>
            <div
              role="group"
              aria-label="배치 단계 토글"
              className="grid grid-cols-1 gap-2 rounded-lg border border-gray-200 bg-white p-3 sm:grid-cols-2"
            >
              {STAGE_LABELS.map((s) => {
                const checked = form.enabledStages[s.key];
                return (
                  <label
                    key={s.key}
                    className="flex cursor-pointer items-start gap-2 rounded-md px-2 py-1.5 hover:bg-gray-50"
                    data-testid={`autolabel-stage-toggle-${s.key}`}
                  >
                    <input
                      type="checkbox"
                      checked={checked}
                      onChange={() => toggleStage(s.key)}
                      disabled={mutation.isPending}
                      className="mt-0.5 h-4 w-4 rounded border-gray-300 text-primary-600 focus:ring-primary-500"
                    />
                    <span className="flex flex-col">
                      <span className="text-body text-gray-800">{s.label}</span>
                      <span className="text-sub text-gray-500">
                        {s.hint} ({s.key})
                      </span>
                    </span>
                  </label>
                );
              })}
            </div>
            <span className="text-sub text-gray-500">
              선택하지 않은 단계는 건너뜁니다. 의존 단계가 OFF 라도 강제 차단하지 않으며,
              자연스럽게 빈 결과로 처리됩니다.
            </span>
          </fieldset>

          {errorMessage && (
            <div
              role="alert"
              data-testid="autolabel-error"
              className="rounded-md border border-danger/30 bg-red-50 px-3 py-2 text-sub text-danger"
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
              실행
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

      {result && (
        <Card title="실행 결과" padding="lg">
          <div className="space-y-3 text-sm">
            <div className="flex items-center gap-2">
              <span
                data-testid="autolabel-raw-sn"
                className="rounded bg-primary-50 px-2 py-0.5 font-mono text-primary-700"
              >
                rawSn = {result.rawSn}
              </span>
              <span className="text-gray-500">
                파이프라인 상태: {videoDetail?.status ?? result.pipelineStatus}
              </span>
              {!reachedTerminal && <Spinner size="sm" label="파이프라인 진행 중" />}
            </div>

            {videoDetail?.status === 'FAILED' && (
              <div
                role="alert"
                data-testid="autolabel-pipeline-failed"
                className="rounded-md border border-danger/40 bg-red-50 px-3 py-2 text-sub text-danger"
              >
                파이프라인 실행에 실패했습니다. BE 로그를 확인해주세요. (rawSn ={' '}
                {result.rawSn})
              </div>
            )}

            <div className="grid grid-cols-1 gap-2 text-gray-600 sm:grid-cols-3">
              <div>
                <span className="text-gray-400">파일 경로</span>
                <p className="font-mono break-all text-xs text-gray-700">
                  {result.savedFilePath}
                </p>
              </div>
              <div>
                <span className="text-gray-400">프레임 수</span>
                <p className="text-base font-semibold text-gray-900">
                  {videoDetail?.frameCount ?? 0}
                </p>
              </div>
              <div>
                <span className="text-gray-400">트리거 시각</span>
                <p className="text-xs text-gray-700">
                  {new Date(result.startedAt).toLocaleString()}
                </p>
              </div>
            </div>

            {videoDetail?.stages && videoDetail.stages.length > 0 && (
              <ul className="flex flex-wrap gap-2 text-xs">
                {videoDetail.stages.map((s) => (
                  <li
                    key={s.name}
                    className="rounded-full bg-gray-100 px-2.5 py-1 text-gray-700"
                  >
                    {s.name} · {s.status} ({s.progress}%)
                  </li>
                ))}
              </ul>
            )}

            <div className="pt-2">
              <Link
                to={`/video/${result.rawSn}`}
                className="text-sm font-medium text-primary-600 hover:underline"
              >
                영상 상세 보기 →
              </Link>
            </div>
          </div>
        </Card>
      )}
    </main>
  );
}

export default DevAutolabelTestPage;
