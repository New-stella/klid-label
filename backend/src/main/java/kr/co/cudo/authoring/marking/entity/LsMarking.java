package kr.co.cudo.authoring.marking.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 영상 마킹 (LS_MARKING). 자동/수동 이벤트 마킹 정보를 저장한다.
 *
 * <h3>마킹 모드</h3>
 * <ul>
 *   <li>AUTO: frmeIntvNocs(프레임간격수) 기반으로 marks(프레임 인덱스/타임스탬프) 자동 생성</li>
 *   <li>MANUAL: 사용자가 직접 선택한 marks 배열 저장</li>
 * </ul>
 *
 * <h3>상태 전이 [design: ERD-013]</h3>
 * <pre>
 *   RESERVED ─┬─▶ PENDING ──┬─▶ VLM_REQUESTED ──┬─▶ VLM_COMPLETED
 *             │             │                   └─▶ VLM_FAILED (VLM describe 실패 콜백 수신 시)
 *             │             └─▶ SKIPPED (배치 트리거가 정당하게 skip 되어 소비될 일이 없는 마킹 — B-ISSUE-41)
 *             └─▶ SKIPPED (적용하지 못한 예약을 마감 — 비식별이 끝내 실패한 경우)
 *                          VLM_REQUESTED ─▶ SKIPPED (비식별 신고 해소 후 재마킹 진입을 여는 경로)
 * </pre>
 *
 * <p>{@code RESERVED} 는 외부에서 마킹까지 끝난 영상을 받아 적재할 때 쓰는 <b>시작 상태</b>다
 * (ADR-052). 적재 시점에는 그 영상이 아직 비식별되지 않아 마킹을 곧바로 활성화할 수 없으므로
 * 예약해 두었다가, 비식별이 끝나 영상이 마킹 가능 상태가 되면 {@code PENDING} 으로 전이시킨다.
 *
 * <p><b>★ {@code RESERVED → PENDING} 전이 메서드를 이 엔티티에 두지 않는다.</b> 그 전이는 2노드
 * Active-Active 에서 <b>둘 중 한쪽만</b> 집어 가야 하는 <b>원자 클레임</b>이라, 조회 후 변경(dirty
 * checking) 방식이면 두 노드가 <b>둘 다 통과</b>해 잔여 배치가 두 번 기동한다. 따라서 그 전이는
 * {@code LsMarkingRepository} 의 조건부 UPDATE 를 통해서만 일어나며 진입점은
 * {@code MarkingActivationTxService} 하나다 — 여기에 전이 메서드를 두면 그것이 곧 레이스를 다시 여는
 * 우회로가 된다. 예약 마감({@code RESERVED → SKIPPED}) 도 같은 이유로 조건부 UPDATE 다(활성화와
 * 마감이 동시에 오면 마감이 방금 활성화된 {@code PENDING} 을 덮어써 <b>활성 마킹을 지운다</b>).
 *
 * <p>{@code VLM_FAILED} 는 {@code VLM_REQUESTED} 고착(dead-lock)을 해제하는 <b>종결 실패 상태</b>다.
 * 실패 콜백을 받고도 {@code VLM_REQUESTED} 에 방치하면 마킹이 영구 고착된다.
 * 이 상태를 소비해 자동 재요청/복구하는 잡은 아직 <b>미구현</b>이며, 수동/후속 재처리 대상이다(DEV_FIX 2차 #3).
 *
 * <h3>★ 이 원장은 이벤트 유형 코드·영상 파일 경로를 보관하지 않는다 (V27) [design: ERD-013]</h3>
 * <p>두 값은 영상 행({@code LS_DATA_RAW.EVNT_TYPE_CD} · {@code RAW_FILE_PATH_NM})에 이미 있는 것을
 * 마킹 행에 <b>베껴 두던 중복</b>이라, 영상 쪽이 바뀌면 두 값이 어긋나고 어느 쪽이 맞는지 판정할 축이
 * 없었다. 마킹 응답의 두 값은 사라지지 않고 <b>영상 행에서 조달</b>한다
 * ({@code MarkingResponse.from} 이 그 둘을 인자로 받는다) — 화면·외부 계약은 무변경이다.
 *
 * <p>없앤 계기는 포털 업로드 영상 마킹이다. 그 경로에는 관제 인입 이벤트 유형이 애초에 오지 않고
 * 본인 데이터라 비식별도 하지 않아 <b>둘 다 채울 값이 없는데</b> 두 칸이 NOT NULL 이라 저장 자체가
 * 막혀 있었다. 두 값을 지어내 채우지 않고 칸을 없애는 쪽을 골랐다.
 */
@Entity
@Table(name = "LS_MARKING")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LsMarking {

    public static final String MODE_AUTO = "AUTO";
    public static final String MODE_MANUAL = "MANUAL";
    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_VLM_REQUESTED = "VLM_REQUESTED";
    public static final String STATUS_VLM_COMPLETED = "VLM_COMPLETED";
    public static final String STATUS_VLM_FAILED = "VLM_FAILED";

    /**
     * <b>예약</b> — 외부에서 이벤트 마킹까지 끝난 영상을 받아 적재할 때의 시작 상태 (ADR-052).
     * [design: ADR-052] [design: ERD-013]
     *
     * <p>적재 시점에는 그 영상이 아직 비식별되지 않았다. 마킹은 비식별된 영상을 대상으로 하도록
     * 정해져 있고 비식별은 적재 뒤 비동기로 수행되므로, 올리는 그 자리에서 활성화할 수 없다.
     * 그래서 외부가 준 시점 배열을 이 상태로 담아 두었다가 비식별이 끝나 영상이 마킹 가능 상태가
     * 되면 {@link #STATUS_PENDING} 으로 전이시킨다({@code MarkingActivationTxService}).
     *
     * <h3>★ 이 상태는 {@link #ACTIVE_STATUSES} 에 넣지 않는다 — 그것이 이 설계의 요점이다</h3>
     * <p>활성 마킹을 세는 부분 유니크 인덱스({@code UK_LS_MARKING_RAW_ACTVTN} —
     * {@code WHERE STTS_CD IN ('PENDING','VLM_REQUESTED')})가 이 값을 보지 않으므로, 예약은 영상당
     * 활성 마킹 1건 제약을 <b>점유하지 않는다</b>. 따라서 <b>비식별이 끝내 실패해도 사람이 그 영상을
     * 다시 마킹할 수 있다</b>. ADR-052 가 「업로드 시점 즉시 활성화」를 기각한 근거가 바로 이것이며,
     * 여기에 이 값을 더하는 순간 그 성질이 사라진다.
     *
     * <p>적용하지 못한 채 끝난 예약은 {@link #STATUS_SKIPPED} 로 마감한다 — 적재 자체는 되돌리지
     * 않는다(영상과 그 파일은 그대로 남고, 사람이 다시 마킹하면 기존 흐름을 탄다).
     *
     * <p>DB 제약을 새로 걸지 않는다: {@code STTS_CD} 는 {@code varchar(16)} 이고 CHECK 제약이 없어
     * 코드 상수 추가만으로 성립한다(V31 주석의 실측 근거). 값 목록을 제약으로 강제하면 위 성질을
     * 깨뜨릴 위험만 생긴다.
     *
     * <p>⚠ 포털 업로드 경로는 위탁 축 상태값과 이 예약 상태를 <b>쓰지 않는다</b>(ERD-013). 그 경로의
     * 마킹은 프레임을 어느 지점에서 뽑을지 정하는 것이고 비식별 단계 자체가 없다.
     */
    public static final String STATUS_RESERVED = "RESERVED";

    /**
     * <b>종결</b> — 배치 트리거가 정당하게 skip 되어 이 마킹이 소비될 일이 없음 (B-ISSUE-41).
     *
     * <p>마킹 저장(커밋)과 배치 트리거 판단({@code MarkingBatchBridge}, AFTER_COMMIT)이 분리돼 있어,
     * 브리지가 skip 을 결정해도 방금 커밋된 {@code PENDING} 마킹은 남는다. {@code PENDING→VLM_*} 전이는
     * <b>오직 VLM 단계</b>에서만 일어나고 그 단계는 배치가 돌아야 도달하므로, skip 된 마킹은 아무도
     * 전이시키지 않는 <b>영구 고아</b>가 된다. 활성 마킹은 후속 마킹을 409 로 막으므로(=그 영상은 다시는
     * 마킹할 수 없음) 종결시켜 활성 집합에서 빼야 한다.
     *
     * <p>{@code VLM_FAILED} 를 재사용하지 않는 이유: 그 상태는 "VLM 위탁이 실패했다"는 뜻이라 운영자·후속
     * 복구 로직이 재위탁 대상으로 오독한다. skip 은 <b>위탁된 적이 없는</b> 마킹이므로 별도 코드로 구분한다.
     */
    public static final String STATUS_SKIPPED = "SKIPPED";

    /**
     * <b>활성(미종결) 마킹</b> 상태 집합 — 영상당 1건만 존재할 수 있다 (B-ISSUE-22).
     *
     * <h3>"활성" 의 정의와 근거</h3>
     * <p>위 상태 머신에서 {@code PENDING}(위탁 대기)과 {@code VLM_REQUESTED}(위탁 진행 중)는
     * <b>VLM 위탁 사이클이 끝나지 않은</b> 상태다. 배치는 영상당 <b>최신 마킹 1건</b>만 위탁하므로
     * (MarkingLoadStep/VlmTimeseriesStep), 이 구간에 마킹이 2건 이상 쌓이면 나머지는 영원히 위탁되지
     * 않는 <b>고아 행</b>이 된다 — 실측 결함의 형태 그대로다. 따라서 이 두 상태에 한해 1건으로 수렴시킨다.
     *
     * <p>반대로 {@code VLM_COMPLETED}/{@code VLM_FAILED}/{@code SKIPPED} 는 <b>종결</b> 상태라 활성에서 제외한다.
     * 종결 마킹만 남은 영상의 재마킹은 새 배치 사이클을 여는 정당한 시나리오이며(예: VLM 실패 후
     * 재마킹, VLM 은 성공했으나 후속 단계에서 실패해 배치 단계가 여전히 {@code MARKING_READY} 인 영상),
     * 이때 생성되는 새 마킹은 최신 행이라 실제로 위탁된다(고아가 아니다). 이 재마킹 동선을 막지 않기
     * 위해 "모든 마킹 1건" 이 아니라 "미종결 마킹 1건" 으로 정의한다.
     */
    public static final List<String> ACTIVE_STATUSES = List.of(STATUS_PENDING, STATUS_VLM_REQUESTED);

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "MARKING_SN")
    private Long markingSn;

    @Column(name = "RAW_SN", nullable = false)
    private Long rawSn;

    @Column(name = "MARK_MODE_CD", nullable = false, length = 16)
    private String markModeCd;

    @Column(name = "FRME_INTV_NOCS")
    private Integer frmeIntvNocs;

    @Column(name = "MARK_CN", nullable = false, columnDefinition = "TEXT")
    private String markCn;

    /**
     * 마킹 시점에 고정(pin)된 실 프레임레이트 — TOCTOU 제거의 핵심(M-3 후속 근본 수정).
     *
     * <p>마킹 생성 시 {@code VideoFpsResolver.resolveFps} 로 얻은 fps 를 이 컬럼에 저장한다.
     * 프레임추출({@code FfmpegFrameExtractor})은 fps 를 재조회하지 않고 이 pin 값을 읽어
     * {@code seekMillis} 를 계산하므로, 마킹이 {@code frameIndex} 를 산출할 때 쓴 fps 와
     * 추출이 쓰는 fps 가 <b>동일 레코드의 동일 값</b>으로 구조적으로 일치한다(정합성 불변식).
     *
     * <p>{@code null} 허용: 이 컬럼 도입 이전에 생성된 기존 행은 값이 없으므로, 추출이
     * {@code resolveFps} 폴백을 사용한다(하위호환·fail-safe).
     */
    @Column(name = "FPS")
    private Double fps;

    /**
     * 마킹 시 작업자가 고른 <b>검증 이벤트 질문</b>의 일련번호 — 이벤트 어노테이션 질문 칸의 1순위 조달값.
     * [design: ERD-013]
     *
     * <p>그 영상의 검증 이벤트 유형에 등록된 질문 가운데 고른 값이다. 미선택이면 그 유형의 <b>첫 번째
     * 질문</b>이 대신 저장되고, 검증 이벤트 유형이 미수신이면 고를 축이 없으므로 <b>비워 둔다</b>
     * (지어내지 않는다). 질문이 없다고 마킹·위탁이 막히지는 않는다.
     *
     * <p>★ <b>물리 FK 가 없다</b>(V17) — 질문 목록은 관리 화면에서 <b>전체 교체</b>로 저장되어 가리키던
     * 행이 사라지는 것이 정상 동선이기 때문이다. 참조 무결성은 DB 가 아니라 조달 판정기
     * ({@code VerificationEventQuestionResolver})가 갖는다. 따라서 <b>읽는 쪽은 이 값을 그대로 믿지 않고</b>
     * 조달 시점마다 그 판정기로 다시 해석한다.
     */
    @Column(name = "VRFC_EVNT_QSTN_SN")
    private Long vrfcEvntQstnSn;

    /**
     * <b>검증이벤트유형코드</b> — 관제가 유형을 보내지 않은 영상에서 <b>작업자가 마킹 화면에서 고른</b> 값.
     *
     * <p>★ <b>관제 값이 있으면 이 칸은 쓰이지 않는다.</b> 그 경우 화면이 유형 선택을 <b>아예 노출하지 않아</b>
     * 값이 들어올 수 없다. 조달 순서는 <b>관제 인입 값 → 이 칸 → {@code null}</b> 이며, 그래서 우선순위
     * 충돌이 구조적으로 발생하지 않는다(둘이 경쟁할 일이 없다).
     *
     * <p>이 값이 있으면 두 가지가 함께 풀린다 — ①그 유형의 질문 목록이 조달돼 추가 질문 축 위탁의
     * 질문 문구가 생기고 ②묘사 축 위탁의 이벤트 유형이 채워진다. 관제 미수신 영상은 이 값이 없으면
     * <b>시계열 메타를 하나도 받지 못한다</b>.
     *
     * <p>⚠ <b>선택 사항이다</b> — 고르지 않아도 마킹은 완료된다. 필수로 만들면 유형 미수신 영상의 마킹이
     * 막혀(지금은 진행된다) 하위호환이 깨진다.
     *
     * <p>★ <b>물리 FK 가 없다</b> — {@code vrfcEvntQstnSn} 과 같은 이유다. 검증 이벤트 유형·질문 카탈로그는
     * 전체 교체로 저장되어 가리키던 행이 사라지는 것이 정상 동선이고, 그 카탈로그는 <b>허용목록이 아니다</b>
     * (목록에 없는 유형의 영상도 위탁은 그대로 나간다).
     */
    @Column(name = "VRFC_EVNT_TYPE_CD", length = 20)
    private String vrfcEvntTypeCd;

    @Column(name = "STTS_CD", nullable = false, length = 16)
    private String sttsCd;

    /**
     * 마킹을 만든 사용자 — 내부 채널에서는 작업자이고, 포털 채널에서는 그 마킹의 <b>소유자이자 인가
     * 판정의 키</b>다. [design: ERD-013]
     *
     * <p><b>자료형이 숫자가 아니라 문자인 이유(V27)</b>: 포털이 발급한 토큰의 주체 식별자를 담아야 한다.
     * 숫자로 두면 포털 주체를 담지 못해 파싱 실패로 <b>조용히 null</b> 이 되고, 소유자 없는 마킹이
     * 저장된다. 폭은 공통표준도메인 번호V100(문자 100자)을 따른다 — 더 좁히면 서로 다른 사용자가 같은
     * 값으로 잘려 인가가 조용히 어긋난다.
     */
    @Column(name = "REG_USER_NO", length = 100)
    private String createdBy;

    @Column(name = "REG_DT", nullable = false)
    private LocalDateTime regDt;

    @Column(name = "MDFCN_DT", nullable = false)
    private LocalDateTime mdfcnDt;

    /**
     * 자동 모드 마킹 생성.
     *
     * @param rawSn          영상 PK
     * @param intervalFrames 프레임 간격(프레임 수) — 1 이상 필수
     * @param marksJson      JSON 문자열
     * @param createdBy      생성자 사용자 식별자 (nullable)
     */

    /**
     * 작업자가 마킹 화면에서 고른 <b>검증 이벤트 유형</b>을 싣는다.
     *
     * <p>팩토리 인자로 받지 않고 별도 메서드로 둔 이유는 이 값이 <b>관제 미수신 영상에서만</b> 들어오는
     * 조건부 값이라, 모든 생성 경로의 인자를 늘리면 대다수 호출부가 {@code null} 만 넘기게 되기 때문이다.
     *
     * <p>⚠ 넘기는 값은 <b>이미 정규화가 끝난</b> 것이어야 한다 — 관제 인입이 싣는 값과 같은 값 공간이므로
     * 정규화 규칙을 여기에 복제하지 않는다. 복제하면 인입이 대문자로 실어 보낸 값이 이쪽에서만
     * 조달에 실패한다.
     *
     * <p>⚠ 값이 없으면 <b>아무 일도 하지 않는다</b> — 지어내지 않는다.
     *
     * @param normalizedTypeCd 정규화된 검증 이벤트 유형 코드. {@code null}·공백이면 무시한다
     */
    public void applySelectedEventType(String normalizedTypeCd) {
        if (normalizedTypeCd == null || normalizedTypeCd.isBlank()) {
            return;
        }
        this.vrfcEvntTypeCd = normalizedTypeCd;
        this.mdfcnDt = LocalDateTime.now();
    }

    public static LsMarking createAuto(Long rawSn, int intervalFrames, String marksJson, String createdBy) {
        return createAuto(rawSn, intervalFrames, marksJson, createdBy, null);
    }

    /**
     * 자동 모드 마킹 생성 (fps pin 포함) — TOCTOU 제거용.
     *
     * <p>{@code fps} 는 마킹 시점에 해석한 실 프레임레이트다. 프레임추출이 이 값을 재조회 없이
     * 사용하여 마킹↔추출 fps 를 구조적으로 일치시킨다. 미상 폴백값(30.0)을 넘겨도 되고,
     * 아예 {@code null} 을 넘기면 추출이 조회 폴백을 쓴다(하위호환).
     *
     * @param fps 마킹 시점 고정 프레임레이트 (nullable — null 이면 추출이 resolveFps 폴백)
     */
    public static LsMarking createAuto(Long rawSn, int intervalFrames, String marksJson, String createdBy,
                                        Double fps) {
        return createAuto(rawSn, intervalFrames, marksJson, createdBy, fps, null);
    }

    /**
     * 자동 모드 마킹 생성 (fps pin + 질문 선택값 포함).
     *
     * <p>{@code vrfcEvntQstnSn} 은 <b>이미 해석이 끝난</b> 값이어야 한다 — 요청값을 그대로 넘기지 말고
     * {@code VerificationEventQuestionResolver} 로 해석한 결과를 넘긴다(그 유형에 속하지 않으면 첫 번째로
     * 되돌아간 값). 해석 규칙을 여기에 두면 그것이 곧 두 번째 진실원이 된다.
     *
     * @param vrfcEvntQstnSn 해석된 질문 일련번호 (nullable — 검증 이벤트 유형 미수신·질문 0건이면 null)
     */
    public static LsMarking createAuto(Long rawSn, int intervalFrames, String marksJson, String createdBy,
                                        Double fps, Long vrfcEvntQstnSn) {
        if (rawSn == null) {
            throw new IllegalArgumentException("rawSn 은 필수입니다.");
        }
        if (intervalFrames <= 0) {
            throw new IllegalArgumentException("intervalFrames 는 1 이상이어야 합니다.");
        }

        LsMarking m = new LsMarking();
        m.rawSn = rawSn;
        m.markModeCd = MODE_AUTO;
        m.frmeIntvNocs = intervalFrames;
        m.markCn = marksJson;
        m.fps = fps;
        m.vrfcEvntQstnSn = vrfcEvntQstnSn;
        m.sttsCd = STATUS_PENDING;
        m.createdBy = createdBy;
        LocalDateTime now = LocalDateTime.now();
        m.regDt = now;
        m.mdfcnDt = now;
        return m;
    }

    /**
     * 수동 모드 마킹 생성.
     *
     * @param rawSn      영상 PK
     * @param marksJson  JSON 문자열
     * @param createdBy  생성자 사용자 식별자 (nullable)
     */
    public static LsMarking createManual(Long rawSn, String marksJson, String createdBy) {
        return createManual(rawSn, marksJson, createdBy, null);
    }

    /**
     * 수동 모드 마킹 생성 (fps pin 포함) — TOCTOU 제거용.
     *
     * <p>수동 모드는 marks 가 사용자 지정 frameIndex 이지만, 추출 단계는 모드와 무관하게
     * frameIndex→seekMillis 변환에 fps 를 쓴다. 따라서 수동 마킹도 fps 를 고정 저장해
     * 추출이 재조회 없이 동일 값을 사용하게 한다.
     *
     * @param fps 마킹 시점 고정 프레임레이트 (nullable — null 이면 추출이 resolveFps 폴백)
     */
    public static LsMarking createManual(Long rawSn, String marksJson, String createdBy, Double fps) {
        return createManual(rawSn, marksJson, createdBy, fps, null);
    }

    /**
     * 수동 모드 마킹 생성 (fps pin + 질문 선택값 포함).
     *
     * <p>{@code vrfcEvntQstnSn} 의 계약은 {@link #createAuto(Long, int, String, String, Double, Long)}
     * 과 같다 — <b>해석이 끝난</b> 값만 받는다.
     *
     * @param vrfcEvntQstnSn 해석된 질문 일련번호 (nullable)
     */
    public static LsMarking createManual(Long rawSn, String marksJson, String createdBy, Double fps,
                                          Long vrfcEvntQstnSn) {
        if (rawSn == null) {
            throw new IllegalArgumentException("rawSn 은 필수입니다.");
        }

        LsMarking m = new LsMarking();
        m.rawSn = rawSn;
        m.markModeCd = MODE_MANUAL;
        m.frmeIntvNocs = null;
        m.markCn = marksJson;
        m.fps = fps;
        m.vrfcEvntQstnSn = vrfcEvntQstnSn;
        m.sttsCd = STATUS_PENDING;
        m.createdBy = createdBy;
        LocalDateTime now = LocalDateTime.now();
        m.regDt = now;
        m.mdfcnDt = now;
        return m;
    }

    /**
     * <b>예약 마킹 생성</b> — 외부에서 이벤트 마킹까지 끝난 영상을 적재할 때 쓴다 (ADR-052).
     * [design: ADR-052]
     *
     * <p>시작 상태는 {@link #STATUS_RESERVED} 다. 적재 시점에는 그 영상이 아직 비식별되지 않아
     * 마킹을 곧바로 활성화할 수 없으므로 예약해 두고, 비식별이 끝나 영상이 마킹 가능 상태가 되면
     * {@code MarkingActivationTxService} 가 {@link #STATUS_PENDING} 으로 전이시킨다.
     *
     * <h3>마킹 방식 코드가 {@link #MODE_MANUAL} 인 이유</h3>
     * <p>외부가 준 시점 배열의 <b>구조가 사람이 직접 찍은 마킹과 같기</b> 때문이다(ADR-052).
     * 따라서 {@code frmeIntvNocs}(프레임 간격)는 {@code null} 이다 — 간격으로 생성한 값이 아니다.
     *
     * <p>⚠ 그 대가로 <b>사람이 찍은 마킹과 외부가 준 마킹이 마킹 방식 코드로는 구분되지 않는다</b>.
     * ADR-052 가 인지·수용한 대가이며, 구분하려고 새 코드값을 만들지 않는다.
     *
     * <p>⚠ 외부가 함께 준 프레임 이미지는 적재하지 않는다 — <b>시점 위치 정보로만</b> 쓴다. 그 이미지는
     * 비식별 이전 원본이고, 프레임 추출 단계가 같은 시점에서 원본·비식별본 두 벌을 다시 만든다.
     *
     * @param rawSn     영상 PK (필수)
     * @param marksJson 외부가 준 시점 배열의 JSON 문자열 (필수 — 비면 예약할 내용이 없다)
     * @param createdBy 적재를 실행한 사용자 식별자 (nullable)
     * @param fps       적재 시점에 확정한 프레임레이트 pin (nullable — null 이면 추출이 조회 폴백)
     */
    public static LsMarking createReserved(Long rawSn, String marksJson, String createdBy, Double fps) {
        return createReserved(rawSn, marksJson, createdBy, fps, null);
    }

    /**
     * 예약 마킹 생성 (질문 선택값 포함) — 계약은 {@link #createReserved(Long, String, String, Double)}
     * 과 같다.
     *
     * <p>{@code vrfcEvntQstnSn} 은 <b>이미 해석이 끝난</b> 값만 받는다({@code createAuto}/{@code createManual}
     * 과 동일 규약) — 해석 규칙을 여기에 두면 그것이 곧 두 번째 진실원이 된다. 외부 마킹 경로는 사람이
     * 질문을 고르는 자리가 없으므로 대개 {@code null} 이거나 그 유형의 첫 번째 질문이다.
     *
     * @param vrfcEvntQstnSn 해석된 질문 일련번호 (nullable)
     */
    public static LsMarking createReserved(Long rawSn, String marksJson, String createdBy, Double fps,
                                            Long vrfcEvntQstnSn) {
        if (rawSn == null) {
            throw new IllegalArgumentException("rawSn 은 필수입니다.");
        }
        if (marksJson == null || marksJson.isBlank()) {
            throw new IllegalArgumentException("marksJson 은 필수입니다.");
        }

        LsMarking m = new LsMarking();
        m.rawSn = rawSn;
        m.markModeCd = MODE_MANUAL;
        m.frmeIntvNocs = null;
        m.markCn = marksJson;
        m.fps = fps;
        m.vrfcEvntQstnSn = vrfcEvntQstnSn;
        m.sttsCd = STATUS_RESERVED;
        m.createdBy = createdBy;
        LocalDateTime now = LocalDateTime.now();
        m.regDt = now;
        m.mdfcnDt = now;
        return m;
    }

    /**
     * VLM 요청 발송 상태 전이 — {@code PENDING} 에서만 {@code VLM_REQUESTED} 로 전이한다.
     *
     * <p>이미 {@code VLM_REQUESTED}/{@code VLM_COMPLETED}/{@code VLM_FAILED} 인 마킹은 <b>no-op</b>
     * (상태 유지)다. retry 로 파이프라인이 MARKING 부터 전량 재실행될 때 {@code VLM_COMPLETED} 마킹을
     * {@code VLM_REQUESTED} 로 덮어써 유효 META 를 가진 마킹이 고착되는 durable 역행을 원천 차단한다.
     *
     * @return 실제로 전이가 발생하면 {@code true}, no-op 이면 {@code false}
     */
    public boolean markVlmRequested() {
        if (!STATUS_PENDING.equals(this.sttsCd)) {
            return false;
        }
        this.sttsCd = STATUS_VLM_REQUESTED;
        this.mdfcnDt = LocalDateTime.now();
        return true;
    }

    /** VLM 처리 완료 상태 전이. */
    public void markVlmCompleted() {
        this.sttsCd = STATUS_VLM_COMPLETED;
        this.mdfcnDt = LocalDateTime.now();
    }

    /**
     * VLM describe 실패 상태 전이 — 실패 콜백 수신 시 VLM_REQUESTED 고착을 해제한다.
     * 배치 파이프라인이 재요청/복구를 판단할 수 있는 종료 상태로 남긴다.
     */
    public void markVlmFailed() {
        this.sttsCd = STATUS_VLM_FAILED;
        this.mdfcnDt = LocalDateTime.now();
    }

    /**
     * 배치 트리거 skip 종결 전이 — {@code PENDING} 에서만 {@link #STATUS_SKIPPED} 로 전이한다 (B-ISSUE-41).
     *
     * <p>{@code PENDING} 한정 가드가 핵심이다. 이미 {@code VLM_REQUESTED}(위탁 진행 중) 이거나 종결된
     * 마킹을 skip 으로 덮으면 <b>진행 중인 VLM 사이클을 지워버리거나 종결 사실을 역행</b>시킨다.
     * 브리지 skip 분기는 "방금 커밋된 이 마킹"만 대상으로 하므로 {@code PENDING} 이 정상이며,
     * 그렇지 않은 값이면 <b>no-op</b> 으로 아무것도 하지 않는다.
     *
     * @return 실제로 전이가 발생하면 {@code true}, no-op 이면 {@code false}
     */
    public boolean markSkipped() {
        if (!STATUS_PENDING.equals(this.sttsCd)) {
            return false;
        }
        this.sttsCd = STATUS_SKIPPED;
        this.mdfcnDt = LocalDateTime.now();
        return true;
    }

    /**
     * 비식별 재처리(마킹 단계 신고 해소) 재개 시 <b>활성 마킹 강제 종결</b> — {@link #ACTIVE_STATUSES}
     * ({@code PENDING} · {@code VLM_REQUESTED}) 에서 {@link #STATUS_SKIPPED} 로 전이한다 (V171).
     *
     * <h3>{@link #markSkipped()} 와 왜 별도인가</h3>
     * <p>{@code markSkipped()} 는 {@code PENDING} 한정이다 — 그 호출자(배치 트리거 skip)는 "방금 커밋된
     * 미위탁 마킹"만 대상으로 하기 때문이다. 반면 마킹 단계 재개는 <b>재마킹 진입을 열어야</b> 하는데
     * 활성 마킹이 1건이라도 남으면 재마킹이 409(V142 부분 유니크)로 막힌다. 따라서 {@code VLM_REQUESTED}
     * 도 종결 대상이다.
     *
     * <h3>진행 중 위탁을 종결시켜도 되는가 — 되어야 한다</h3>
     * <p>{@code VLM_REQUESTED} 는 <b>신고된(= 마스킹이 잘못된) 비식별본</b>을 대상으로 나간 위탁이다.
     * 그 결과를 재비식별 후에 소비하면 옛 영상의 분석이 새 영상의 시계열 메타로 둔갑한다. 지각 콜백은
     * {@code VlmResultService.markingsInScope} 가 {@code ACTIVE_STATUSES} 만 조회하므로 종결된 이 마킹을
     * 전이시키지 못한다(오전이 없음).
     *
     * <p>종결 상태({@code VLM_COMPLETED}/{@code VLM_FAILED}/{@code SKIPPED})는 <b>no-op</b> —
     * 종결 사실을 역행시키지 않는다.
     *
     * @return 실제로 전이가 발생하면 {@code true}, no-op 이면 {@code false}
     */
    public boolean markSkippedForRedeident() {
        if (!ACTIVE_STATUSES.contains(this.sttsCd)) {
            return false;
        }
        this.sttsCd = STATUS_SKIPPED;
        this.mdfcnDt = LocalDateTime.now();
        return true;
    }

    @PrePersist
    void prePersist() {
        LocalDateTime now = LocalDateTime.now();
        if (this.regDt == null) this.regDt = now;
        if (this.mdfcnDt == null) this.mdfcnDt = now;
        if (this.sttsCd == null) this.sttsCd = STATUS_PENDING;
    }

    @PreUpdate
    void preUpdate() {
        this.mdfcnDt = LocalDateTime.now();
    }
}
