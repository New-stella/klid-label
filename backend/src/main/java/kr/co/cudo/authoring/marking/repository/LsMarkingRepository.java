package kr.co.cudo.authoring.marking.repository;

import kr.co.cudo.authoring.common.datasource.ControlRepo;
import kr.co.cudo.authoring.marking.entity.LsMarking;
import org.springframework.data.jpa.repository.JpaRepository;

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
     * <p>따라서 <b>정렬 기준은 이 메서드 하나로 정의</b>하고(유일 소비자: {@code MarkingLoadStep}),
     * V142 백필 정렬과 문자 그대로 일치시킨다. 순서를 바꾸려면 양쪽을 함께 바꿔야 한다.
     */
    List<LsMarking> findByRawSnOrderByRegDtDescMarkingSnDesc(Long rawSn);

    List<LsMarking> findByRawSnAndSttsCd(Long rawSn, String sttsCd);

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
