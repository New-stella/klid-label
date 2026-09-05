package kr.co.cudo.authoring.video.service;

import kr.co.cudo.authoring.video.dto.MarkingImportIngestCommand;
import kr.co.cudo.authoring.video.entity.LsDataRaw;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Pattern;

/**
 * 마킹 이관 적재 커맨드의 <b>입구 검증</b> — 판정을 한 곳에 모은다.
 *
 * <h3>왜 적재 직전에 막는가</h3>
 * <p>여기서 통과시킨 값은 되돌리기 어려운 자리로 간다. 식별자는 중복 반입을 막는 유일 제약의 키가 되고,
 * 파일 경로는 그 영상에 딸린 비식별본·프레임·학습데이터 산출물이 쌓이는 자리의 근거가 된다(ADR-053).
 * 컬럼 폭을 넘는 값은 INSERT 시점 DB 오류가 되어 <b>건별 사유가 아니라 알 수 없는 실패</b>로 마감된다.
 *
 * <h3>사람이 지정하는 넷은 <b>비울 수 없다</b></h3>
 * <p>DFEAT-060 이 "이벤트 유형과 지자체 코드와 카메라 식별자와 개인정보 유형이 그것이며
 * <b>촬영일시는 선택</b>"이라고 가른다. 넷 중 하나라도 비면 적재 자체를 막는다 — 특히 이벤트 유형은
 * 비어도 적재는 되지만 그 영상은 마킹 진입에서 막혀 <b>비식별만 끝난 채 멈춘 영상</b>이 된다. 조용히
 * 멈추는 것보다 건별 사유로 드러나는 편이 낫다.
 * <p>⚠ 관제 인입 경로는 같은 상황에서 <b>null 로 적재하고 성공</b>시킨다({@code TrainingVideoIngestTx}).
 * 두 경로의 판정이 다른 것은 <b>의도</b>다 — 그쪽은 값의 주인이 관제라 우리가 되물을 수 없지만,
 * 이쪽은 사람이 화면에서 지금 지정하는 값이라 되물을 수 있다. 일관성을 이유로 통일하지 말 것.
 *
 * <h3>경로는 <b>열지 않는다</b></h3>
 * <p>파일이 실재하는지·허용 루트 안인지는 확인하지 않는다. 영상 파일을 저작도구 저장소로 복사하는 것도
 * 그 위치를 정하는 것도 이관 쪽 몫이고, 이 도메인은 외부 폴더를 직접 열지 않는다. 여기서 보는 것은
 * <b>저장할 값 자체의 형태</b>뿐이다 — 상위 이동({@code ..})이 섞인 값은 저장해 두면 나중에 그 값을
 * 읽는 모든 곳이 의도하지 않은 자리를 가리키므로 거부한다(CWE-22).
 *
 * @design ADR-053
 * @design DFEAT-060
 */
final class MarkingImportIngestValidator {

    /** {@code LS_DATA_RAW.RAW_FILE_PATH_NM} 컬럼 폭. */
    private static final int RAW_FILE_PATH_MAX = 500;

    /** {@code LS_DATA_RAW.VMS_CCTV_ID} 컬럼 폭. */
    private static final int VMS_CCTV_ID_MAX = 64;

    /** {@code LS_DATA_RAW.EVNT_TYPE_CD} · {@code LCLGV_CD} 컬럼 폭(코드값 표준도메인). */
    private static final int CODE_MAX = 20;

    /**
     * 이벤트 유형 코드 표기 — {@code EV} + 숫자 8자리(예 {@code EV01000101}).
     *
     * <p>⚠ <b>값 목록이 아니라 모양만 본다.</b> 미등록·비규격 이벤트 코드를 버리지
     * 않는 것은 구속 규칙이다 — 라벨이 코드 원문이라 옵션에서 제거하면 그 영상이 필터로
     * <b>도달 불가능</b>해진다. 특정 코드 목록(allowlist)으로 좁히지 말 것.
     */
    private static final Pattern EVNT_TYPE_CD_FORMAT = Pattern.compile("^EV[0-9]{8}$");

    /** 지자체 코드 표기 — 숫자 1~10자리(예 {@code 4113500000}). 역시 모양만 본다. */
    private static final Pattern LCLGV_CD_FORMAT = Pattern.compile("^[0-9]{1,10}$");

    /**
     * 카메라 식별자 허용 문자 — 영문·숫자·{@code _}·{@code -} 만.
     *
     * <p>공백·개행·제어문자를 막는 것이 목적이다. 이 값은 영상과 함께 화면·산출물·운영 로그로
     * 퍼져 나가므로 개행이 섞이면 로그 인젝션(CWE-117)과 표시 깨짐이 된다.
     */
    private static final Pattern VMS_CCTV_ID_FORMAT = Pattern.compile("^[A-Za-z0-9_-]+$");

    private MarkingImportIngestValidator() {
    }

