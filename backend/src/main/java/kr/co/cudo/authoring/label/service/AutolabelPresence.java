package kr.co.cudo.authoring.label.service;

import kr.co.cudo.authoring.batch.entity.LsDataLbl;
import kr.co.cudo.authoring.batch.repository.LsDataLblRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * "이 영상에 <b>오토라벨 산출물</b>(자동 생성 라벨)이 이미 있는가" 판정의 <b>단일 원천</b>.
 * [@design AC-051] [@design API-043]
 *
 * <h3>왜 별도 빈인가 (복제 금지)</h3>
 * <p>영상 상세의 배치 알림은 「조치가 필요한 작업 묶음」만 보여주고, 그 판정 축이 <b>그 묶음의 산출물이
 * 있는가</b> 다. 묶음마다 산출물이 다르다 — 시계열 묶음은 시계열 메타({@code VlmTimeseriesMetaPresence}),
 * 오토라벨 묶음은 <b>자동 생성 라벨</b>이다. 두 묶음이 같은 모양의 판정기를 갖도록 이 클래스를 그 대칭으로
 * 둔다. 판정을 호출부마다 다시 쓰면 한쪽만 갱신되는 날 조용히 어긋난다.
 *
 * <h3>⚠ "라벨이 있는가" 로 판정하면 안 된다 (이 클래스의 존재 이유)</h3>
 * <p>{@code LS_DATA_LBL} 에는 오토라벨이 만든 라벨과 <b>사람이 손으로 그린 라벨</b>이 같은 테이블에
 * 들어 있다(사람 라벨은 AI 메타가 {@code null} — {@link LsDataLbl} 클래스 주석 「NULL 의 의미」).
 * 전체 존재 여부로 판정하면 라벨링 작업자가 라벨을 <b>하나라도 그린 순간</b> 그 영상의 오토라벨 묶음이
 * "산출물 있음"이 되어 조치 목록에서 사라진다 — 정말 조치가 필요한 영상이 조용히 숨는다.
 * 그래서 술어는 {@code AUTO_LBL_YN='Y'} <b>이면서</b> {@code LBL_SRC_CD} 가 {@link #AUTOLABEL_SRC_CDS}
 * 인 라벨로 좁힌다.
 *
 * <h3>출처 목록은 여기 한 곳이다</h3>
 * <p>지금 오토라벨 출처는 AI 탐지({@link LsDataLbl#SRC_YOLO})와 AI 분할({@link LsDataLbl#SRC_SAM2})
 * 둘이다. 출처가 늘면 <b>{@link #AUTOLABEL_SRC_CDS} 한 곳만</b> 고친다 — 리포지토리 술어는 이 목록을
 * 인자로 받을 뿐 출처 문자열을 갖지 않는다.
 *
 * <p>⚠ 트랙 보간({@link LsDataLbl#SRC_INTERPOLATE})은 목록에 넣지 않는다. 보간은 오토라벨 묶음의
 * 구성원이지만 <b>탐지·분할 산출물에서 파생</b>되므로, 그 둘이 없는데 보간만 남는 상태는 산출물이
 * 있다고 볼 수 없다(있다면 오히려 앞 단계가 지워진 이상 상태다).
 */
@Component
@RequiredArgsConstructor
public class AutolabelPresence {

    /**
     * 자동 생성 라벨로 인정하는 {@code LBL_SRC_CD} 목록 — 오토라벨 산출물 판정의 단일 지점.
     * 값은 {@link LsDataLbl} 상수를 그대로 쓴다(문자열 리터럴을 여기서 새로 만들지 않는다).
     */
    public static final List<String> AUTOLABEL_SRC_CDS = List.of(LsDataLbl.SRC_YOLO, LsDataLbl.SRC_SAM2);

    /** 존재 확인은 한 건만 읽고 끝낸다 — 프레임이 수천인 영상에서 전량을 훑지 않는다. */
    private static final PageRequest FIRST_ONE = PageRequest.of(0, 1);

    private final LsDataLblRepository labelRepository;

    /**
     * 자동 생성 라벨이 1건이라도 있는가. {@code rawSn} 이 null 이면 {@code false}.
     *
     * <p><b>{@link #count} 로 위임하지 않는다</b> — 존재 확인은 첫 한 건에서 조기 종료돼야 한다
     * (수용기준). 카운트를 세고 0 과 비교하면 같은 답을 훨씬 비싸게 얻는다.
     *
     * <p>트랜잭션 경계를 두지 않는다 — 호출부가 트랜잭션 안일 수도 밖일 수도 있고, 단건 조회라
     * 리포지토리 자체 트랜잭션으로 충분하다(중첩 커넥션 요구 금지).
     */
    public boolean exists(Long rawSn) {
        if (rawSn == null) {
            return false;
        }
        return !labelRepository.findAutoLabelLblSnsByRawSn(rawSn, AUTOLABEL_SRC_CDS, FIRST_ONE).isEmpty();
    }

    /**
     * 자동 생성 라벨 건수(사람이 그린 라벨 제외). {@code rawSn} 이 null 이면 0.
     *
     * <p>판정 근거를 로그·진단에 남기는 호출부가 있어 boolean 말고 건수도 노출한다. 화면 노출 여부를
     * 여기서 정하지 않는다 — 이 클래스는 <b>산출물이 있는가</b> 만 답한다.
     */
    public long count(Long rawSn) {
        if (rawSn == null) {
            return 0L;
        }
        return labelRepository.countAutoLabelByRawSn(rawSn, AUTOLABEL_SRC_CDS);
    }
}
