// SCR-LABEL-001 다크 헤더 (mock 정합 — 풀스크린 라벨링 화면 상단 56px 바).
//
// 좌: × 닫기 + CCTV명 + 이벤트뱃지
// 중: 저장 상태(저장 중… / 편집 중 / 저장됨 — 상태 아이콘 + 문구)
// 우: [비식별 신고] + [도움말] + [검수제출] (WORKER only)
//
// ★ 객체 수 표시(`N개 객체`)도 헤더에서 폐지했다 — 우측 '객체' 탭의 객체 목록 상단이 단독으로
//   담당한다(SCREEN-005 §라벨링 헤더 바 `[폐기] N개 객체`). 되돌려 넣으면 표시가 두 곳으로 갈린다.
//
// ★ 헤더 [저장] 버튼 제거(진입점 일원화). 저장 진입점은 **캔버스 상단 옵션바의 저장 버튼 +
//   Ctrl+S** 뿐이며, 헤더는 "지금 저장돼 있나"라는 **상태**만 표시한다. 버튼 라벨이 담당하던
//   `저장 중...` 진행 표시는 아래 상태 문구로 이관했다(피드백 유실 방지).
// ★ 프레임 위치 표시(`Frame N / 총 프레임`)도 헤더에서 폐지했다 — 위치 표시·이동은 캔버스 상단
//   옵션바의 프레임 이동 컨트롤(FrameNavigator)이 단독으로 담당한다(SCREEN-005 §라벨링 헤더 바
//   `[폐기] Frame N / 총 프레임`). 되돌려 넣으면 표시가 두 곳으로 갈린다.
// ★ 프레임 이미지 타입 배지(DEID/RAW)도 헤더에서 폐지했다 — 화면은 항상 비식별 프레임을 보여주는
//   것이 기본이라 타입 표기가 사용자에게 주는 정보가 없다.
//   ⚠ **표시만 폐지했고 값(`frameImageType`)은 계속 흐른다** — LabelingPage 가 그 값으로
//     [비식별 누락 신고] 버튼을 RAW 일 때 비활성화한다. 값 배선까지 함께 지우면 검수자가 원본을
//     보는 중에도 신고 버튼이 열린다(회귀 가드: LabelingPageDeidentReport.test.tsx).
//
// ★ 히스토리 진입도 헤더에서 폐지했다(R6/D4) — 어느 버전에서 편집을 시작할지 고르는 일은 라벨링
//   진입 시 띄우는 「시작 버전 선택」 모달이 맡는다(UI-055 의 `[폐기] showHistory` ·
//   `[폐기] onHistoryClick` · `[폐기] historyOpen` · `[폐기] onRolledBack`).
//   ⚠ **제거가 아니라 재배치다** — 그 모달이 영상 산출 버전 목록뿐 아니라 프레임 버전 이력
//     (버전 간 diff · 작업본 diff · 롤백)까지 함께 품는다. 여기 버튼을 되살리면 진입점이 둘로 갈린다.
//
// @design UI-055
// @design D4
// @req R6

import { Check, Circle, HelpCircle, X } from 'lucide-react';
import { useNavigate } from 'react-router-dom';

import { Button } from '@/components/common/Button';
import { EventTypeBadge } from '@/components/common/EventTypeBadge';
import { cn } from '@/lib/cn';

interface LabelHeaderProps {
  cctvName?: string;
  /** 영상의 EV-코드 또는 한글 라벨(eventName). EventTypeBadge 로 전달 — categoryKey 금지. */
  eventType?: string;
  /** 현재 프레임 순번(0부터) — CCTV명이 없을 때의 대체 제목(`프레임 N`)에만 쓴다.
   *  ⚠ 프레임 위치 표시 용도가 아니다(그건 캔버스 상단 옵션바 소관). */
  currentFrame: number;
  dirty: boolean;
  // ⚠ `videoId` · `showHistory` · `onHistoryClick` · `historyOpen` 은 히스토리 진입 폐지와 함께
  //   제거했다(R6/D4). 되살리려면 「시작 버전 선택」 모달과 진입점이 둘로 갈리지 않는지 먼저 볼 것.
  /**
   * 저장 요청 진행 중 — 중앙 상태 문구를 `저장 중...` 으로 바꾼다.
   * ⚠ 헤더 [저장] 버튼이 사라진 뒤 **이 화면의 유일한 텍스트 진행 피드백**이다(버튼 스피너는
   *   캔버스 상단 옵션바의 저장 버튼). 저장은 네트워크 왕복이라 이게 없으면 눌렸는지 알 수 없다 — 제거 금지.
   */
  saving?: boolean;
  /** 검수제출 — WORKER만 노출 (LabelingPage에서 isWorker 가드) */
  submitButton?: React.ReactNode;
  /**
   * 비식별 누락 신고 버튼 슬롯 (히스토리 우측에 배치).
   * INTERNAL 채널 + WORKER/REVIEWER 에게만 LabelingPage 에서 주입.
   */
  deidentReportButton?: React.ReactNode;
  /** X 닫기 버튼 클릭 콜백. 미지정 시 navigate(-1) 기본 동작 (dirty 가드 없음). */
  onClose?: () => void;
  /** 단축키 도움말(치트시트) 열기 콜백. 지정 시 우측에 도움말(?) 버튼 노출. */
  onHelpClick?: () => void;
}

