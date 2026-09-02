package kr.exchange.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetAddress;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.web.ServerProperties;

/**
 * 관리 평면 바인딩 가드(관리자 설계 1-3-a).
 *
 * 이 가드가 고정하는 계약은 하나다. trusted-forwarding 이 켜지면 관리 신원이 자가신고
 * X-Operator-Cn 헤더가 되므로, 그 조합은 루프백 바인딩에서만 허용한다.
 *
 * 이 테스트가 없어서 가드가 통합테스트 6종의 컨텍스트를 죽이는 것을 병합 전에 못 잡았다.
 * 그때 돌린 두 테스트 클래스는 exchange.admin.enabled 를 켜지 않아 가드를 한 줄도
 * 태우지 않았다. 실행했다는 사실과 그것이 무엇을 덮었는지는 다른 문제다.
 */
class AdminForwardingBindingGuardTest {

    private static AdminProperties props(boolean trustedForwarding) {
        AdminProperties p = new AdminProperties();
        p.setEnabled(true);
        p.getMtls().setTrustedForwarding(trustedForwarding);
        return p;
    }

    private static ServerProperties bind(String address) throws Exception {
        ServerProperties s = new ServerProperties();
        s.setAddress(address == null ? null : InetAddress.getByName(address));
        return s;
    }

    @Test
    void 주소_미지정은_전_인터페이스_바인딩이므로_기동을_막는다() throws Exception {
        assertThatThrownBy(() -> new AdminConfig().adminForwardingBindingGuard(props(true), bind(null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("미지정");
    }

    @Test
    void 와일드카드_바인딩은_기동을_막는다() throws Exception {
        assertThatThrownBy(() -> new AdminConfig().adminForwardingBindingGuard(props(true), bind("0.0.0.0")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("0.0.0.0");
    }

    @Test
    void 루프백이면_기동한다() throws Exception {
        assertThatCode(() -> new AdminConfig().adminForwardingBindingGuard(props(true), bind("127.0.0.1")))
                .doesNotThrowAnyException();
    }

    /* trusted-forwarding 이 꺼져 있으면 신원이 인증서에서 나온다. 주소는 이 가드의 관할이 아니다 */
    @Test
    void 인증서_검증_모드는_주소를_보지_않는다() throws Exception {
        assertThatCode(() -> new AdminConfig().adminForwardingBindingGuard(props(false), bind("0.0.0.0")))
                .doesNotThrowAnyException();
    }

    /* 문자열 비교로 판정하면 아래가 전부 새거나 막힌다. 127.0.0.0/8 전역이 루프백이고,
       ::1 과 IPv4 매핑 표기도 같다. 판정을 InetAddress 에 맡기는 이유다 */
    @Test
    void 루프백_표기를_문자열이_아니라_주소로_판정한다() throws Exception {
        for (String a : new String[] {"127.0.0.1", "127.0.0.2", "localhost", "::1"}) {
            assertThat(AdminConfig.isLoopback(InetAddress.getByName(a))).as(a).isTrue();
        }
        for (String a : new String[] {"0.0.0.0", "10.0.0.1", "192.168.0.5"}) {
            assertThat(AdminConfig.isLoopback(InetAddress.getByName(a))).as(a).isFalse();
        }
    }
}
