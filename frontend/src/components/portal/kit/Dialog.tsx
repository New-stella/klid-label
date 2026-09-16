import type { ReactNode } from 'react'
import { Modal } from 'krds-react'
import { glued } from './util'
import { StepButton, type Step } from './modal-step'
import { useWindowScrollLockRelease } from './windowScrollLock'
import './Dialog.css'

/**
 * **작은 창(sm 400) — 포털에 창은 이것 하나다** (2026-08-10 도입 · 2026-08-25 통일).
 *
 * ★ **문구가 다르다고 창을 새로 만들지 않는다.** 화면은 이 창에 글만 꽂는다 —
 *   `title` · `desc` · `main` · `sub`. 종전에는 문구 묶음마다 창 컴포넌트가 하나씩 있었고
 *   (로그인 벽 · 저장 상한 · 로그아웃 · 세션 만료), 그만큼 같은 모양이 네 군데로 갈려 한쪽을
 *   고치면 나머지가 뒤처졌다. 2026-08-25 에 전부 접었다.
 *
 * **정렬은 가운데로 하나다.** 머리 줄의 **첫 조각이 X 와 같은 줄을 쓴다** — 글리프가 있으면
 * 글리프가, 없으면 제목이 그 자리를 쓴다 (`data-mark`).
 * ```
 *  글리프 없음                      글리프 있음
 *      타이틀        ✕                [글리프]      ✕
 *    서브텍스트                          타이틀
 *   [경고 상자]                       서브텍스트
 *  ─────────────                    [경고 상자]
 *  [추가 내용] (왼쪽)                ─────────────
 *    서브 · 메인                     [추가 내용] (왼쪽)
 *                                     서브 · 메인
 * ```
 * 위 여백은 킷 기본 그대로다 — 킷 기본형이 곧 이 꼴이고, 아래로 밀어 X 줄을 비우면 창 위에
 * 빈 띠만 남는다. 좌우는 X 자리만큼 **같이** 비운다 (그래야 첫 조각이 창 한가운데에 선다).
 * 종전에는 정렬이 갈래 둘이었다 — 로그아웃·세션 만료가 킷 기본형(제목 왼쪽 · 걸음 오른쪽)으로
 * 서서 같은 폭 안에 두 모양이 번갈아 떴고, 사용자는 매번 새 창으로 읽었다.
 * **창의 성격은 글리프와 제목이 말하지, 정렬이 말하지 않는다.**
 *
 * 그 안에서 갈리는 것은 셋뿐이고 셋 다 이 창이 받는다:
 *   1. **글리프**(`icon`) — 있으면 성격을 한눈에 말하고(로그인 벽=자물쇠, 저장 상한=별),
 *      없으면 제목부터 선다 (로그아웃·삭제 확인처럼 하던 일의 연장인 창).
 *      걸음이 없는 창에는 쓰지 않는다 — 제목이 X 와 한 줄에 서서 얹을 자리가 없다.
 *   2. **경고 상자**(`alert`) — 서브텍스트 아래에서 지금 걸리는 것을 짚는다 (공용 `Alert`).
 *   3. **걸음 수** — `main`+`sub` 면 둘, `main` 만이면 하나, **둘 다 없으면 없다**
 *      (X 로만 닫는 안내 — 알리는 것이 전부라 고를 것이 없는 자리다).
 * 이 축들과 실제 창은 스토리북 `포털/Dialog` 한 카드에서 함께 본다.
 *
 * ★ **창은 두 갈래다** (2026-09-07 갈래 분리). 이 창은 **이름과 걸음이 주인인 쪽**이다 —
 *   알리고, 고르게 하고, 닫힌다. 담은 것을 보여주려고 뜨는 창은 **모달**이 맡는다 (`Modal`).
 *
 * | | **다이얼로그** (이 창) | **모달** (`Modal`) |
 * |---|---|---|
 * | 무엇이 주인인가 | **이름과 걸음** | 내용 |
 * | 걸음 자리 | **가운데** | 오른쪽 끝 |
 * | 머리·아랫동 선 | **없다** | 있다 |
 * | 폭 | **sm 하나** | sm · md · lg |
 *
 * **선을 긋지 않는 이유**: 담은 것이 글 두어 줄과 걸음뿐이라 나눌 칸이 없다. 선을 그으면
 * 짧은 글이 칸에 갇혀, 알리려던 한 문장보다 테두리가 먼저 보인다.
 *
 * 입력칸이 들거나 읽을 자료가 있으면 이 창이 아니다 — 회원 탈퇴 · 별칭 수정 · 문의 상세 ·
 * 다운로드 신청은 모두 모달 갈래다.
 *
 * **제목은 "무슨 일이 일어났는가" 다** — 창을 연 버튼 이름이 아니다 (`검색조건 저장` 이 아니라
 * `검색 조건은 5개까지 저장할 수 있습니다`). 버튼 이름을 그대로 쓰면 방금 누른 말을 되풀이할 뿐,
 * 왜 멈췄는지는 설명 줄까지 읽어야 알게 된다.
 *
 * 푸터에 `닫기` 를 두지 않는다 — 헤더 X 가 이미 나가는 길이다. 남는 것은 **여기서 갈 수 있는
 * 곳**뿐이다 (되돌릴 수 없는 창의 `취소`·`머무르기` 는 나가는 길이 아니라 고르는 걸음이라 남는다).
 *
 * ★ 걸음이 둘이면 **언제나 서브가 먼저 · 메인이 나중 — 메인이 오른쪽 끝이다**
 *   (2026-09-04 확정, 예외 없음. 종전에는 메인이 먼저였다 — 2026-08-25).
 *   포털의 나머지 창(폼 창 · 관리자 창)이 전부 이 꼴이라 작은 확인 창 하나만 뒤집혀 있었다.
 *   되돌릴 수 없는 창이라고 안전한 쪽을 앞세우지 않는다 — 같은 자리의 버튼이 창마다 자리를
 *   바꾸면 사용자는 매번 어느 쪽이 무엇인지 다시 읽는다. **자리는 고정이고 무게는 색이 진다.**
 */
