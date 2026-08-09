import { useEffect, useMemo, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import {
  AlertCircle,
  Info,
  RotateCcw,
  Search,
  X,
} from 'lucide-react';

import { Button } from '@/components/common/Button';
import { EmptyState } from '@/components/common/EmptyState';
import { ErrorState } from '@/components/common/ErrorState';
import { EventTypeBadge } from '@/components/common/EventTypeBadge';
import { PageHeader } from '@/components/common/PageHeader';
import { Pagination } from '@/components/common/Pagination';
import { Skeleton } from '@/components/common/Skeleton';
import { AugmentPromptFieldset } from '@/features/augment/components/AugmentPromptFieldset';
import { JobCard } from '@/features/augment/components/JobCard';
import { ProcessKindCard } from '@/features/augment/components/ProcessKindCard';
import { TargetResolutionSelect } from '@/features/augment/components/TargetResolutionSelect';
import { useRequestAugment } from '@/features/augment/hooks/useAugmentDecision';
import { useAugmentJobs } from '@/features/augment/hooks/useAugmentJobs';
import { validateAugmentPrompt } from '@/features/augment/promptValidation';
import {
  AUGMENT_PROMPT_FIELD_KEYS,
  PROCESS_KINDS,
  PROCESS_KIND_LABEL,
  createEmptyAugmentPrompt,
  createPromptPresetFor,
  isAugmentKind,
  type AugmentPromptFieldKey,
  type AugmentPromptFields,
  type ProcessKind,
} from '@/features/augment/types';
import { useResolutionDerivative } from '@/features/video/hooks/useResolutionDerivative';
import { useResolutionDerivativeStatus } from '@/features/video/hooks/useResolutionDerivativeStatus';
import { useVideoDetail } from '@/features/video/hooks/useVideoDetail';
import { useVideos } from '@/features/video/hooks/useVideos';
import {
  DERIVATIVE_STATUS,
  RESOLUTION_PRESETS,
  RESOLUTION_PRESET_LABEL,
  isDerivativeSettled,
  type DerivativeStatus,
  type ResolutionPreset,
} from '@/features/video/types';
import { ApiError } from '@/lib/api/errors';
import { KRDS_FOCUS } from '@/lib/focusRing';
import { useUiStore } from '@/stores/useUiStore';

const PAGE_SIZE = 20;

interface DerivativeStatusView {
  text: string;
  className: string;
}

/**
 * 파생영상 확정 상태 → 화면 표기.
 *
 * 값 집합의 진실원은 `features/video/types(DERIVATIVE_STATUS)` 이고 여기서 새 상태를 만들지
 * 않는다. `Record<DerivativeStatus, …>` 라 상태가 늘면 컴파일 단계에서 누락이 드러난다.
 * `CREATED` 는 **예약만 된 상태**이지 완료가 아니라는 점이 표기에 드러나야 한다.
 */
const DERIVATIVE_STATUS_VIEW: Record<DerivativeStatus, DerivativeStatusView> = {
  [DERIVATIVE_STATUS.CREATED]: {
    text: '생성 대기',
    className: 'bg-gray-100 text-gray-600',
  },
  [DERIVATIVE_STATUS.IN_PROGRESS]: {
    text: '생성 중',
    className: 'bg-info/10 text-info-700',
  },
  [DERIVATIVE_STATUS.COMPLETED]: {
    text: '생성 완료',
    className: 'bg-success/10 text-success-700',
  },
  [DERIVATIVE_STATUS.FAILED]: {
    text: '생성 실패',
    className: 'bg-danger/10 text-danger-700',
  },
};

/** 미지 상태 코드(구/신 BE 혼재)는 코드 그대로 노출한다 — `resLabel` 과 같은 규칙. */
const derivativeStatusView = (status: DerivativeStatus): DerivativeStatusView =>
  (DERIVATIVE_STATUS_VIEW as Record<string, DerivativeStatusView | undefined>)[
    status
  ] ?? { text: status, className: 'bg-gray-100 text-gray-600' };

interface VideoFilterValues {
  q: string;
  eventType: string;
}

const DEFAULT_FILTERS: VideoFilterValues = {
  q: '',
  eventType: '',
};

/**
 * SCR-AUG-001 데이터 증강 요청 (`/augment`) — 통합 단일 선택 UI.
 *
 * UI/UX §4-12 + V1.x (Phase 1 통합 재구성):
 * - 처리 종류 카드 4개(겨울/야간/우천/해상도 변경)를 radiogroup 으로 **단일 선택**.
 *   증강 3종은 외부 위탁 잡, 해상도 변경(RESOLUTION)은 저작도구 직접 수행(SFR-06-03).
 * - 해상도 변경 종류를 고른 경우에만 생성할 해상도(1080P/720P/480P) 선택 UI 노출.
 *   3종 고정 기능이라 기본 전체 선택(다중)이며, 선택된 해상도별로 새 파생영상(RAW_SN)을
 *   만들어 검수 파이프라인(검수 대기)에 넣는다(SFR-06-03, export 프레임셋 아님).
 * - 대상 영상은 **1건 단일 선택**(라디오).
 * - 종류를 바꾸면 생성할 해상도(전체 3종)·결과 상태 초기화.
 * - **검수 완료된 영상만 선택 가능** — 비활성/검색·이벤트 필터·페이징.
 * - 최근 요청 이력 잡 카드 6건 그리드.
 *
 * - 증강 3종을 고르면 **생성 조건(프롬프트) 5필드** 입력 블록이 노출된다(BE 필수 계약).
 *
 * 보안:
 * - REVIEWER 역할 검증 (라우터 + BE).
 * - videoId 는 number 로 변환 후 전달 — 비숫자 입력 차단.
 * - kind/preset 은 정의된 상수 집합(allowlist)으로만 좁힘 — 임의 문자열 분기 차단.
 * - 프롬프트는 외부 생성형 AI 로 나가는 자유 입력이라 BE 와 **같은 기준**으로 미리 검증한다
 *   (공백·보이지 않는 문자·50자 초과 차단, `validateAugmentPrompt`).
 *
 * <h3>연타(중복 제출) 방어는 이 화면이 유일한 방어선이다 (Critical)</h3>
 * <p>BE 의 중복 요청 차단(부분 유니크 인덱스·409 가드·속도 제한)은 2026-07-31 사용자 확정으로
 * **전면 해제**됐다 — 증강 결과 이미지는 요청마다 다르게 생성되므로 같은 영상·같은 종류로 다시
 * 요청하는 것이 정상 동선이기 때문이다. 그 대신 **오조작(연타)** 이 그대로 통과하면 클릭 수만큼
 * 파생 영상·NAS 사본·프레임셋·외부 벤더 비용이 곱해진다. 그래서 여기서 두 겹으로 막는다:
 * <ol>
 *   <li><b>동기 락(`submitLockRef`)</b> — 클릭 핸들러 진입 즉시 잠근다. 리렌더를 기다리지 않으므로
 *       같은 tick 에 들어온 연속 클릭도 첫 건만 통과한다.</li>
 *   <li><b>버튼 비활성(`isPendingAny`)</b> — 요청 진행 중에는 버튼 자체를 누를 수 없다(시각적 피드백).</li>
 * </ol>
 * <p>락은 성공/실패와 무관하게 `onSettled` 에서 푼다 — <b>의도적 재요청은 막지 않는다</b>. 같은 이유로
 * 성공 후에도 선택·프롬프트를 지우지 않는다(같은 조건으로 곧바로 다시 요청할 수 있어야 한다).
 */
export function AugmentRequestPage() {
  const navigate = useNavigate();
  const pushToast = useUiStore((s) => s.pushToast);

  const [selectedKind, setSelectedKind] = useState<ProcessKind | null>(null);
  const [selectedVideoId, setSelectedVideoId] = useState<number | null>(null);
  // 해상도 변경은 3종 고정 기능이라 기본 전체 선택(다중). 불변성 위해 스프레드 복사.
  const [selectedPresets, setSelectedPresets] = useState<ResolutionPreset[]>([
    ...RESOLUTION_PRESETS,
  ]);

  // 생성 조건(프롬프트) 5필드 — 외부 위탁 증강 전용. 원문을 그대로 들고 있다가 전송 직전 정규화한다.
  const [prompt, setPrompt] = useState<AugmentPromptFields>(createEmptyAugmentPrompt);
  // 사용자가 건드린 필드만 오류를 표시한다(진입하자마자 빨간 글씨가 뜨지 않게).
  const [promptTouched, setPromptTouched] = useState<
    Partial<Record<AugmentPromptFieldKey, boolean>>
  >({});
  /**
   * 사용자가 **값을 직접 고친** 필드 — 종류 변경 시 프리필이 덮어쓰지 않을 대상이다.
   *
   * `promptTouched` 와 분리한 이유가 둘 있다.
   * <ul>
   *   <li><b>touched 는 blur 만으로도 켜진다</b> — 입력칸을 지나가기만 해도 켜지므로
   *       "사용자가 정한 값"의 근거가 되지 못한다. touched 의 책임은 오류 표시 시점이다.</li>
   *   <li><b>빈 값 여부로는 판정할 수 없다</b> — 프리필된 값을 사용자가 **의도적으로 지운**
   *       상태와 애초에 비어 있던 상태가 값만으로는 구분되지 않는다. 값으로 판정하면
   *       종류를 바꿀 때 사용자가 지운 값이 되살아난다.</li>
   * </ul>
   * 따라서 "편집 행위가 있었는가"를 별도로 기록한다(빈 문자열로 지운 것도 편집이다).
   */
  const [promptEdited, setPromptEdited] = useState<
    Partial<Record<AugmentPromptFieldKey, boolean>>
  >({});
  const promptValidation = useMemo(() => validateAugmentPrompt(prompt), [prompt]);

  const handlePromptChange = (key: AugmentPromptFieldKey, value: string) => {
    // 불변성: 새 객체 생성 (mutation 금지).
    setPrompt((prev) => ({ ...prev, [key]: value }));
    setPromptTouched((prev) => ({ ...prev, [key]: true }));
    setPromptEdited((prev) => ({ ...prev, [key]: true }));
  };

  const handlePromptBlur = (key: AugmentPromptFieldKey) => {
    setPromptTouched((prev) => ({ ...prev, [key]: true }));
  };

  // 필터: localFilters (입력 중), filters (적용된 값)
  const [localFilters, setLocalFilters] =
    useState<VideoFilterValues>(DEFAULT_FILTERS);
  const [filters, setFilters] = useState<VideoFilterValues>(DEFAULT_FILTERS);
  const [page, setPage] = useState(0);

  // 검수 완료(승인) 영상만 — V1.x SFR-07 가드
  // - dataSttsCd=COMPLETED: 배치 파이프라인 완료 (LS_DATA_RAW.DATA_STTS_CD)
  // - reviewStatusCd=APPROVED: 검수 승인 완료 (LS_RAW_DATA_STATUS.DATA_STTS_CD)
  const { data: videosPage, isLoading: videosLoading } = useVideos({
    page,
    size: PAGE_SIZE,
    dataSttsCd: 'COMPLETED',
    reviewStatusCd: 'APPROVED',
  });
  const pageContent = videosPage?.content ?? [];
  // 안전 가드: BE 응답에 배치 미완료 영상이 섞여도 화면 노출은 COMPLETED 만 허용 (SFR-07).
  const approvedPage = useMemo(
    () => pageContent.filter((v) => v.status === 'COMPLETED'),
    [pageContent],
  );

  // 이벤트 옵션 — 현재 페이지 승인 영상의 eventName(한글 표시명)에서 unique 수집.
  // 하드코딩 코드 목록을 폐지하고 실제 데이터 기반으로 노출 → 필터(eventName 비교)와 정합.
  const eventTypeOptions = useMemo(
    () =>
      Array.from(
        new Set(approvedPage.map((v) => v.eventName).filter((n): n is string => !!n)),
      ),
    [approvedPage],
  );

  // 클라이언트 필터 (현재 페이지 한정 — BE 검색 강화는 후속 PR)
  const pagedVideos = useMemo(() => {
    let result = approvedPage;
    if (filters.q) {
      const q = filters.q.toLowerCase();
      result = result.filter(
        (v) =>
          v.cctvName.toLowerCase().includes(q) ||
          String(v.id).includes(q) ||
          (v.eventName ?? '').toLowerCase().includes(q),
      );
    }
    if (filters.eventType) {
      result = result.filter((v) => v.eventName === filters.eventType);
    }
    return result;
  }, [approvedPage, filters]);

  // BE 페이지 메타데이터
  const totalElements = videosPage?.totalElements ?? 0;
  const totalPages = Math.max(1, videosPage?.totalPages ?? 1);
  const currentPage = videosPage?.number ?? page;

  // 필터 적용 시 페이지 리셋
  useEffect(() => {
    setPage(0);
  }, [filters]);

  const { data, isLoading, error } = useAugmentJobs({ page: 0, size: 6 });
  const { mutate, isPending } = useRequestAugment({
    // 성공해도 선택·생성 조건을 지우지 않는다 — 같은 조건 재요청이 정상 동선이기 때문
    // (중복 차단이 BE 에서 해제된 뒤로 "다시 요청" 은 오류가 아니라 기능이다).
    //
    // ⚠ 응답의 `jobId` 로 이동하지 않는다 (Critical — 다시 되돌리지 말 것).
    //   BE `AugmentRequestService#jobIdSeq` 는 "placeholder jobId 시퀀스"(인스턴스 기동 시각 ms 기반
    //   AtomicLong)라 어떤 엔티티의 식별자도 아니다. 반면 결과 화면이 호출하는
    //   `GET /v1/augments/{jobId}/result` 의 경로변수는 **원본 영상 RAW_SN**
    //   (`AugmentResultViewService` → `findByOriginalRawSn`)이고, 잡 카드(`JobCard`)가 여는 경로도
    //   같은 축이다. placeholder 값으로 이동하면 결과 0건 + "증강 처리 중" 배너에 영구 고착된다.
    //   요청은 단건 계약(영상 1건 × 종류 1개, BE `requireSingleSelection`)이므로 요청 본문의
    //   videoIds[0] 이 곧 이동 대상 RAW_SN 이다.
    onSuccess: (_resp, variables) => {
      pushToast({
        variant: 'success',
        message: '증강 요청이 접수되었습니다 — 처리 현황에서 확인하세요',
      });
      const targetRawSn = variables.videoIds[0];
      if (typeof targetRawSn === 'number' && Number.isFinite(targetRawSn)) {
        navigate(`/augment/result/${targetRawSn}`);
      }
    },
    onError: (err) => {
      // 해상도 분기(resolutionErrorMessage)와 동일 원칙 — BE 가 내려준 사용자 메시지를
      // 노출한다. 고정 문자열은 실패 사유(중복 불가 등 개별 검증 실패)를 가린다.
      const message = err instanceof ApiError ? err.userMessage : '증강 요청 실패';
      pushToast({ variant: 'error', message });
    },
  });

  // 해상도 변경(SFR-06-03) — 저작도구 직접 수행: 목표 해상도별 새 파생영상(RAW_SN) 생성.
  // rawSn 은 mutate 시점에 canSubmit 가드로 보장. selectedVideoId 가 null 이면 0 을 넘기되
  // canSubmit 가 false 라 실제 호출은 차단된다.
  const resolutionDerivative = useResolutionDerivative(selectedVideoId ?? 0);
  const {
    data: resolutionResult,
    error: resolutionError,
    reset: resetResolution,
  } = resolutionDerivative;

  // ★파생 깊이는 1 로 고정된다 — 파생영상(증강·해상도 결과물)에서는 어떤 파생도 만들 수 없고
  //   BE 가 400 으로 **영구히** 거부한다(재시도 여지가 없는 조건이다). 그 사실을 제출 후에야
  //   알리면 사용자는 조건을 다 채우고 나서야 막힌다 — 영상 상세로 미리 판정해 카드를 비활성화하고
  //   사유를 툴팁으로 알린다. 400 안내는 화면이 파생 여부를 모를 때의 안전망으로만 남는다.
  const { data: selectedVideoDetail } = useVideoDetail(selectedVideoId);
  const isDerivativeVideo = selectedVideoDetail?.derivative === true;
  const derivativeBlockReason = isDerivativeVideo
    ? '증강·해상도 변환으로 만든 파생영상이라 다시 처리를 요청할 수 없습니다.'
    : undefined;

  const isResolution = selectedKind === 'RESOLUTION';
  // 외부 위탁 증강(WINTER/NIGHT/RAIN) 선택 여부 — 생성 조건 입력이 필요한 경로.
  const isAugmentRequest = selectedKind !== null && isAugmentKind(selectedKind);
  const isPendingAny = isPending || resolutionDerivative.isPending;

  // ★확정 결과는 생성 응답(POST)이 아니라 **조회 폴링**으로만 드러난다.
  //   생성 응답의 `CREATED` 는 "예약됨" 일 뿐이라, 그 뒤의 확정 완료·**확정 실패**가 종전에는
  //   어느 화면에도 나타나지 않았다. 실패가 사용자에게 보이는 것이 이 배선의 핵심이다.
  //   폴링 시작/종료 규칙은 훅(`useResolutionDerivativeStatus`)이 단독으로 소유한다 —
  //   여기서 다시 구현하지 않는다(복제하면 한쪽만 갱신돼 어긋난다).
  //   해상도 종류를 고른 동안만 조회한다(증강 3종 경로는 이 축과 무관하다).
  const { data: derivativeStatus } = useResolutionDerivativeStatus(selectedVideoId, {
    enabled: isResolution && !isDerivativeVideo,
  });
  const statusDerivatives = derivativeStatus?.derivatives ?? [];
  // 진행/실패 판정은 종료 규칙의 단일 원천(isDerivativeSettled)을 그대로 쓴다.
  const statusPending = statusDerivatives.filter((d) => !isDerivativeSettled(d.status));
  const statusFailed = statusDerivatives.filter(
    (d) => d.status === DERIVATIVE_STATUS.FAILED,
  );

  /**
   * 제출 동기 락 — 연타 1차 방어. 상태(리렌더)보다 먼저 잠긴다.
   * 상태 기반 비활성만으로는 같은 tick 에 들어온 연속 클릭을 막지 못한다.
   */
  const submitLockRef = useRef(false);
  const releaseSubmitLock = () => {
    submitLockRef.current = false;
  };

  // radiogroup 로빙 tabindex/화살표 탐색용 카드 ref (a11y WCAG 4.1.2).
  const kindCardRefs = useRef<(HTMLButtonElement | null)[]>([]);

  // 종류 변경: 생성할 해상도(전체 3종)·결과 상태 초기화 (AC4) + 생성 조건 프리필.
  //
  // 프리필 규칙 — 시스템이 채운 값만 종류를 따라가고, 사용자가 고친 값은 그대로 둔다.
  // 종류가 생성 조건에 반영되지 않으면 증강 3종이 같은 요청이 되어 종류를 나눈 의미가 없어진다.
  // 반대로 사용자 입력을 덮으면 검수자가 조건을 조절할 수 있어야 한다는 정책을 깬다.
  const handleSelectKind = (kind: ProcessKind) => {
    setSelectedKind(kind);
    setSelectedPresets([...RESOLUTION_PRESETS]);
    resetResolution();
    // 해상도 변경은 프롬프트 자체가 없다 — 여기서 값을 비우면 증강으로 되돌아왔을 때
    // 사용자가 입력해 둔 조건이 사라진다.
    if (!isAugmentKind(kind)) return;
    const preset = createPromptPresetFor(kind);
    setPrompt((prev) => {
      const next = { ...prev };
      AUGMENT_PROMPT_FIELD_KEYS.forEach((key) => {
        // 사용자가 손댄 필드는 값(빈 문자열 포함)을 그대로 보존한다.
        if (!promptEdited[key]) next[key] = preset[key];
      });
      return next;
    });
  };

  // 화살표 키로 카드 간 이동 + 선택 + 포커스 이동 (로빙 tabindex 패턴).
  const handleKindKeyDown = (
    e: React.KeyboardEvent<HTMLButtonElement>,
    index: number,
  ) => {
    let nextIndex: number | null = null;
    if (e.key === 'ArrowRight' || e.key === 'ArrowDown') {
      nextIndex = (index + 1) % PROCESS_KINDS.length;
    } else if (e.key === 'ArrowLeft' || e.key === 'ArrowUp') {
      nextIndex = (index - 1 + PROCESS_KINDS.length) % PROCESS_KINDS.length;
    }
    if (nextIndex === null) return;
    e.preventDefault();
    handleSelectKind(PROCESS_KINDS[nextIndex]);
    kindCardRefs.current[nextIndex]?.focus();
  };

  // 미선택 상태면 첫 카드가 tab 진입점(0), 선택 상태면 선택된 카드만 0.
  const kindTabIndex = (kind: ProcessKind, index: number) =>
    selectedKind === null ? (index === 0 ? 0 : -1) : selectedKind === kind ? 0 : -1;

  const selectVideo = (id: number) => {
    setSelectedVideoId((prev) => (prev === id ? null : id));
  };

  const handleApplyFilters = (e: React.FormEvent) => {
    e.preventDefault();
    setFilters(localFilters);
  };

  const handleResetFilters = () => {
    setLocalFilters(DEFAULT_FILTERS);
    setFilters(DEFAULT_FILTERS);
  };

  // 제출 가능 조건 — 종류·영상 선택 필수, 해상도 종류면 생성할 해상도 1개 이상 선택,
  // 증강 종류면 생성 조건 5필드가 BE 기준으로 유효해야 한다(사용자가 400 을 보지 않게 미리 차단).
  const canSubmit =
    selectedKind !== null &&
    selectedVideoId !== null &&
    // 파생영상은 BE 가 400 으로 영구 거부한다 — 제출 자체를 막는다.
    !isDerivativeVideo &&
    (isResolution ? selectedPresets.length > 0 : promptValidation.ok) &&
    !isPendingAny;

  // 실행 분기 (R4): 증강 3종은 위탁 잡 요청(/augments/request),
  // 해상도 변경은 저작도구 직접 수행(/videos/{rawSn}/resolution → 파생영상 생성).
  // kind/preset 은 allowlist 상수로만 좁혀(isAugmentKind/RESOLUTION_PRESETS) 임의 분기 차단.
  const handleSubmit = () => {
    // 연타 1차 방어(동기 락) — 버튼 비활성 상태가 반영되기 전의 연속 클릭을 여기서 끊는다.
    if (submitLockRef.current || isPendingAny) return;
    if (!canSubmit || selectedKind === null || selectedVideoId === null) return;
    if (isAugmentKind(selectedKind)) {
      // 프롬프트 재검증(fail-closed) — 버튼 비활성만 믿지 않는다.
      const promptValue = promptValidation.value;
      if (promptValue === null) {
        // 모든 필드를 touched 로 표시해 어디가 문제인지 즉시 보이게 한다.
        setPromptTouched(
          Object.fromEntries(AUGMENT_PROMPT_FIELD_KEYS.map((k) => [k, true])),
        );
        return;
      }
      // 증강: 성공 시 결과화면 네비게이션(useRequestAugment onSuccess).
      submitLockRef.current = true;
      mutate(
        {
          videoIds: [selectedVideoId],
          types: [selectedKind],
          // 종류(types)는 프롬프트에서 파생하지 않는다 — 사용자가 카드로 고른 값 그대로다.
          prompt: promptValue,
        },
        { onSettled: releaseSubmitLock },
      );
    } else if (selectedPresets.length > 0) {
      // 해상도: 성공 시 결과 카드 inline 표시(아래 resolutionResult) + 검수 대기 안내 토스트.
      submitLockRef.current = true;
      resolutionDerivative.mutate(selectedPresets, {
        onSettled: releaseSubmitLock,
        onSuccess: (data) => {
          const created = data.derivatives.filter(
            (d) => d.status === 'CREATED',
          ).length;
          const failed = data.derivatives.length - created;
          pushToast({
            variant: failed > 0 ? 'warning' : 'success',
            message:
              failed > 0
                ? `해상도 파생영상 ${created}건 생성 — 검수 대기 (${failed}건 실패)`
                : `해상도 파생영상 ${created}건 생성됨 — 검수 대기`,
          });
        },
      });
    }
  };

  const resolutionErrorMessage =
    resolutionError instanceof ApiError
      ? resolutionError.userMessage
      : resolutionError
        ? '해상도 변경에 실패했습니다.'
        : null;

  // 파생영상 결과 요약 (부분/전부 실패 안내용).
  const createdDerivatives =
    resolutionResult?.derivatives.filter((d) => d.status === 'CREATED') ?? [];
  const failedDerivatives =
    resolutionResult?.derivatives.filter((d) => d.status === 'FAILED') ?? [];

  // goalResCd → 표시 라벨 (화이트리스트 매핑, 미지 코드는 코드 그대로 노출).
  const resLabel = (goalResCd: string) =>
    (RESOLUTION_PRESET_LABEL as Record<string, string>)[goalResCd] ?? goalResCd;

  return (
    <section
      className="flex flex-col gap-6 pb-28"
      data-testid="augment-request-page"
    >
      <PageHeader
        title="데이터 증강 요청"
        description="검수 완료(승인) 영상 1건에 처리 종류(겨울/야간/우천 증강 또는 해상도 변경) 하나를 선택해 요청합니다."
      />

      {/* SFR-07 안내 */}
      <div className="flex items-start gap-2 rounded-lg border border-info/30 bg-info/10 px-3 py-2.5">
        <Info size={14} className="mt-0.5 shrink-0 text-info" aria-hidden />
        <p className="text-caption text-info-700">
          처리 요청은 검수 완료(승인)된 영상만 가능합니다. 미승인 영상은 목록에 표시되지 않습니다.
        </p>
      </div>

      {/* Step 1: 처리 종류 선택 (단일 선택 카드 4개) */}
      <section className="space-y-4" data-testid="process-kind-step">
        <div className="flex items-center gap-2">
          <span className="flex h-7 w-7 shrink-0 items-center justify-center rounded-full bg-primary-600 text-label font-bold text-white">
            1
          </span>
          <h2 className="text-title-sm font-semibold text-gray-800">처리 종류 선택</h2>
          {selectedKind && (
            <span className="inline-flex items-center rounded-full bg-info/10 px-2 py-0.5 text-label font-medium text-info-700">
              {PROCESS_KIND_LABEL[selectedKind]}
            </span>
          )}
        </div>

        <div
          role="radiogroup"
          aria-label="처리 종류"
          className="grid grid-cols-2 gap-4 md:grid-cols-4"
          data-testid="process-kind-list"
        >
          {PROCESS_KINDS.map((kind, index) => (
            <ProcessKindCard
              key={kind}
              ref={(el) => {
                kindCardRefs.current[index] = el;
              }}
              kind={kind}
              selected={selectedKind === kind}
              tabIndex={kindTabIndex(kind, index)}
              disabled={isPendingAny || isDerivativeVideo}
              disabledReason={derivativeBlockReason}
              onSelect={() => handleSelectKind(kind)}
              onKeyDown={(e) => handleKindKeyDown(e, index)}
            />
          ))}
        </div>

        {isDerivativeVideo && (
          <p
            role="status"
            data-testid="derivative-block-notice"
            className="flex items-center gap-1.5 text-caption text-warning-700"
          >
            <AlertCircle size={13} aria-hidden />
            {derivativeBlockReason}
          </p>
        )}

        {selectedKind === null && !isDerivativeVideo && (
          <p className="flex items-center gap-1.5 text-caption text-warning-700">
            <AlertCircle size={13} aria-hidden />
            처리 종류를 하나 선택하세요.
          </p>
        )}

        {/* 증강 3종 선택 시에만 생성 조건(프롬프트) 입력 노출 — 해상도 변경은 외부 위탁이 아니라 불필요 */}
        {isAugmentRequest && (
          <AugmentPromptFieldset
            value={prompt}
            errors={promptValidation.errors}
            touched={promptTouched}
            onChange={handlePromptChange}
            onBlur={handlePromptBlur}
            disabled={isPendingAny}
          />
        )}

        {/* 해상도 변경 종류 선택 시에만 생성할 해상도 UI 노출 (AC3) */}
        {isResolution && (
          <div className="space-y-2" data-testid="target-resolution-block">
            <p className="text-label font-medium text-gray-600">
              생성할 해상도 (파생영상)
            </p>
            <TargetResolutionSelect
              value={selectedPresets}
              onChange={(presets) => {
                setSelectedPresets(presets);
                resetResolution();
              }}
              disabled={resolutionDerivative.isPending}
            />

            {/* 해상도 변경 실행 결과 (AC5) — 검수 대기 파생영상 목록 */}
            {resolutionErrorMessage && (
              <p role="alert" className="text-body-md text-danger-700">
                {resolutionErrorMessage}
              </p>
            )}
            {resolutionResult && (
              <div
                role="status"
                data-testid="resolution-derivative-result"
                className="space-y-2 rounded-md border border-success/30 bg-success/10 px-3 py-2.5 text-body-md"
              >
                <p className="font-medium text-success-700">
                  파생영상 {createdDerivatives.length}건 생성됨 — 검수 대기
                  {failedDerivatives.length > 0 &&
                    ` (${failedDerivatives.length}건 실패)`}
                </p>
                <ul className="space-y-1">
                  {resolutionResult.derivatives.map((d) => (
                    <li
                      key={d.goalResCd}
                      className="flex items-center justify-between gap-3 text-caption"
                    >
                      <span className="text-gray-700">
                        {resLabel(d.goalResCd)}
                        <span className="ml-1 text-gray-400">
                          ({d.targetW}×{d.targetH})
                        </span>
                      </span>
                      {d.status === 'CREATED' ? (
                        <span className="inline-flex items-center rounded-full bg-info/10 px-2 py-0.5 font-medium text-info-700">
                          검수 대기 (영상 #{d.rawSn})
                        </span>
                      ) : (
                        <span className="inline-flex items-center rounded-full bg-danger/10 px-2 py-0.5 font-medium text-danger-700">
                          실패
                        </span>
                      )}
                    </li>
                  ))}
                </ul>
              </div>
            )}

            {/* 확정 현황 — 위 블록(생성 응답)이 "예약됨"까지만 말하는 것과 달리, 여기는 조회
                폴링이 알려주는 **확정 결과**다. 확정 실패가 드러나는 유일한 화면이다. */}
            {statusDerivatives.length > 0 && (
              <div
                data-testid="resolution-derivative-status"
                className="space-y-2 rounded-md border border-gray-200 bg-white px-3 py-2.5 text-body-md"
              >
                <p role="status" className="font-medium text-gray-800">
                  파생영상 생성 현황
                  {statusPending.length > 0
                    ? ` — ${statusPending.length}건 진행 중`
                    : statusFailed.length > 0
                      ? ` — ${statusFailed.length}건 실패`
                      : ' — 모두 완료'}
                </p>
                <ul className="space-y-1">
                  {statusDerivatives.map((d) => {
                    const view = derivativeStatusView(d.status);
                    return (
                      <li
                        key={`${d.goalResCd}-${d.rawSn ?? 'none'}`}
                        className="flex items-center justify-between gap-3 text-caption"
                      >
                        <span className="text-gray-700">
                          {resLabel(d.goalResCd)}
                          <span className="ml-1 text-gray-400">
                            ({d.targetW}×{d.targetH})
                          </span>
                          {d.rawSn !== null && (
                            <span className="ml-1 text-gray-400">영상 #{d.rawSn}</span>
                          )}
                        </span>
                        <span
                          data-testid={`derivative-status-${d.goalResCd}`}
                          className={`inline-flex items-center rounded-full px-2 py-0.5 font-medium ${view.className}`}
                        >
                          {view.text}
                        </span>
                      </li>
                    );
                  })}
                </ul>
                {statusFailed.length > 0 && (
                  <p
                    role="alert"
                    data-testid="derivative-failed-notice"
                    className="flex items-start gap-1.5 text-caption text-danger-700"
                  >
                    <AlertCircle size={13} className="mt-0.5 shrink-0" aria-hidden />
                    파생영상 {statusFailed.length}건이 생성에 실패했습니다. 해당 해상도를
                    다시 요청하세요.
                  </p>
                )}
              </div>
            )}
          </div>
        )}
      </section>

      {/* Step 2: 대상 영상 선택 (단일 선택) */}
      <section className="space-y-4">
        <div className="flex flex-wrap items-center gap-2">
          <span className="flex h-7 w-7 shrink-0 items-center justify-center rounded-full bg-primary-600 text-label font-bold text-white">
            2
          </span>
          <h2 className="text-title-sm font-semibold text-gray-800">대상 영상 선택</h2>
          <span className="inline-flex items-center rounded-full bg-gray-100 px-2 py-0.5 text-label font-medium text-gray-600">
            검수 완료 {totalElements}건
          </span>
          {selectedVideoId !== null && (
            <span className="inline-flex items-center rounded-full bg-success/10 px-2 py-0.5 text-label font-medium text-success-700">
              #{selectedVideoId} 선택
            </span>
          )}
        </div>

        {/* 검색·이벤트 필터 */}
        <form
          onSubmit={handleApplyFilters}
          className="flex flex-wrap items-end gap-3 rounded-lg border border-gray-200 bg-white px-4 py-3 shadow-sm"
          data-testid="augment-video-filters"
        >
          <div className="min-w-[200px] flex-1">
            <label
              htmlFor="aug-video-q"
              className="mb-1 block text-label font-medium text-gray-500"
            >
              영상명 / CCTV / ID
            </label>
            <div className="relative">
              <Search
                size={14}
                className="absolute left-2.5 top-1/2 -translate-y-1/2 text-gray-400"
                aria-hidden
              />
              <input
                id="aug-video-q"
                type="text"
                value={localFilters.q}
                onChange={(e) =>
                  setLocalFilters((p) => ({ ...p, q: e.target.value }))
                }
                placeholder="검색어 입력"
                className={`w-full rounded-md border border-gray-300 bg-white py-1.5 pl-8 pr-3 text-body-md ${KRDS_FOCUS}`}
              />
            </div>
          </div>
          <div>
            <label
              htmlFor="aug-event-filter"
              className="mb-1 block text-label font-medium text-gray-500"
            >
              이벤트
            </label>
            <select
              id="aug-event-filter"
              value={localFilters.eventType}
              onChange={(e) =>
                setLocalFilters((p) => ({ ...p, eventType: e.target.value }))
              }
              className={`rounded-md border border-gray-300 bg-white px-2 py-1.5 text-body-md ${KRDS_FOCUS}`}
            >
              <option value="">전체</option>
              {eventTypeOptions.map((et) => (
                <option key={et} value={et}>
                  {et}
                </option>
              ))}
            </select>
          </div>
          <div className="flex items-end gap-2">
            <Button type="submit" variant="primary" size="sm">
              <Search size={14} aria-hidden />
              조회
            </Button>
            <Button
              type="button"
              variant="secondary"
              size="sm"
              onClick={handleResetFilters}
            >
              <RotateCcw size={14} aria-hidden />
              초기화
            </Button>
          </div>
        </form>

        {/* 영상 테이블 (단일 선택 라디오) */}
        {videosLoading ? (
          <div className="space-y-2">
            {Array.from({ length: 5 }).map((_, i) => (
              <Skeleton key={i} height={32} />
            ))}
          </div>
        ) : pagedVideos.length === 0 ? (
          <EmptyState
            message={
              totalElements === 0
                ? '검수 완료된 영상이 없습니다.'
                : '검색 조건에 맞는 영상이 없습니다.'
            }
          />
        ) : (
          <div className="overflow-hidden rounded-lg border border-gray-200 bg-white shadow-sm">
            <table
              className="w-full text-body-md"
              data-testid="augment-video-table"
            >
              <thead className="bg-gray-50">
                <tr>
                  <th className="w-10 px-3 py-2" />
                  <th className="px-3 py-2 text-left text-table-header font-medium text-gray-600">
                    영상명 / CCTV
                  </th>
                  <th className="px-3 py-2 text-left text-table-header font-medium text-gray-600">
                    이벤트
                  </th>
                  <th className="px-3 py-2 text-left text-table-header font-medium text-gray-600">
                    녹화일
                  </th>
                  <th className="px-3 py-2 text-left text-table-header font-medium text-gray-600">
                    검수 완료 일시
                  </th>
                </tr>
              </thead>
              <tbody>
                {pagedVideos.map((v) => {
                  const checked = selectedVideoId === v.id;
                  return (
                    <tr
                      key={v.id}
                      className="cursor-pointer border-b border-gray-100 hover:bg-gray-50"
                      onClick={() => selectVideo(v.id)}
                    >
                      <td className="px-3 py-2">
                        <input
                          type="radio"
                          name="augment-target-video"
                          aria-label={`${v.cctvName} 선택`}
                          checked={checked}
                          onChange={() => selectVideo(v.id)}
                          onClick={(e) => e.stopPropagation()}
                          className="h-4 w-4 border-gray-300 text-primary-600 focus:ring-primary-500"
                        />
                      </td>
                      <td className="max-w-[260px] px-3 py-2">
                        <p className="truncate text-body-md font-medium text-gray-800">
                          {v.cctvName}
                        </p>
                        <p className="truncate font-mono text-mono text-gray-400">
                          #{v.id}
                        </p>
                      </td>
                      <td className="px-3 py-2">
                        {v.eventName ? (
                          <EventTypeBadge eventType={v.eventName} />
                        ) : (
                          <span className="text-caption text-gray-400">-</span>
                        )}
                      </td>
                      <td className="px-3 py-2 text-caption text-gray-600">
                        {/* 촬영 시각(SHT_DT)이 없는 영상은 BE 가 null 을 준다 — 빈 값으로
                            new Date() 를 만들면 'Invalid Date' 가 그대로 노출되므로 '-' 로 둔다. */}
                        {v.capturedAt
                          ? new Date(v.capturedAt).toLocaleDateString('ko-KR')
                          : '-'}
                      </td>
                      <td className="px-3 py-2 text-caption text-gray-600">
                        {v.reviewCompletedAt
                          ? new Date(v.reviewCompletedAt).toLocaleString(
                              'ko-KR',
                              {
                                year: 'numeric',
                                month: '2-digit',
                                day: '2-digit',
                                hour: '2-digit',
                                minute: '2-digit',
                              },
                            )
                          : '-'}
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
            {/*
              페이지네이션 — 공용 컨트롤을 그대로 쓴다(UI-008).
              이 화면이 갖고 있던 이전/다음 버튼만으로는 뒤쪽 페이지로 가려면 그만큼 눌러야 했다.
              공용 컨트롤은 양끝 + 현재 앞뒤 1칸의 번호를 함께 주므로 마지막 페이지로 한 번에 간다.
              총 건수는 이 컨트롤이 갖지 않는다 — 사양이 별도 요소로 규정한 '검수 완료 N건' 배지가
              단계 머리글에서 이미 같은 값을 말하고 있어, 여기 있던 문구는 그 배지와 중복이었다.
            */}
            {totalPages > 1 && (
              <div className="border-t border-gray-200 bg-gray-50 px-3 py-2">
                <Pagination
                  page={currentPage}
                  totalPages={totalPages}
                  onChange={setPage}
                />
              </div>
            )}
          </div>
        )}

        {/* 선택 액션 배지 (단일) */}
        {selectedVideoId !== null && (
          <div className="flex flex-wrap items-center justify-between gap-3 rounded-lg border border-primary-200 bg-primary-50 px-4 py-3">
            <div className="flex items-center gap-3">
              <span className="inline-flex items-center rounded-full bg-info/10 px-2 py-0.5 text-label font-medium text-info-700">
                영상 #{selectedVideoId} 선택됨
              </span>
              <button
                type="button"
                onClick={() => setSelectedVideoId(null)}
                className="inline-flex items-center gap-1 text-caption text-gray-600 underline hover:text-gray-700"
              >
                <X size={12} aria-hidden />
                선택 해제
              </button>
            </div>
          </div>
        )}
      </section>

      {/* 최근 요청 이력 */}
      <section aria-label="최근 요청 이력" className="flex flex-col gap-3">
        <div className="flex items-center justify-between">
          {/* 제목 옆 장식 아이콘은 두지 않는다 — 제목 텍스트를 되풀이할 뿐이다. */}
          <h2 className="text-title-sm font-semibold text-gray-800">최근 요청 이력</h2>
          {data && (
            <span className="inline-flex items-center rounded-full bg-gray-100 px-2 py-0.5 text-label font-medium text-gray-600">
              {data.totalElements}건
            </span>
          )}
        </div>
        {error && <ErrorState title="이력을 불러올 수 없습니다" />}
        {isLoading && (
          <div className="grid grid-cols-1 gap-3 md:grid-cols-2 xl:grid-cols-3">
            {Array.from({ length: 6 }).map((_, i) => (
              <Skeleton key={i} height={92} />
            ))}
          </div>
        )}
        {data &&
          (data.content.length === 0 ? (
            <EmptyState message="등록된 증강 요청이 없습니다" />
          ) : (
            <div
              data-testid="job-card-grid"
              className="grid grid-cols-1 gap-3 md:grid-cols-2 xl:grid-cols-3"
            >
              {data.content.slice(0, 6).map((job) => (
                <JobCard key={job.jobId} job={job} />
              ))}
            </div>
          ))}
      </section>

      {/* 고정 하단 액션 바 */}
      <div className="fixed bottom-0 left-60 right-0 z-20 border-t border-gray-200 bg-white px-6 py-4 shadow-[0_-2px_8px_rgba(0,0,0,0.04)]">
        <div className="mx-auto flex max-w-7xl flex-wrap items-center justify-between gap-4">
          <div className="text-body-md text-gray-600">
            <p>
              선택:{' '}
              <span className="font-semibold text-primary-600">
                {selectedKind ? PROCESS_KIND_LABEL[selectedKind] : '종류 미선택'}
              </span>{' '}
              ×{' '}
              <span className="font-semibold text-primary-600">
                {selectedVideoId !== null
                  ? `영상 #${selectedVideoId}`
                  : '영상 미선택'}
              </span>
            </p>
            {/* 버튼이 왜 비활성인지 알려준다 — 이유를 숨기면 사용자는 원인을 찾지 못한다. */}
            {isAugmentRequest && !promptValidation.ok && (
              <p className="mt-0.5 text-caption text-warning">
                생성 조건 {AUGMENT_PROMPT_FIELD_KEYS.length}개 항목을 모두 입력해야
                요청할 수 있습니다.
              </p>
            )}
          </div>
          <div className="flex items-center gap-3">
            <Button
              variant="secondary"
              size="md"
              onClick={() => navigate(-1)}
              disabled={isPendingAny}
            >
              취소
            </Button>
            <Button
              data-testid="augment-submit"
              variant="primary"
              size="md"
              disabled={!canSubmit}
              loading={isPendingAny}
              onClick={handleSubmit}
            >
              처리 요청
            </Button>
          </div>
        </div>
      </div>
    </section>
  );
}
