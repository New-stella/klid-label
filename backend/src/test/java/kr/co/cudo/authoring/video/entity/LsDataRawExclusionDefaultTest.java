package kr.co.cudo.authoring.video.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ★<b>제외여부 적재 기본값</b> 회귀 가드 — 어떤 생성 경로로 만들어도 비어 있지 않다.
 * [@design ADR-069] [@design ERD-012]
 *
 * <h2>왜 이 가드가 있는가 (실측된 사고)</h2>
 * <p>기본값을 <b>빌더 생성자에만</b> 두었더니, 이 엔티티가 증강·해상도 파생을 보호 생성자
 * ({@code new LsDataRaw()}) + 필드 대입으로 만들기 때문에 그 경로 전부가 {@code null} 로 INSERT 되어
 * <b>파생 생성이 통째로 실패</b>했다({@code null value in column "excl_yn" violates not-null} — 전체
 * 회귀에서 113건). 컬럼이 {@code NOT NULL} 이라 시끄럽게 드러난 것이 다행이었지만, 새 생성 경로가
 * 생길 때마다 같은 일이 반복될 수 있다.
 *
 * <h2>가장 약한 경로를 직접 겨냥한다</h2>
 * <p>팩토리를 손으로 나열하는 시험은 <b>새로 생긴 팩토리를 놓친다</b>. 그래서 모든 생성 경로가 반드시
 * 지나가는 <b>보호 생성자</b>(Hibernate 와 파생 팩토리가 쓰는 그 경로)를 직접 만들어 기본값이 서는지
 * 본다 — 필드 선언에서 기본값을 걷어내면 여기서 즉시 깨진다. 공개 팩토리 둘은 그 위에 얹는 확인이다.
 */
class LsDataRawExclusionDefaultTest {

    @Test
    @DisplayName("★★보호_생성자로_만들어도_제외여부_기본값이_선다_모든_생성경로가_지나는_자리다")
    void protectedConstructorHasDefault() throws Exception {
        Constructor<LsDataRaw> ctor = LsDataRaw.class.getDeclaredConstructor();
        ctor.setAccessible(true);
        LsDataRaw raw = ctor.newInstance();

        assertThat(raw.getExclYn())
                .as("기본값을 빌더 생성자에만 두면, 보호 생성자 + 필드 대입으로 만드는 파생 생성 경로가"
                        + " null 로 INSERT 되어 파생 생성이 통째로 실패한다(실측)")
                .isEqualTo(LsDataRaw.EXCL_NO);
        assertThat(raw.isExcluded()).isFalse();
    }

    @Test
    @DisplayName("관제_인입_적재본은_표시분으로_시작한다")
    void ingestStartsVisible() {
        LsDataRaw raw = LsDataRaw.createFromIngest(
                "CLIP-guard", "CCTV-guard", "EVT", "11680",
                LsDataRaw.PRVC_TYPE_PRVC, "/var/raw/clip.mp4", LocalDateTime.now(), 30);

        assertThat(raw.getExclYn()).isEqualTo(LsDataRaw.EXCL_NO);
        assertThat(raw.isExcluded()).isFalse();
    }

    @Test
    @DisplayName("포털_업로드_적재본도_표시분으로_시작한다")
    void portalUploadStartsVisible() {
        LsDataRaw raw = LsDataRaw.createPortalUpload("portal-user-1", "/var/raw/upload.mp4");

        assertThat(raw.getExclYn()).isEqualTo(LsDataRaw.EXCL_NO);
        assertThat(raw.isExcluded()).isFalse();
    }
}
