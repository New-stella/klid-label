package kr.co.cudo.authoring.marking.repository;

import kr.co.cudo.authoring.common.datasource.ControlRepo;
import kr.co.cudo.authoring.marking.entity.LsMarking;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

@ControlRepo
public interface LsMarkingRepository extends JpaRepository<LsMarking, Long> {

    /**
     * 영상의 마킹을 <b>배치 소비 순서</b>(최신 먼저)로 조회한다 — 배치가 실제로 위탁하는 1건은 첫 항목이다.
     *
     * <h3>{@code MARKING_SN DESC} 타이브레이크가 필수인 이유 (B-ISSUE-22 정합)</h3>
     * <p>{@code REG_DT} 단일 정렬이면 동률(같은 밀리초) 시 순서가 <b>DB 물리 저장 순서에 의존</b>해
     * 비결정적이다. 그런데 B-ISSUE-22 의 발생 원인 자체가 "동시 요청으로 같은 순간에 여러 행 생성"
     * 이므로 동률은 현실적 시나리오다. V142 백필은 남길 1건을
     * {@code ORDER BY REG_DT DESC, MARKING_SN DESC} 로 결정론적으로 고르므로, 배치 소비 정렬이
     * 이와 다르면 "배치가 실제로 위탁하는 그 1건을 남긴다"는 마이그레이션 전제가 깨진다
     * (남긴 행과 위탁되는 행이 어긋나 종결 처리된 고아가 위탁될 수 있다).
     *
     * <p>따라서 <b>정렬 기준은 이 메서드 하나로 정의</b>하고(소비자: {@code MarkingLoadStep} ·
     * {@code MarkingSelectedQuestionReader} — 후자는 활성 마킹이 없을 때 「최신 한 건」을 고르는 데 쓴다.
     * 비교자를 복제하지 않고 이 조회를 그대로 쓰므로 위 규칙은 지켜진다),
     * V142 백필 정렬과 문자 그대로 일치시킨다. 순서를 바꾸려면 양쪽을 함께 바꿔야 한다.
     */
    List<LsMarking> findByRawSnOrderByRegDtDescMarkingSnDesc(Long rawSn);

    List<LsMarking> findByRawSnAndSttsCd(Long rawSn, String sttsCd);

    /**
     * 특정 상태의 마킹을 <b>배치 소비 순서와 같은 정렬</b>(최신 먼저)로 조회한다.
     *
     * <p>예약 마킹 활성화가 「어느 예약을 깨울 것인가」를 결정론적으로 고르는 데 쓴다. 정렬 규칙을
     * {@link #findByRawSnOrderByRegDtDescMarkingSnDesc} 와 문자 그대로 일치시키는 이유는 그 Javadoc 이
     * 설명한 것과 같다 — 동률(같은 밀리초)일 때 DB 물리 저장 순서에 기대면 <b>깨우는 행과 배치가 실제로
     * 위탁하는 행이 어긋난다</b>.
     */
    List<LsMarking> findByRawSnAndSttsCdOrderByRegDtDescMarkingSnDesc(Long rawSn, String sttsCd);