export function Dialog({
  open,
  onOpenChange,
  icon,
  title,
  alert,
  desc,
  meta,
  caption,
  children,
  main,
  sub,
}: {
  open: boolean
  onOpenChange: (open: boolean) => void
  /**
   * 글리프만 넘긴다 — 감싸는 칩은 이 창이 갖는다 (크기·면·색이 창마다 갈리지 않게).
   * **없어도 된다.** 하던 일의 연장인 창은 그림 없이 제목부터 선다.
   */
  icon?: ReactNode
  /** **타이틀** — 무슨 일이 일어났는가. 창의 이름이자 보조기술이 읽는 이름이다 */
  title: string
  /**
   * **서브텍스트 아래**에 서는 경고 상자(`Alert`). 설명이 무슨 일이 일어나는지 말하고,
   * 상자는 그 때문에 **지금 걸리는 것**을 짚는다 (2026-08-25 확정 — 종전에는 제목 바로 아래
   * 였는데, 상황을 말하기도 전에 경고부터 서서 무엇에 대한 경고인지 되짚어 읽어야 했다).
   * 부품은 공용 `Alert` 이라 톤·색이 화면의 다른 안내 띠와 함께 움직인다.
   */
  alert?: ReactNode
  /**
   * **서브텍스트** — 한두 줄로 끝낸다. 더 길어지면 이 창이 아니라 화면이 할 말이다.
   * 낱말 강조가 섞일 수 있어 글자만이 아니라 조각도 받는다 (세션 만료의 남은 시간 등).
   */
  desc?: ReactNode
  /**
   * **서브텍스트 밑 값 한 줄** — 문장이 아니라 지금 값을 보이는 줄 (저장 중의 진행 시간 「00:03」).
   * 서브텍스트보다 한 단 크게 서고, 숫자가 바뀌어도 폭이 흔들리지 않게 숫자 폭을 고정한다.
   * 서브텍스트에 이어 쓰거나 손으로 줄을 끊지 않으려고 자리를 따로 둔다 (2026-09-14 사용자 지시)
   */
  meta?: ReactNode
  /**
   * **값 밑 안내 한 줄** — 캡션 글(13 · 옅은 회색)로 선다. 값에 딸린 말이다 (저장 중의 「(최대 300초)」).
   * 값과 한 줄에 두면 안내까지 같이 커져 값처럼 읽힌다 (2026-09-14 사용자 지시)
   */
  caption?: ReactNode
  /**
   * 서브텍스트 아래 붙는 **추가 내용** — 목록이나 인증 버튼처럼 문장으로 안 되는 것.
   * **구분선으로 나뉘고 왼쪽 정렬로 선다** — 위는 창이 하는 말이고 여기는 그 말이 가리키는
   * 자료라, 성격이 갈리는 자리다 (2026-08-25 확정). 목록이면 **불렛도 창이 그린다.**
   * 없는 것이 보통이다. **여기에 폼을 넣지 않는다** — 그건 모달이다 (`Modal`).
   */
  children?: ReactNode
  /**
   * **메인 버튼** — 채움(primary). 이 창의 기본 걸음이다.
   * **없어도 된다** — 알리는 것이 전부인 창은 걸음이 없고 X 로만 닫는다
   * (보존기간 안내 · 휴대폰 번호 변경). 그때는 아랫동이 통째로 서지 않는다.
   */
  main?: Step
  /** **서브 버튼** — 테두리(secondary). 없으면 걸음이 하나뿐인 창이 된다 */
  sub?: Step
}) {
  // 열린 채 언마운트돼도 문서(임베드에서는 Host 문서)의 스크롤 잠금이 남지 않게 한다.
  useWindowScrollLockRelease(open)
  const mainButton = main ? <StepButton step={main} onClose={() => onOpenChange(false)} /> : null
  const subButton = sub ? (
    <StepButton step={sub} variant="secondary" onClose={() => onOpenChange(false)} />
  ) : null
  /** 걸음이 있는지. 본문 **아래 여백**을 걸음 줄이 갖느냐를 가른다 (Dialog.css) */
  const hasSteps = mainButton || subButton ? 'yes' : 'none'
  return (
    <Modal.Root closeOnEsc closeOnOverlayClick size="sm" open={open} onOpenChange={onOpenChange}>
      {/* ★ 창의 **이름**을 직접 잇는다. 킷은 `role="dialog" aria-modal="true"` 까지만 주고
          `aria-labelledby` 를 걸지 않아, 그대로 두면 보조기술이 "대화상자" 라고만 읽고 제목을
          읽지 않는다 (krds-react 1.1.1 실측 — `Modal.Header title` 을 쓴 다른 창들도 같다) */}
      <Modal.Content
        className="klid-dialog"
        data-mark={icon ? 'yes' : 'none'}
        data-steps={hasSteps}
        aria-labelledby={TITLE_ID}
      >
        {/* ★ 킷 타입 결함 우회 — `Modal.Header` 의 `title` 은 타입이 `React.ComponentProps<'div'>`
            (HTML title 속성 = string) 와 `{ title?: ReactNode }` 의 교집합이라 **문자열만** 받는다.
            글리프를 함께 얹으려면 children 으로 넘겨야 한다 — 킷은 title 이 없으면 children 을
            그대로 그리고, `Modal.Title` 이 같은 `<h2 class="modal-title">` 을 낸다.
            DOM 은 title 을 넘긴 것과 같다 (krds-react 1.1.1 확인) */}
        <Modal.Header>
          {icon ? <span className="klid-dialog-mark">{icon}</span> : null}
          <Modal.Title id={TITLE_ID}>{glued(title)}</Modal.Title>
        </Modal.Header>
        <Modal.Body>
          {desc ? <p>{glued(desc)}</p> : null}
          {meta ? <p className="klid-dialog-meta">{glued(meta)}</p> : null}
          {caption ? <p className="klid-dialog-caption">{glued(caption)}</p> : null}
          {alert ? <div className="klid-dialog-alert">{alert}</div> : null}
          {children ? <div className="klid-dialog-extra">{children}</div> : null}
        </Modal.Body>
        {/* 걸음이 하나도 없으면 아랫동을 세우지 않는다 — 빈 푸터는 창 아래에 여백만 남긴다.
            서는 순서는 **서브가 먼저 · 메인이 나중**이다 — 메인이 오른쪽 끝 (위 ★).
            걸음이 하나면 서브가 없어 메인 혼자 선다 */}
        {hasSteps === 'yes' ? (
          <Modal.Footer>
            {subButton}
            {mainButton}
          </Modal.Footer>
        ) : null}
      </Modal.Content>
    </Modal.Root>
  )
}

/**
 * 제목 id. 창은 화면에 하나만 떠 있으므로 고정값으로 둔다 — 같은 페이지에 이 창이 둘 이상
 * 뜨는 일은 없다 (뜨면 뒤에 뜬 것이 앞을 덮는다).
 */
const TITLE_ID = 'klid-dialog-title'

export type { Step }
