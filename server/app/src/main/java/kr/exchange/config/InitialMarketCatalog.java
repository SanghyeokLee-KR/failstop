package kr.exchange.config;

import java.math.BigDecimal;

/** 개발 시드와 최초 운영 카탈로그가 공유하는 자산·마켓 명세. 사용자나 잔고는 포함하지 않는다. */
public final class InitialMarketCatalog {

    public static final BigDecimal FEE = new BigDecimal("0.0005");

    public record AssetSeed(int id, String symbol, String koreanName, String kind, int scale) {
    }

    public record MarketSeed(int id, String symbol, int base, int quote, String tick, String lot,
                              String minNotional, String maxNotional, String maxQty) {
    }

    public static final java.util.List<AssetSeed> ASSETS = java.util.List.of(
            new AssetSeed(1, "KRW", "원화", "FIAT", 0),
            new AssetSeed(2, "BTC", "비트코인", "CRYPTO", 8),
            new AssetSeed(3, "ETH", "이더리움", "CRYPTO", 18),
            new AssetSeed(4, "SOL", "솔라나", "CRYPTO", 6),
            new AssetSeed(5, "XRP", "리플", "CRYPTO", 2),
            new AssetSeed(6, "DOGE", "도지코인", "CRYPTO", 2),
            new AssetSeed(7, "ADA", "에이다", "CRYPTO", 2),
            new AssetSeed(8, "TRX", "트론", "CRYPTO", 2),
            new AssetSeed(9, "LINK", "체인링크", "CRYPTO", 4),
            new AssetSeed(10, "DOT", "폴카닷", "CRYPTO", 4),
            new AssetSeed(11, "MATIC", "폴리곤", "CRYPTO", 2),
            new AssetSeed(12, "AVAX", "아발란체", "CRYPTO", 4),
            new AssetSeed(13, "ATOM", "코스모스", "CRYPTO", 4)
    );

    public static final java.util.List<MarketSeed> MARKETS = java.util.List.of(
            new MarketSeed(1, "KRW-BTC", 2, 1, "1000", "0.00000001", "5000", "1000000000", "1000"),
            new MarketSeed(2, "KRW-ETH", 3, 1, "1000", "0.00000001", "5000", "1000000000", "100000"),
            new MarketSeed(3, "KRW-SOL", 4, 1, "50", "0.000001", "5000", "1000000000", "10000000"),
            new MarketSeed(4, "KRW-XRP", 5, 1, "1", "0.01", "5000", "1000000000", "100000000"),
            new MarketSeed(5, "KRW-DOGE", 6, 1, "1", "0.01", "5000", "1000000000", "1000000000"),
            new MarketSeed(6, "KRW-ADA", 7, 1, "1", "0.01", "5000", "1000000000", "100000000"),
            new MarketSeed(7, "KRW-TRX", 8, 1, "1", "0.01", "5000", "1000000000", "100000000"),
            new MarketSeed(8, "KRW-LINK", 9, 1, "10", "0.0001", "5000", "1000000000", "10000000"),
            new MarketSeed(9, "KRW-DOT", 10, 1, "5", "0.0001", "5000", "1000000000", "10000000"),
            new MarketSeed(10, "KRW-MATIC", 11, 1, "1", "0.01", "5000", "1000000000", "100000000"),
            new MarketSeed(11, "KRW-AVAX", 12, 1, "10", "0.0001", "5000", "1000000000", "10000000"),
            new MarketSeed(12, "KRW-ATOM", 13, 1, "5", "0.0001", "5000", "1000000000", "10000000")
    );

    private InitialMarketCatalog() { }
}
