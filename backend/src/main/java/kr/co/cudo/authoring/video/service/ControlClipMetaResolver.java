package kr.co.cudo.authoring.video.service;

import kr.co.cudo.authoring.common.util.LogSanitizer;
import kr.co.cudo.authoring.dataset.util.ShootingEnvironmentVocabulary;
import kr.co.cudo.authoring.dataset.util.TimeOfDaySeasonDeriver;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.entity.MngClipEvntLst;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * 관제 이벤트리스트({@link MngClipEvntLst})의 촬영환경·개인정보유형 코드값을 저작도구 값으로 해석하는
 * <b>단일 지점</b>. 상태·DB 접근이 없는 순수 함수라 트랜잭션 경계를 만들지 않는다.
 *
 * <p><b>채택 대상은 3개</b>: {@code HR_TYPE_CD}(시간대) · {@code SESN_CD}(계절) ·
 * {@code PRVC_TYPE_CD}(개인정보유형).
 *
 * <p><b>★ 날씨({@code WTHR_CD})는 관제에서 받지 않는다 — 저작도구 수동 입력이 유일한 원천</b>
 * (2026-07-31 사용자 확정, 되돌리지 말 것). 이유는 <b>코드값↔표시명 대응표가 없기 때문</b>이다:
 * 관제 {@code WTHR_CD} 는 코드값(예: {@code CLEAR})이고 저작도구 허용 어휘
 * ({@link ShootingEnvironmentVocabulary#WEATHERS})는 한글 표시명(맑음/흐림/비/눈/안개)이라 영구 미매칭이며,
 * 대응표 없이 변환하면 그 변환 자체가 추정(self-fill)이 된다. 관제값을 <b>보지 않으므로</b> 미매칭 WARN 도
 * 남지 않는다(구 구현은 적재 1건마다 WARN 1건을 영구히 쌓았다). 날씨는 {@code EnvironmentMetaService}
 * (작업자 수동 입력)만이 {@code LS_DATA_RAW.WTHR_NM} 을 채운다.
 *
 * <p><b>왜 allowlist 판정뿐인가</b>: 관제 코드값({@code HR_TYPE_CD}·{@code SESN_CD}·
 * {@code PRVC_TYPE_CD})과 저작도구 코드도메인의 <b>대응표는 아직 확정되지 않았다</b>(관제팀 확인 필요).
 * 대응표 없이 값을 변환하면 그 변환 자체가 추정(self-fill)이 되므로, 저작도구 허용 어휘
 * ({@link ShootingEnvironmentVocabulary})와 <b>그대로 일치하는 값만</b> 채택하고 나머지는 채택하지 않는다.
 *
 * <p><b>3분기</b>:
 * <ol>
 *   <li><b>채택</b> — 관제값이 허용 어휘에 있으면 그 값을 쓴다.</li>
 *   <li><b>폴백 + WARN</b> — 관제값이 있으나 미매칭이면 채택하지 않고 WARN 으로 드러낸다.
 *       조용히 넘어가면 대응표 미확정 사실이 관측되지 않는다. 미검증 문자열이 {@code LS_DATA_RAW} 에
 *       실려 동결 스냅샷·export JSON·데이터마트 뷰까지 흘러가는 것도 이 분기가 막는다(CWE-20).</li>
 *   <li><b>기존 폴백</b> — 관제값이 없으면(null/blank) WARN 없이 기본값으로 떨어진다.</li>
 * </ol>
 * ②③의 기본값은 축마다 다르다 — <b>촬영환경은 "미상(null)"</b>, <b>개인정보유형은 fail-closed
 * {@code PRVC}</b>(아래 {@link #resolvePrvcType} 참조).
 *
 * <p><b>촬영환경 폴백 = "미상(null) 유지"이지 파생값 영속이 아니다</b>: ②·③에서 촬영일시 파생
 * ({@link TimeOfDaySeasonDeriver})을 적재값으로 쓰지 <b>않는다</b>. 파생은 조회 시점 프리필 전용이며
 * ({@code EnvironmentMetaService}), {@code LS_DATA_RAW} 에 영속하면 ⓐ출처 구분자가 사라져 추정값이
 * 수동입력(MANUAL)으로 승격되고 ⓑ승인 동결·export·데이터마트로 그대로 전파된다(E-ISSUE-42 —
 * "동결/산출 경로에 파생 폴백을 배선하지 말 것").
 *
 * <p><b>로그(CWE-117)</b>: 관제 DB 값은 신뢰 경계 밖이므로 WARN 출력 전 {@link LogSanitizer} 로
 * 제어문자 제거 + 길이 상한을 적용한다.
 */
@Slf4j
@Component
public class ControlClipMetaResolver {

    /** WARN 로그에 남길 관제 코드값의 최대 길이 — 비정상 입력의 로그 폭주 차단. */
    private static final int LOG_VALUE_MAX_LENGTH = 64;

    /** 적재에 채택 가능한 개인정보 처리 유형. {@code UNKNOWN}(마이그레이션 잠정값)은 채택 대상이 아니다. */
    private static final Set<String> PRVC_TYPES = Set.of(
            LsDataRaw.PRVC_TYPE_ANONY, LsDataRaw.PRVC_TYPE_PRVC, LsDataRaw.PRVC_TYPE_PSDO);

    /**
     * 관제 촬영환경 코드값을 해석한다. 필드별로 독립 판정하며, 채택하지 못한 필드는 {@code null}(미상)이다.
     *
     * <p><b>날씨({@code WTHR_CD})는 읽지 않는다</b> — 클래스 주석의 "날씨는 관제에서 받지 않는다" 참조.
     *
     * @param evntLst 관제 이벤트리스트 행 (미매칭이면 {@code null} — 적재를 막지 않는 기존 계약)
     * @return 채택된 촬영환경. 채택값이 없으면 {@link ShootingEnv#NONE} 과 동등한 전(全) null 레코드
     */
    public ShootingEnv resolve(MngClipEvntLst evntLst) {
        if (evntLst == null) {
            return ShootingEnv.NONE;
        }
        return new ShootingEnv(
                adopt(evntLst.getHrTypeCd(), ShootingEnvironmentVocabulary.TIME_OF_DAYS, "HR_TYPE_CD"),
                adopt(evntLst.getSesnCd(), ShootingEnvironmentVocabulary.SEASONS, "SESN_CD"));
    }

    /**
     * 관제 개인정보 처리 유형을 해석한다.
     *
     * <p><b>★ 폴백은 {@code PRVC}(fail-closed)</b> — 2026-07-31 사용자 확정. 관제팀 확인 결과
     * <b>관제서버는 {@code PRVC_TYPE_CD} 를 실제로 채워 보내지 않는다</b>(컬럼은 ERD-024 에 있으나 데이터 없음).
     * 입력이 없으면 원천영상을 <b>"개인정보가 있고 익명처리되지 않은 것"</b>으로 본다. 반대로 관제를 거치지
     * 않고 올라오는 영상(이미 익명·가명 처리된 영상)은 값이 들어오므로 ①채택 분기에서 그대로 존중된다.
     *
     * <p><b>의도된 회귀</b>: 구 폴백은 {@code ANONY} 였고 현행 dev/stg/prd 데이터는 100% 가 이 분기라
     * 전 영상의 {@code PRVC_TYPE_CD} 가 {@code ANONY}→{@code PRVC} 로 바뀐다. 그 결과
     * {@link LsDataRaw#needsDeidentify()} 가 true 가 되어 <b>비식별본이 없는 영상의 프레임 조회는 404</b>
     * 로 닫힌다({@code FrameImageService} 의 "ANONY + DEID 미준비 → 원본 폴백" 분기가 닫힘).
     * 이는 마스킹 전 원본 노출을 막는 <b>의도된 fail-closed</b>다(CWE-359).
     *
     * @param evntLst 관제 이벤트리스트 행 ({@code null} 허용)
     * @return {@code ANONY}/{@code PRVC}/{@code PSDO} 중 하나. 값 없음·미매칭이면 {@code PRVC}
     */
    public String resolvePrvcType(MngClipEvntLst evntLst) {
        if (evntLst == null) {
            return LsDataRaw.PRVC_TYPE_PRVC;
        }
        String adopted = adopt(evntLst.getPrvcTypeCd(), PRVC_TYPES, "PRVC_TYPE_CD");
        return adopted != null ? adopted : LsDataRaw.PRVC_TYPE_PRVC;
    }

    /**
     * 관제 코드값 1건의 3분기 판정 — 허용 어휘에 있으면 채택, 미매칭이면 WARN 후 미채택, 없으면 조용히 미채택.
     *
     * @param value   관제 원본 코드값 (null/blank 허용)
     * @param allowed 저작도구 허용 어휘
     * @param column  관제 컬럼 물리명 (로그 식별용 — 상수라 정제 불필요)
     * @return 채택값(trim 됨) 또는 {@code null}
     */
    private String adopt(String value, Set<String> allowed, String column) {
        if (value == null || value.isBlank()) {
            return null; // ③ 값 없음 — 기존 동작 그대로(WARN 아님).
        }
        String normalized = value.trim();
        if (allowed.contains(normalized)) {
            return normalized; // ① 채택.
        }
        // ② 미매칭 — 채택하지 않고 드러낸다. 관제 원본값은 신뢰 경계 밖이라 정제 후 출력(CWE-117).
        log.warn("[ControlClipMeta] unmapped control code — not adopted column={} value={}",
                column, LogSanitizer.sanitize(normalized, LOG_VALUE_MAX_LENGTH));
        return null;
    }

    /**
     * 적재에 채택된 촬영환경 값. 각 필드는 {@code LS_DATA_RAW} 의 {@code DAY_NGT_CD}·{@code SESN_CD}
     * 에 대응하며 {@code null} 은 "미상"(조회 시 파생 프리필 대상)을 뜻한다.
     *
     * <p><b>날씨({@code WTHR_NM}) 자리는 없다</b> — 관제에서 받지 않아 항상 null 이 될 값을 레코드에 두면
     * 죽은 필드가 되고, 나중에 누군가 "왜 안 채워지지?" 하며 되살릴 여지를 남긴다(클래스 주석 참조).
     */
    public record ShootingEnv(String dayNgtCd, String sesnCd) {

        /** 채택값이 하나도 없는 경우. */
        public static final ShootingEnv NONE = new ShootingEnv(null, null);
    }
}
