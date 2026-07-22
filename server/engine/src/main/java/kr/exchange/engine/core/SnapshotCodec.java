package kr.exchange.engine.core;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * 오더북 상태 스냅샷 codec(복구 RTO 설계 §3-b). 마켓 하나의 OrderBookImpl을 결정론 바이트로
 * 인코딩하고 되돌린다. 스냅샷은 저널 파생 최적화라 단일 정본이 아니며(§3-b 단일 정본 규율),
 * 손상·버전 불일치 시 호출측이 폐기하지 않고 seq 0 재생으로 폴백한다.
 *
 * 결정론: 순회 순서가 정의된 자료구조만 인코딩하고(OrderBookImpl.exportEntries의 정준 순서),
 * BigDecimal은 scale 18 고정 문자열로 직렬화한다. 현재 시각·랜덤·해시 순회를 쓰지 않는다.
 * 동일 상태는 항상 동일 바이트가 되어야 한다(엔진 순수성 계약, 하네스 T3-A).
 *
 * format_version은 채택 판정 입력이다(snapshots.format_version). 불일치 버전은 디코딩하지 않고
 * 예외로 알려 호출측 폴백을 유도한다.
 */
public final class SnapshotCodec {

    static final int FORMAT_VERSION = 1;
    private static final int SCALE = 18;

    private SnapshotCodec() {
    }

    public static int formatVersion() {
        return FORMAT_VERSION;
    }

    public static byte[] encode(OrderBookImpl book) {
        List<OrderBookImpl.BookEntry> entries = book.exportEntries();
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(baos)) {
            out.writeByte(FORMAT_VERSION);
            out.writeInt(book.marketId());
            out.writeInt(entries.size());
            for (OrderBookImpl.BookEntry e : entries) {
                out.writeLong(e.orderId());
                out.writeLong(e.userId());
                out.writeByte(e.side() == OrderCommand.Side.BUY ? 'B' : 'S');
                out.writeUTF(text(e.price()));
                out.writeUTF(text(e.remaining()));
            }
        } catch (IOException impossible) {
            // ByteArrayOutputStream은 IO를 내지 않는다. 검사 예외 전파를 막기 위한 변환.
            throw new IllegalStateException("메모리 버퍼 스냅샷 인코딩 실패", impossible);
        }
        return baos.toByteArray();
    }

    public static OrderBookImpl decode(byte[] bytes) {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            int version = in.readUnsignedByte();
            if (version != FORMAT_VERSION) {
                throw new IllegalArgumentException("지원하지 않는 스냅샷 format_version=" + version);
            }
            int marketId = in.readInt();
            int count = in.readInt();
            if (count < 0) {
                throw new IllegalArgumentException("스냅샷 엔트리 수 음수: " + count);
            }
            List<OrderBookImpl.BookEntry> entries = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                long orderId = in.readLong();
                long userId = in.readLong();
                int sideTag = in.readUnsignedByte();
                OrderCommand.Side side = switch (sideTag) {
                    case 'B' -> OrderCommand.Side.BUY;
                    case 'S' -> OrderCommand.Side.SELL;
                    default -> throw new IllegalArgumentException("알 수 없는 side 태그: " + sideTag);
                };
                BigDecimal price = new BigDecimal(in.readUTF());
                BigDecimal remaining = new BigDecimal(in.readUTF());
                entries.add(new OrderBookImpl.BookEntry(orderId, userId, side, price, remaining));
            }
            if (in.read() != -1) {
                throw new IllegalArgumentException("스냅샷 바이트가 선언된 엔트리 수보다 길다");
            }
            return OrderBookImpl.fromEntries(marketId, entries);
        } catch (IOException e) {
            throw new IllegalArgumentException("스냅샷 디코딩 실패(바이트 손상 또는 절단)", e);
        }
    }

    // scale 18 고정 문자열. stateChecksum의 정준 표기와 동일 규약이라 재적재 후 체크섬이 보존된다.
    private static String text(BigDecimal v) {
        return v.setScale(SCALE, RoundingMode.UNNECESSARY).toPlainString();
    }
}
