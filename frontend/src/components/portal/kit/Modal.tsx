import type { ReactNode } from 'react'
import { Modal as KitModal } from 'krds-react'
import { glued } from './util'
import { StepButton, type Step } from './modal-step'
import { useWindowScrollLockRelease } from './windowScrollLock'
import './Modal.css'

/**
 * **모달 — 내용이 주인인 창** (2026-09-07 도입 · 갈래 분리).
 *
 * 창은 두 갈래다. 이 창은 **담은 것을 보여주려고 뜨는 쪽**이다 — 입력칸 한 판(회원 탈퇴 ·
 * 별칭 수정), 읽을 자료(문의 상세), 고를 목록. 알리고 고르는 짧은 창은 다이얼로그가 맡는다
 * (`Dialog`).
 *
 * ```
 *  ─────────────────────────────
 *      이름                  ✕      ← 선으로 끊긴다
 *  ─────────────────────────────
 *   내용                            ← 왼쪽부터 · 조각 사이 16
 *   내용
 *  ─────────────────────────────
 *                    서브 · 메인     ← 오른쪽 끝
 *   (안내문)         서브 · 메인     ← note 를 주면 안내문이 왼쪽에 선다
 * ```
 *
 * | | **모달** (이 창) | **다이얼로그** (`Dialog`) |
 * |---|---|---|
 * | 무엇이 주인인가 | **내용** | **이름과 걸음** |
 * | 걸음 자리 | **오른쪽 끝** | 가운데 |
 * | 머리·아랫동 선 | **있다** | 없다 |
 * | 폭 | **sm · md · lg** | sm 하나 |
 *
 * 선 · 좌우 24 · 위아래 16 · 이름 19 · X 자리는 **공통층이 갖는다**
 * (krds-theme.css § 창은 두 갈래다). 이 파일이 갖는 것은 본문 리듬과 걸음 줄뿐이다.
 *
 * ★ **문구가 다르다고 창을 새로 만들지 않는다.** 화면은 이 창에 이름과 내용을 꽂는다.
 *   속이 특별한 창만 제 부품을 갖는다 — 다운로드 신청(`DownloadRequestModal`) · 검색 조건
 *   (`FilterModal`) · 저장분 고르기(`SavedSearchPickerModal`) · 이메일 변경
 *   (`EmailChangeModal`). 넷 다 **같은 모달 갈래**라 겉모습은 이 창과 하나로 움직인다.
 */
export function Modal({
  open,
  onOpenChange,
  size = 'md',
  title,
  children,
  main,
  sub,
  note,
}: {
  open: boolean
  onOpenChange: (open: boolean) => void
  /**
   * **창 폭** — `sm`(400) · `md`(512 · 기본) · `lg`(760).
   * 든 것이 정한다: 한 판짜리 폼은 md, 줄이 긴 표나 나란히 서는 칸이 있으면 lg,
   * 한두 칸뿐이면 sm. **폰에서는 셋 다 화면을 다 쓴다** — 폭이 갈리는 것은 넓은 화면뿐이다.
   * ※ 이름 크기는 폭을 따라가지 않는다 — 셋 다 19 다 (2026-09-07).
   */
  size?: 'sm' | 'md' | 'lg'
  /** **이름** — 창이 무엇을 담고 있는지. 보조기술이 읽는 이름이기도 하다 */
  title: string
  /**
   * **창의 내용.** 넘긴 조각이 위에서 아래로 **16** 씩 쌓인다 (입력칸 · 경고 띠 · 표 ·
   * 체크박스 · 읽을 글). 간격을 화면에서 다시 잡지 않는다 — 그러면 창마다 값이 갈린다.
   */
  children: ReactNode
  /**
   * **메인 버튼** — 채움(primary). **없어도 된다** — 읽고 X 로 닫는 창이 있다(문의 상세).
   * 그때는 아랫동이 통째로 서지 않는다.
   */
  main?: Step
  /** **서브 버튼** — 테두리(secondary). 없으면 걸음이 하나뿐인 창이 된다 */
  sub?: Step
  /**
   * **하단 안내문** — 아랫동 왼쪽에 서는 한 줄. 걸음은 그대로 오른쪽 끝이다 (2026-09-14 사용자 지시).
   * 메인 걸음이 **누르면 어디로 가는지**를 누르기 전에 말해야 하는 자리가 쓴다 (증강 결과 확인 ·
   * 「라벨링 이어서 하기」). 걸음과 짝인 말이라 **걸음이 있을 때만** 선다.
   */
  note?: ReactNode
}) {
  // 열린 채 언마운트돼도 문서(임베드에서는 Host 문서)의 스크롤 잠금이 남지 않게 한다.
  useWindowScrollLockRelease(open)
  const mainButton = main ? <StepButton step={main} onClose={() => onOpenChange(false)} /> : null
  const subButton = sub ? (
    <StepButton step={sub} variant="secondary" onClose={() => onOpenChange(false)} />
  ) : null
  /** 걸음이 있는지. 본문 **아래 여백**을 걸음 줄이 갖느냐를 가른다 (Modal.css) */
  const hasSteps = mainButton || subButton ? 'yes' : 'none'
  return (
    <KitModal.Root closeOnEsc closeOnOverlayClick size={size} open={open} onOpenChange={onOpenChange}>
      {/* ★ 창의 **이름**을 직접 잇는다. 킷은 `role="dialog" aria-modal="true"` 까지만 주고
          `aria-labelledby` 를 걸지 않아, 그대로 두면 보조기술이 "대화상자" 라고만 읽고 이름을
          읽지 않는다 (krds-react 1.1.1 실측) */}
      <KitModal.Content className="klid-modal" data-steps={hasSteps} aria-labelledby={TITLE_ID}>
        <KitModal.Header>
          <KitModal.Title id={TITLE_ID}>{glued(title)}</KitModal.Title>
        </KitModal.Header>
        <KitModal.Body>
          <div className="klid-modal-body">{children}</div>
        </KitModal.Body>
        {/* 걸음이 하나도 없으면 아랫동을 세우지 않는다 — 빈 푸터는 창 아래에 선과 여백만 남긴다.
            서는 순서는 **서브가 먼저 · 메인이 나중**이다 — 메인이 오른쪽 끝
            (2026-09-04 확정 · 예외 없음. 자리는 고정이고 무게는 색이 진다) */}
        {/* 안내문이 있으면 킷의 양끝 짜임(multi-conts)을 켠다 — 안내문이 왼쪽, 걸음 묶음이 오른쪽 끝 */}
        {hasSteps === 'yes' ? (
          note ? (
            <KitModal.Footer className="multi-conts">
              <p className="form-hint klid-modal-note">{note}</p>
              <div className="klid-modal-steps">
                {subButton}
                {mainButton}
              </div>
            </KitModal.Footer>
          ) : (
            <KitModal.Footer>
              {subButton}
              {mainButton}
            </KitModal.Footer>
          )
        ) : null}
      </KitModal.Content>
    </KitModal.Root>
  )
}

/**
 * 이름 id. 창은 화면에 하나만 떠 있으므로 고정값으로 둔다 — 같은 페이지에 이 창이 둘 이상
 * 뜨는 일은 없다 (뜨면 뒤에 뜬 것이 앞을 덮는다).
 */
const TITLE_ID = 'klid-modal-title'