    /**
     * 커맨드를 검증하고 <b>공백을 정규화한 사본</b>을 돌려준다.
     *
     * <p>공백만 담긴 값을 그대로 실으면 이후 판정이 "값 있음"으로 오인한다 — 관제 인입 경로가
     * {@code trimToNull} 로 같은 문제를 막는 것과 같은 이유다. 다만 이 경로에서는 필수 넷이 공백이면
     * null 로 눕히는 대신 <b>거부</b>한다(위 클래스 설명).
     *
     * @throws IllegalArgumentException 적재에 필요한 값이 없거나 저장할 수 없는 형태일 때
     */
    static MarkingImportIngestCommand validate(MarkingImportIngestCommand command) {
        if (command == null) {
            throw new IllegalArgumentException("마킹 이관 적재 커맨드가 비어 있습니다.");
        }
        String vmsClipId = requireText(command.vmsClipId(), "영상 식별자");
        requireMaxLength(vmsClipId, LsDataRaw.VMS_CLIP_ID_MAX, "영상 식별자");
        requireNoPathSeparator(vmsClipId);

        String vmsCctvId = requireText(command.vmsCctvId(), "카메라 식별자");
        requireMaxLength(vmsCctvId, VMS_CCTV_ID_MAX, "카메라 식별자");
        requireFormat(vmsCctvId, VMS_CCTV_ID_FORMAT, "카메라 식별자");

        String evntTypeCd = requireText(command.evntTypeCd(), "이벤트 유형 코드");
        requireMaxLength(evntTypeCd, CODE_MAX, "이벤트 유형 코드");
        requireFormat(evntTypeCd, EVNT_TYPE_CD_FORMAT, "이벤트 유형 코드");

        String lclgvCd = requireText(command.lclgvCd(), "지자체 코드");
        requireMaxLength(lclgvCd, CODE_MAX, "지자체 코드");
        requireFormat(lclgvCd, LCLGV_CD_FORMAT, "지자체 코드");

        String prvcTypeCd = requireText(command.prvcTypeCd(), "개인정보 유형");
        if (!MarkingImportIngestCommand.ALLOWED_PRVC_TYPES.contains(prvcTypeCd)) {
            // 값 자체를 메시지에 담지 않는다 — 이 문자열이 로그·응답으로 흐른다(CWE-117).
            throw new IllegalArgumentException("개인정보 유형이 허용값이 아닙니다.");
        }

        String rawFilePathNm = requireText(command.rawFilePathNm(), "영상 파일 경로");
        requireMaxLength(rawFilePathNm, RAW_FILE_PATH_MAX, "영상 파일 경로");
        requireNoParentTraversal(rawFilePathNm);

        return new MarkingImportIngestCommand(vmsClipId, vmsCctvId, evntTypeCd, lclgvCd,
                prvcTypeCd, rawFilePathNm, command.shtDt());
    }

    private static String requireText(String value, String label) {
        String trimmed = value == null ? null : value.trim();
        if (trimmed == null || trimmed.isEmpty()) {
            throw new IllegalArgumentException(label + "이(가) 비어 있습니다.");
        }
        return trimmed;
    }

    private static void requireMaxLength(String value, int max, String label) {
        if (value.length() > max) {
            // 컬럼 폭을 넘는 값을 잘라 넣지 않는다 — 잘린 식별자는 다른 영상과 부딪히고,
            // 잘린 경로는 존재하지 않는 자리를 가리킨다.
            throw new IllegalArgumentException(label + "이(가) 허용 길이를 넘습니다.");
        }
    }

    /**
     * 확정된 <b>표기 규칙</b>을 만족하는지 — 판정 함수는 이 검증기 한 곳에만 둔다.
     *
     * <p>같은 판정을 호출부마다 복제하면 한쪽만 갱신돼 어긋난다. 어긋난 뒤에는 둘 중 어느 쪽이 진실원인지 알 수 없다(이 저장소의 반복 사고 패턴). dev 업로드의 검증이벤트유형이 DTO 와 서비스
     * 두 단에서 <b>같은 함수</b>를 부르는 것과 같은 원칙이다.
     *
     * <p><b>입력값을 메시지에 echo 하지 않는다</b> — 이 문자열은 응답·로그로 흘러간다
     * (CWE-209 · CWE-117). 어느 항목이 틀렸는지만 알리고 값은 담지 않는다.
     */
    private static void requireFormat(String value, Pattern format, String label) {
        if (!format.matcher(value).matches()) {
            throw new IllegalArgumentException(label + "의 표기 형식이 올바르지 않습니다.");
        }
    }

    /**
     * 식별자에 경로 구분자·NUL 이 섞이지 않았는지 — 이 값은 <b>영상 파일 이름에서 확장자를 뗀 것</b>이라
     * 구분자가 들어 있을 수 없다. 들어 있다면 이름이 아니라 경로가 흘러든 것이다(CWE-22).
     */
    private static void requireNoPathSeparator(String vmsClipId) {
        if (vmsClipId.indexOf('/') >= 0 || vmsClipId.indexOf('\\') >= 0 || vmsClipId.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("영상 식별자에 경로 구분자가 포함될 수 없습니다.");
        }
    }

    /**
     * 저장할 경로 문자열에 상위 이동({@code ..})이 남아 있지 않은지.
     *
     * <p>정규화한 결과를 대신 저장하지 않는다 — 호출부가 정한 자리를 우리가 조용히 바꾸면 실제로 복사한
     * 파일과 적재된 경로가 갈린다. 어긋나면 <b>거부</b>해 호출부가 고치게 한다.
     */
    private static void requireNoParentTraversal(String rawFilePathNm) {
        Path path;
        try {
            path = Paths.get(rawFilePathNm);
        } catch (InvalidPathException e) {
            throw new IllegalArgumentException("영상 파일 경로를 해석할 수 없습니다.");
        }
        for (Path segment : path) {
            if ("..".equals(segment.toString())) {
                throw new IllegalArgumentException("영상 파일 경로에 상위 이동이 포함될 수 없습니다.");
            }
        }
    }
}