    /**
     * <b>예약 마킹 활성화의 원자 클레임</b> — 지정한 마킹이 아직 {@code fromStatus} 일 때만 전이한다
     * (CWE-362). [design: ADR-052] [design: SEQ-030]
     *
     * <h3>왜 조회 후 변경이면 안 되는가</h3>
     * <p>2노드 Active-Active 라 두 노드가 같은 예약을 동시에 집을 수 있다. 조회 후 변경(dirty checking)
     * 방식이면 두 노드가 <b>둘 다 예약 상태를 보고 둘 다 전이에 성공</b>해 잔여 배치가 두 번 기동한다.
     * Quartz 클러스터링은 트리거 중복만 막고 잡 내부의 이 레이스는 막지 않는다. 단일 조건부 UPDATE 는
     * DB 가 직렬화하므로 <b>영향 행수 1을 받은 쪽만</b> 소유권을 갖는다.
     *
     * <p>{@code MDFCN_DT} 를 SET 절에서 직접 갱신한다 — 벌크 UPDATE 는 {@code @PreUpdate} 콜백을 타지
     * 않으므로 여기서 채우지 않으면 수정 일시가 예약 시점에 고착된다.
     *
     * @param markingSn  깨울 예약 마킹 PK
     * @param fromStatus 출발 상태 — {@link LsMarking#STATUS_RESERVED}
     * @param toStatus   도착 상태 — {@link LsMarking#STATUS_PENDING}
     * @return 영향 행수 (1=이 호출이 소유권을 얻음, 0=다른 노드가 먼저 집었거나 행이 사라짐)
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE LsMarking m SET m.sttsCd = :toStatus, m.mdfcnDt = CURRENT_TIMESTAMP "
            + "WHERE m.markingSn = :markingSn AND m.sttsCd = :fromStatus")
    int transitionMarkingIfStatus(@Param("markingSn") Long markingSn,
                                  @Param("fromStatus") String fromStatus,
                                  @Param("toStatus") String toStatus);

    /**
     * <b>영상의 예약 마킹 일괄 마감</b> — 아직 {@code fromStatus} 인 행만 {@code toStatus} 로 바꾼다.
     * [design: ADR-052]
     *
     * <p>비식별이 끝내 실패한 영상의 예약을 마감하거나(RESERVED → SKIPPED), 활성화 이후 남은 잉여
     * 예약을 쓸어 담는 데 쓴다. 여기서도 조건부 UPDATE 여야 한다 — 무조건 UPDATE 면 마감이 <b>방금
     * 활성화된 {@code PENDING} 마킹을 덮어써</b> 그 영상의 활성 마킹을 지운다.
     *
     * <p>도착 상태가 활성 집합 밖({@link LsMarking#STATUS_SKIPPED})이므로 여러 행을 한 번에 바꿔도
     * 활성 마킹 부분 유니크({@code UK_LS_MARKING_RAW_ACTVTN})를 건드리지 않는다.
     *
     * @return 실제로 마감된 행 수 (0=마감할 예약이 없었음 — 멱등)
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE LsMarking m SET m.sttsCd = :toStatus, m.mdfcnDt = CURRENT_TIMESTAMP "
            + "WHERE m.rawSn = :rawSn AND m.sttsCd = :fromStatus")
    int transitionAllByRawSnIfStatus(@Param("rawSn") Long rawSn,
                                     @Param("fromStatus") String fromStatus,
                                     @Param("toStatus") String toStatus);

    /**
     * 여러 상태를 한 번에 조회한다 — <b>콜백 선행 레이스</b> 대응 (Phase C-1).
     *
     * <p>VLM 콜백 수신부는 {@code VLM_REQUESTED} 만 보고 전이했는데, 제출이 논블로킹이 되면 콜백이
     * ACK 보다 먼저 커밋될 수 있다. 선커밋(제출 전 전이)이 1차 방어지만, 어떤 이유로든 마킹이 아직
     * {@code PENDING} 이면 콜백 전이가 0건이 되고 이후 스텝이 상태를 올려 <b>VLM_REQUESTED 영구 고착</b>이
     * 된다. 그래서 수신부 조회를 {@link LsMarking#ACTIVE_STATUSES}(PENDING + VLM_REQUESTED)로 넓혀
     * 양단에서 닫는다. 종결 상태(VLM_COMPLETED/VLM_FAILED)는 포함하지 않는다(역행 금지).
     */
    List<LsMarking> findByRawSnAndSttsCdIn(Long rawSn, Collection<String> sttsCds);

    /**
     * 영상에 <b>활성(미종결) 마킹</b>이 이미 존재하는지 (B-ISSUE-22).
     *
     * <p>사전 거부(409)용 조회다. 동시 요청은 서로의 미커밋 행을 보지 못하므로 이 조회만으로는
     * 막을 수 없고, 최종 방어는 {@code LS_MARKING} 부분 유니크 인덱스(V142)다.
     *
     * @param sttsCds {@link LsMarking#ACTIVE_STATUSES}
     */
    boolean existsByRawSnAndSttsCdIn(Long rawSn, Collection<String> sttsCds);
}