export function LabelHeader({
  cctvName,
  eventType,
  currentFrame,
  dirty,
  saving = false,
  submitButton,
  deidentReportButton,
  onClose,
  onHelpClick,
}: LabelHeaderProps) {
  const navigate = useNavigate();
  const handleClose = onClose ?? (() => navigate(-1));

  return (
    <header
      className="flex items-center gap-3 px-4 bg-white border-b border-gray-200 shrink-0"
      style={{ height: 56 }}
    >
      <Button
        variant="ghost"
        size="sm"
        onClick={handleClose}
        aria-label="뒤로가기"
        className="rounded p-1.5"
      >
        <X size={18} />
      </Button>

      <div className="flex-1 min-w-0 flex items-center gap-2">
        <p className="text-body-md font-semibold text-gray-900 truncate">
          {cctvName ?? `프레임 ${currentFrame + 1}`}
        </p>
        {eventType && <EventTypeBadge eventType={eventType} size="sm" />}
      </div>

      {/* Center status — 프레임 위치 표시는 여기 두지 않는다(캔버스 상단 옵션바 소관). */}
      <div className="text-center shrink-0">
        {/* 저장 상태 — 진행 중(저장 중...) > 미저장(편집 중) > 저장됨 순으로 우선한다.
            진행 중을 dirty 보다 앞에 두는 이유: 저장은 dirty 상태에서 시작되므로 dirty 를 먼저
            보면 진행 표시가 영영 뜨지 않는다. aria-live 로 스크린리더에도 진행을 알린다.

            표식은 아이콘 라이브러리(점=미저장, 체크=저장됨)이며 `aria-hidden` 이다 — 낭독되는
            내용은 문구("편집 중"/"저장됨") 그대로다. 색(경고/성공)은 보조 축일 뿐이고 상태 구분은
            문구와 아이콘 모양이 함께 진다(색만으로 정보 전달 금지). */}
        <p
          role="status"
          aria-live="polite"
          data-testid="label-save-status"
          className={cn(
            'inline-flex items-center gap-1 text-caption',
            saving ? 'text-gray-600' : dirty ? 'text-warning' : 'text-success',
          )}
        >
          {saving ? (
            '저장 중...'
          ) : dirty ? (
            <>
              <Circle className="h-2 w-2 shrink-0 fill-current" aria-hidden />
              편집 중
            </>
          ) : (
            <>
              <Check className="h-3 w-3 shrink-0" aria-hidden />
              저장됨
            </>
          )}
        </p>
      </div>

      <div className="flex-1 flex justify-end items-center gap-2">
        {/* ⚠ 여기에 프레임 이미지 타입(DEID/RAW) 배지를 다시 넣지 말 것 — 2026-08-10 폐지. */}
        {deidentReportButton}
        {onHelpClick && (
          <Button
            variant="ghost"
            size="sm"
            onClick={onHelpClick}
            // ★좌측 도구바 하단에도 도움말 버튼이 있다(둘 다 확정 사양). 접근성 이름이 같으면
            //   보조기술에서 구별되지 않으므로 여는 표면을 이름에 담아 구분한다.
            aria-label="단축키 도움말 전체 보기"
            data-testid="shortcut-help-button"
            className="h-8 w-8 border border-gray-300 p-0"
          >
            <HelpCircle size={14} />
          </Button>
        )}
        {/* ⚠ 여기에 [히스토리] 버튼을 다시 넣지 말 것 — 2026-08-11 폐지(R6/D4).
            버전 진입점은 「시작 버전 선택」 모달 하나이며, 되살리면 진입점이 둘로 갈린다. */}
        {/* ⚠ 여기에 [저장] 버튼을 다시 넣지 말 것 — 좌측 도구바 저장과 중복 진입점이었다.
            (2026-08-06 확정 · 회귀 가드 LabelHeader.test.tsx) */}
        {submitButton}
      </div>
    </header>
  );
}
