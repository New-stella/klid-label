package kr.co.cudo.authoring.upload.dto;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import kr.co.cudo.authoring.video.entity.LsDataIngest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 내부 업로드 세션 생성 요청의 <b>입력 검증</b> 단위 테스트 (CWE-20).
 *
 * <p>컨트롤러의 {@code @Valid} 가 이 제약을 그대로 실행하므로, 여기서 위반이 잡히면 HTTP 400 이다.
 * 길이·범위는 {@code LS_DATA_INGEST}(V147) DDL 과 1:1 이어야 한다 — 어긋나면 <b>완료 시점 INSERT</b>
 * 에서 값 초과로 500 이 나고 0바이트 임시 파일이 남는다(구 {@code cctvId} 실사고와 동형).
 *
 * <p>컨테이너 없이 Hibernate Validator 만 띄워 결정적으로 검증한다.
 */
class InternalUploadCreateRequestValidationTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void initValidator() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void closeValidator() {
        factory.close();
    }

    /** 필수 5(파일명·클립ID·CCTV ID·지자체코드) 만 채운 최소 요청 — 나머지는 전부 선택이다. */
    private static InternalUploadCreateRequest minimal() {
        return new InternalUploadCreateRequest(
                "clip.mp4", "VMS-1", "CCTV-1", null, "1168000000", null,
                null, null, null, null,
                null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null);
    }

    private Set<String> violatedFields(InternalUploadCreateRequest req) {
        return validator.validate(req).stream()
                .map(v -> v.getPropertyPath().toString())
                .collect(Collectors.toSet());
    }

    @Test
    @DisplayName("기술메타를_모두_비워도_검증을_통과한다 — 비운_키는_ffprobe_폴백_대상이다")
    void minimalRequestWithNoTechnicalMetaIsValid() {
        assertThat(violatedFields(minimal())).isEmpty();
    }

    @Test
    @DisplayName("필수값이_비면_해당_필드가_전부_위반으로_잡힌다 — 400")
    void blankRequiredFieldsAreRejected() {
        InternalUploadCreateRequest empty = new InternalUploadCreateRequest(
                " ", " ", " ", null, " ", null,
                null, null, null, null,
                null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null);

        assertThat(violatedFields(empty))
                .contains("fileName", "vmsClipId", "cctvId", "lclgvCd");
    }

    @Test
    @DisplayName("출처유형은_선택이지만_allowlist_밖이면_400 — 미지정은_USER_ULD_기본값")
    void srcTypeIsOptionalButAllowlisted() {
        assertThat(minimal().srcTypeOrDefault()).isEqualTo(LsDataIngest.SRC_TYPE_USER_ULD);
        for (String allowed : LsDataIngest.UPLOAD_SRC_TYPES) {
            assertThat(violatedFields(withSrcType(allowed))).as("srcType=%s", allowed).isEmpty();
        }
        assertThat(violatedFields(withSrcType("HACKED"))).isNotEmpty();
    }

    @Test
    @DisplayName("M3_AUGMENTED는_인입_입력면_allowlist_밖이다 — 파생은_저작도구가_만든다")
    void augmentedIsNotAcceptableAsIngestInput() {
        // 증강 파생본은 저작도구가 직접 만들며 ORGNL_RAW_SN 으로 부모를 가리킨다. 인입으로 받으면
        // ORGNL_RAW_SN 이 null 인데 출처만 파생인 LS_DATA_RAW 행이 생겨 파생 판별 축과 어긋난다.
        assertThat(violatedFields(withSrcType("AUGMENTED"))).isNotEmpty();
        assertThat(LsDataIngest.UPLOAD_SRC_TYPES).doesNotContain("AUGMENTED");
        // 적재면(관제 수신값 판정)은 5종을 유지한다 — 입력면과 적재면은 다른 관심사다.
        assertThat(LsDataIngest.ALLOWED_SRC_TYPES).contains("AUGMENTED");
        assertThat(LsDataIngest.ALLOWED_SRC_TYPES).containsAll(LsDataIngest.UPLOAD_SRC_TYPES);
    }

    @Test
    @DisplayName("LOW_빈문자열은_미지정과_같게_취급한다 — FE가_빈_입력을_보내도_이유없는_400이_아니다")
    void emptyStringsAreTreatedAsUnspecified() {
        // 서비스는 ""(빈 값)을 "미지정"으로 보는데 @Pattern 은 400 을 냈다 — 계약을 통일한다.
        assertThat(violatedFields(withSrcType(""))).isEmpty();
        assertThat(violatedFields(withFileFmt(""))).isEmpty();
        assertThat(violatedFields(withFileFmt("mp4"))).isEmpty();
        assertThat(violatedFields(withFileFmt("mp4!"))).contains("fileFmt");
    }

    @Test
    @DisplayName("클립ID가_경로문자나_65자면_400 — 이_값이_저장_파일명이_된다")
    void clipIdIsPathSafeAndBounded() {
        for (String evil : new String[]{"../etc/passwd", "a/b", "a\\b", "clip id", "A".repeat(65)}) {
            assertThat(violatedFields(withClipId(evil))).as("clipId=%s", evil).contains("vmsClipId");
        }
        assertThat(violatedFields(withClipId("A".repeat(64)))).isEmpty();
    }

    @Test
    @DisplayName("좌표_높이_각도가_DDL_범위를_벗어나면_400")
    void geoFieldsAreRangeChecked() {
        // WGS84_LAT DECIMAL(10,7) — 값 범위(-90~90) + 소수 7자리
        assertThat(violatedFields(withLat(new BigDecimal("91.0")))).contains("wgs84Lat");
        assertThat(violatedFields(withLat(new BigDecimal("37.12345678")))).contains("wgs84Lat");
        assertThat(violatedFields(withLat(new BigDecimal("37.4979200")))).isEmpty();
        // MAIN_SURV_PAN_ANG — 0~360
        assertThat(violatedFields(withPanAngle(361))).contains("mainSurvPanAng");
        assertThat(violatedFields(withPanAngle(-1))).contains("mainSurvPanAng");
        assertThat(violatedFields(withPanAngle(360))).isEmpty();
    }

    @Test
    @DisplayName("문자열_기술메타가_DDL_길이를_넘으면_400 — 관제일지는_4000자")
    void stringLengthsMatchDdl() {
        assertThat(violatedFields(withMntrCn("가".repeat(4001)))).contains("mntrCn");
        assertThat(violatedFields(withMntrCn("가".repeat(4000)))).isEmpty();
    }

    @Test
    @DisplayName("영상길이_프레임수는_음수면_400")
    void negativeTechnicalMetaRejected() {
        assertThat(violatedFields(withDuration(new BigDecimal("-1")))).contains("vdoLenSec");
        assertThat(violatedFields(withDuration(new BigDecimal("600")))).isEmpty();
    }

    // [폐기] 검증이벤트유형이_허용_6종_밖이면_400 — 단일_진실원은_LsDataIngest_상수다
    //
    // ★ 2026-08-06 사용자 확정으로 6종 allowlist 판정 자체가 폐기됐다(CLAUDE.md "우리 쓰기 통로(dev
    //   업로드)도 6종 allowlist 를 폐기했다 — '6+수동입력'"). 화면에 직접 입력이 열렸고, 판정은
    //   목록이 아니라 형식(소문자·숫자·밑줄 + VRFC_EVNT_TYPE_MAX_LENGTH 20자, 단일 진실원
    //   LsDataIngest.isVrfcEvntTypeFormatValid)으로 좁혀졌다. 이 반전을 반영한 회귀 가드가 이미
    //   TusUploadServiceTest(★6종_밖이어도_형식만_맞으면_통과한다_구_allowlist_폐기)에 있는데, 이
    //   DTO 레벨 테스트만 구 기대결과("6종 밖이면 무조건 400")를 들고 있어 FULL 회귀에서 실패했다
    //   ("FIRE_" 는 정규화(trim+소문자)를 거쳐 "fire_" 가 되고, 이는 형식상 유효해 위반이 0건이다).
    //   구 단언을 지우지 않고 아래 두 테스트로 이관한다 — ①형식만 맞으면 6종 밖이어도 통과 ②공백·
    //   특수문자 등 형식 자체를 벗어나는 입력(SQLi 문자 포함)은 여전히 거부.

    @Test
    @DisplayName("★이벤트유형코드는_미입력이어도_통과한다 — 관제_미송신_상태의_재현이라_필수가_아니다")
    void eventTypeCodeIsOptional() {
        // 관제가 아직 이 값을 보내지 않는 것이 현재 형상이라, 업로드가 그 상태를 그대로 재현할 수
        //   있어야 한다. 빈 문자열도 "미지정" 이다 — 화면이 선택 안 함을 "" 로 보낸다.
        for (String unset : new String[]{null, ""}) {
            assertThat(violatedFields(withEvntTypeCd(unset)))
                    .as("미지정 %s", unset).doesNotContain("evntTypeCd");
        }
    }

    @Test
    @DisplayName("★이벤트유형코드는_값_목록으로_좁히지_않는다 — 미등록_비규격_코드도_통과한다")
    void eventTypeCodeIsNotRestrictedToKnownCodes() {
        // 관제 코드 체계는 우리 소유가 아니다. 사본 allowlist 를 들면 관제가 코드를 넓힐 때
        //   정상 값을 우리가 먼저 막는다(검증이벤트유형에서 이미 폐기한 실패 방식).
        for (String code : new String[]{"EV01000101", "INTRUSION", "X9", "A_B_1"}) {
            assertThat(violatedFields(withEvntTypeCd(code)))
                    .as("형식 유효값 %s", code).doesNotContain("evntTypeCd");
        }
    }

    @Test
    @DisplayName("★이벤트유형코드가_컬럼폭_20자를_넘으면_400이다 — DB오류로_새지_않는다")
    void overlongEventTypeCodeIsRejected() {
        // EVNT_TYPE_CD 는 VARCHAR(20). 입구에서 막지 않으면 INSERT 시점 DB 오류(500)가 된다.
        assertThat(violatedFields(withEvntTypeCd("E".repeat(21)))).contains("evntTypeCd");
        assertThat(violatedFields(withEvntTypeCd("E".repeat(20)))).doesNotContain("evntTypeCd");
    }

    @Test
    @DisplayName("★이벤트유형코드에_공백이나_특수문자가_섞이면_형식_위반으로_400 — 로그인젝션_차단")
    void malformedEventTypeCodeIsRejected() {
        // 이 값은 조회 파라미터·로그에 그대로 실린다(CWE-117). 소문자도 형식 위반이다 —
        //   화면이 대문자로 올려 보내므로 소문자가 서버에 도달하는 것은 화면 우회 경로다.
        for (String evil : new String[]{"EV 01", "ev01000101", "EV-01", "EV01'; DROP TABLE--", "EV\n01"}) {
            assertThat(violatedFields(withEvntTypeCd(evil))).as("evntTypeCd=%s", evil).contains("evntTypeCd");
        }
    }

    @Test
    @DisplayName("검증이벤트유형은_형식만_맞으면_6종_밖이어도_통과한다 — 구_allowlist_판정_폐기")
    void wellFormedVerificationEventTypeOutsideKnownSetIsAccepted() {
        // "unknown"·"fire_" 는 구 테스트에서 거부 대상이었으나, 소문자·숫자·밑줄 형식만 맞으면
        //   6종 밖이라도 통과하는 것이 현재 정책이다(TusUploadServiceTest 동일 근거 테스트 참조).
        for (String wellFormed : new String[]{"unknown", "fire_", "custom_event", "abc123"}) {
            assertThat(violatedFields(withVrfcEvntType(wellFormed)))
                    .as("형식 유효값 %s", wellFormed).doesNotContain("vrfcEvntTypeAllowed");
        }
    }

    @Test
    @DisplayName("검증이벤트유형에_공백이나_특수문자가_섞이면_형식_위반으로_400 — SQLi_문자도_형식으로_차단된다")
    void malformedVerificationEventTypeIsRejected() {
        // 형식(소문자·숫자·밑줄) 밖의 문자는 여전히 거부된다 — allowlist 가 아니라 문자 집합 제약이
        //   이 값을 외부 벤더 요청 바디·로그에 그대로 싣기 때문(CWE-20/117). "FIRE_" 는 정규화(소문자화)
        //   후 "fire_" 로 형식이 유효해져 더 이상 이 케이스가 아니다(위 테스트로 이관 — 정규화가 검증
        //   보다 먼저 적용되는 것이 설계다, InternalUploadCreateRequest 클래스 주석 참조).
        for (String evil : new String[]{"car crash", "fire fall", "fire'; DROP TABLE LS_DATA_INGEST--"}) {
            assertThat(violatedFields(withVrfcEvntType(evil)))
                    .as("vrfcEvntTypeCd=%s", evil).contains("vrfcEvntTypeAllowed");
        }
    }

    @Test
    @DisplayName("허용_6종은_대소문자_공백이_섞여도_정규화되어_통과한다")
    void allowedVerificationEventTypesPassAfterNormalization() {
        for (String type : LsDataIngest.VRFC_EVNT_TYPES) {
            assertThat(violatedFields(withVrfcEvntType(type))).as("허용값 %s", type).isEmpty();
            assertThat(violatedFields(withVrfcEvntType(" " + type.toUpperCase(java.util.Locale.ROOT) + " ")))
                    .as("대문자·공백 변형 %s", type).isEmpty();
        }
    }

    @Test
    @DisplayName("검증이벤트유형은_선택값이라_미지정_공백은_통과하고_null_로_해석된다")
    void blankVerificationEventTypeIsUnspecified() {
        assertThat(violatedFields(withVrfcEvntType(null))).isEmpty();
        assertThat(violatedFields(withVrfcEvntType(""))).isEmpty();
        assertThat(violatedFields(withVrfcEvntType("   "))).isEmpty();
        assertThat(withVrfcEvntType("   ").vrfcEvntTypeCdOrNull()).isNull();
        assertThat(withVrfcEvntType(" Fire ").vrfcEvntTypeCdOrNull()).isEqualTo("fire");
    }

    // --- 픽스처 헬퍼 (record 라 위치 인자가 길어 축약) ---

    private static InternalUploadCreateRequest withVrfcEvntType(String vrfcEvntTypeCd) {
        return new InternalUploadCreateRequest("clip.mp4", "VMS-1", "CCTV-1", null, "1168000000",
                null, null, null, null, null,
                null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, vrfcEvntTypeCd);
    }

    private static InternalUploadCreateRequest withEvntTypeCd(String evntTypeCd) {
        return new InternalUploadCreateRequest("clip.mp4", "VMS-1", "CCTV-1", null, "1168000000",
                null, null, null, null, null,
                null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, evntTypeCd, null, null);
    }

    private static InternalUploadCreateRequest withSrcType(String srcType) {
        return new InternalUploadCreateRequest("clip.mp4", "VMS-1", "CCTV-1", srcType, "1168000000",
                LocalDateTime.now(), null, null, null, null,
                null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null);
    }

    private static InternalUploadCreateRequest withFileFmt(String fileFmt) {
        return new InternalUploadCreateRequest("clip.mp4", "VMS-1", "CCTV-1", null, "1168000000",
                null, fileFmt, null, null, null,
                null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null);
    }

    private static InternalUploadCreateRequest withClipId(String clipId) {
        return new InternalUploadCreateRequest("clip.mp4", clipId, "CCTV-1", null, "1168000000",
                null, null, null, null, null,
                null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null);
    }

    private static InternalUploadCreateRequest withLat(BigDecimal lat) {
        return new InternalUploadCreateRequest("clip.mp4", "VMS-1", "CCTV-1", null, "1168000000",
                null, null, null, null, null,
                null, null, null, null, null, null, null, null, null,
                lat, null, null, null, null, null, null, null, null, null);
    }

    private static InternalUploadCreateRequest withPanAngle(Integer angle) {
        return new InternalUploadCreateRequest("clip.mp4", "VMS-1", "CCTV-1", null, "1168000000",
                null, null, null, null, null,
                null, null, null, null, null, null, null, null, null,
                null, null, null, null, angle, null, null, null, null, null);
    }

    private static InternalUploadCreateRequest withMntrCn(String mntrCn) {
        return new InternalUploadCreateRequest("clip.mp4", "VMS-1", "CCTV-1", null, "1168000000",
                null, null, null, null, null,
                null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, mntrCn, null);
    }

    private static InternalUploadCreateRequest withDuration(BigDecimal vdoLenSec) {
        return new InternalUploadCreateRequest("clip.mp4", "VMS-1", "CCTV-1", null, "1168000000",
                null, null, null, null, null,
                vdoLenSec, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null);
    }
}
