package kr.co.cudo.authoring.dataset.service;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;

/**
 * 통합 메타 스냅샷 멱등키({@code SNPSHT_HASH}) 계산기 — 정규화 문자열의 SHA-256(hex).
 *
 * <p>동결 대상 컬럼값을 <b>키 사전순 정렬</b>한 뒤 <b>길이-프리픽스(length-prefixed) 인코딩</b>으로
 * 이어붙인 정규화 문자열을 해싱한다. {@code LS_LABEL_VERSION.VERSION_HASH}(라벨 페이로드 SHA-256)
 * 방식을 준용하되, 컬럼형 스냅샷이므로 입력 맵의 순서·null 표현에 무관하게 <b>결정성</b>
 * (같은 논리 입력 → 같은 해시)과 <b>단사성</b>(다른 논리 입력 → 다른 해시)을 보장한다:
 * <ul>
 *   <li>키를 {@link TreeMap} 사전순으로 정렬 → 호출 측 맵의 iteration 순서에 무관.</li>
 *   <li>키·값 각각을 {@code {길이}:{문자열}} 자기구분(self-delimiting) 토큰으로 인코딩 → 값에
 *       구분자({@code '\n'}·{@code '='}·{@code ':'}) 가 섞여도 서로 다른 논리 입력이 같은
 *       정규화 문자열로 붕괴하지 않는다(해시 충돌 불가). 예: {@code {a:"x\nb=y"}} 와
 *       {@code {a:"x", b:"y"}} 는 옛 {@code key=value\n} 방식에선 동일 문자열이었으나,
 *       길이 프리픽스로 항상 구분된다.</li>
 *   <li>{@code null} 값은 실제 문자열 길이(≥0)가 될 수 없는 {@link #NULL_LEN}({@code "-1"}) 길이
 *       마커로 정규화 → 빈 문자열({@code "0:"})·리터럴 {@code "NULL"}({@code "4:NULL"}) 과 절대
 *       충돌하지 않는다.</li>
 *   <li>값은 이미 정규화된 문자열을 받는다(숫자/일시 포맷은 호출 측 책임) → 타입 직렬화 편차 배제.</li>
 * </ul>
 *
 * <p>해시 입력에는 관리 컬럼(ACTIVE_YN·RVW_CMPL_DT·REG_DT·REG_ID·PK·해시 자신)을 포함하지 않는다 —
 * 재승인 시각이 달라도 동결 <b>내용</b>이 같으면 같은 해시가 나와 멱등 재승인이 중복 없이 식별된다.
 */
@Component
public class SnapshotHasher {

    /** null 값 길이 마커 — 실제 문자열 길이(≥0)와 겹치지 않는 {@code -1} 로 표기해 빈문자열/리터럴과 구분. */
    static final String NULL_LEN = "-1";

    /**
     * 동결 필드 맵을 정규화·해싱하여 hex SHA-256 을 반환한다.
     *
     * @param fields 컬럼명 → 정규화된 값 문자열(값 null 허용). 순서 무관.
     * @return 64자 hex SHA-256
     */
    public String hash(Map<String, String> fields) {
        if (fields == null) {
            throw new IllegalArgumentException("fields 는 필수입니다.");
        }
        // 사전순 정렬로 순서 독립성 확보.
        TreeMap<String, String> sorted = new TreeMap<>(fields);
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : sorted.entrySet()) {
            // 키(맵 키라 non-null) + 값(nullable) 을 각각 자기구분 토큰으로 인코딩.
            appendToken(sb, e.getKey());
            appendToken(sb, e.getValue());
        }
        return sha256Hex(sb.toString());
    }

    /**
     * 문자열 하나를 {@code {길이}:{문자열}} 자기구분 토큰으로 이어붙인다. {@code null} 은 {@link #NULL_LEN}
     * 길이 마커({@code "-1:"})로 표기한다. 길이 프리픽스가 경계를 명확히 하므로 값에 어떤 구분자가 섞여도
     * 토큰 시퀀스는 유일하게 복원 가능(단사) → 논리값이 다르면 정규화 문자열도 항상 다르다.
     */
    private static void appendToken(StringBuilder sb, String s) {
        if (s == null) {
            sb.append(NULL_LEN).append(':');
        } else {
            sb.append(s.length()).append(':').append(s);
        }
    }

    private static String sha256Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
