import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;

/**
 * 예약액 양자화의 Java 기준값(ADR 0034 S3 설계 3절 P1). 정산(SettlementService.settleAccepted)과 게이트웨이
 * (OrderGateway.ParsedOrder.reserve)가 쓰는 Fees.quantize(price.multiply(qty), scale, RoundingMode.UP) 와 같은 식이다.
 * 표준입력 cases.csv 를 읽어 id,java 를 낸다.
 * 사용: java UpCheck.java < cases.csv > java.csv
 */
public class UpCheck {
    public static void main(String[] args) throws Exception {
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        String header = in.readLine();
        System.out.println("id,java");
        String line;
        while ((line = in.readLine()) != null) {
            String[] f = line.split(",");
            BigDecimal price = new BigDecimal(f[1]);
            BigDecimal qty = new BigDecimal(f[2]);
            int scale = Integer.parseInt(f[3]);
            BigDecimal reserve = price.multiply(qty).setScale(scale, RoundingMode.UP);
            System.out.println(f[0] + "," + reserve.toPlainString());
        }
    }
}
