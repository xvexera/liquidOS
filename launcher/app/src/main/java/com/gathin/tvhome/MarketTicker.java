package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Prices for the market widget, for the assets the owner picked (one, or several the card rotates through): crypto
 * pairs from Binance's public API (24 h change and hourly candles) and stock indices and gold from Yahoo Finance's
 * chart endpoint (change since the last close and today's 15 minute line), neither with a key. Each picked asset is
 * fetched once a minute, only between start() and stop(), which Home calls from onResume and onPause.
 */
final class MarketTicker {
    enum Source { BINANCE, YAHOO }

    /** One asset the widget can follow. */
    static final class Asset {
        final String id;
        final String symbol;
        final String label;
        final String badge;
        final int badgeColor;
        final Source source;
        /** Shown with a dollar sign (crypto, gold) or as plain index points. */
        final boolean dollars;

        Asset(String id, String symbol, String label, String badge, int badgeColor, Source source, boolean dollars) {
            this.id = id;
            this.symbol = symbol;
            this.label = label;
            this.badge = badge;
            this.badgeColor = badgeColor;
            this.source = source;
            this.dollars = dollars;
        }
    }

    private static Asset crypto(String base, String badge, int color) {
        return new Asset(base, base + "USDT", base + "/USDT", badge, color, Source.BINANCE, true);
    }

    /** The menu's list: twenty large Binance USDT pairs, then indices and gold. */
    static final Asset[] ALL = {
            crypto("BTC", "₿", 0xFFF7931A),
            crypto("ETH", "Ξ", 0xFF627EEA),
            crypto("SOL", "S", 0xFF9945FF),
            crypto("BNB", "B", 0xFFE0A800),
            crypto("XRP", "X", 0xFF4A5B6E),
            crypto("DOGE", "Ð", 0xFFC2A633),
            crypto("ADA", "₳", 0xFF0033AD),
            crypto("TRX", "T", 0xFFE5262E),
            crypto("TON", "T", 0xFF0098EA),
            crypto("AVAX", "A", 0xFFE84142),
            crypto("LINK", "L", 0xFF2A5ADA),
            crypto("SUI", "S", 0xFF4DA2FF),
            crypto("DOT", "D", 0xFFE6007A),
            crypto("LTC", "Ł", 0xFF345D9D),
            crypto("BCH", "B", 0xFF0AC18E),
            crypto("XLM", "X", 0xFF3E4A5A),
            crypto("NEAR", "N", 0xFF00C08B),
            crypto("POL", "P", 0xFF8247E5),
            crypto("SHIB", "S", 0xFFE4501B),
            crypto("PEPE", "P", 0xFF479F53),
            new Asset("SPX", "^GSPC", "S&P 500", "S", 0xFF1F6FEB, Source.YAHOO, false),
            new Asset("NDX", "^NDX", "NASDAQ 100", "N", 0xFF0B8F6A, Source.YAHOO, false),
            new Asset("DJI", "^DJI", "DOW JONES", "D", 0xFF5B4BDB, Source.YAHOO, false),
            new Asset("GOLD", "GC=F", "ORO · GOLD", "Au", 0xFFC9A227, Source.YAHOO, true),
    };

    /** Most assets the card rotates through; more would take minutes to come round. */
    static final int MAX_PICKED = 5;
    private static final String CUSTOM = "market_custom";

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE);
    }

    /** Binance pairs the owner added by searching, as "PEPEUSDT"-style symbols. */
    static List<Asset> custom(Context ctx) {
        List<Asset> list = new ArrayList<>();
        for (String symbol : prefs(ctx).getString(CUSTOM, "").split(",")) {
            symbol = symbol.trim();
            if (symbol.isEmpty() || findBuiltIn(symbol) != null) continue;
            String base = symbol.endsWith("USDT") ? symbol.substring(0, symbol.length() - 4) : symbol;
            list.add(new Asset(symbol, symbol, base + "/USDT", base.substring(0, 1), 0xFF5A6472, Source.BINANCE, true));
        }
        return list;
    }

    static void addCustom(Context ctx, String symbol) {
        String saved = prefs(ctx).getString(CUSTOM, "");
        for (String s : saved.split(",")) if (s.equals(symbol)) return;
        prefs(ctx).edit().putString(CUSTOM, saved.isEmpty() ? symbol : saved + "," + symbol).apply();
    }

    private static Asset findBuiltIn(String id) {
        for (Asset a : ALL) if (a.id.equals(id) || a.symbol.equals(id)) return a;
        return null;
    }

    /** The asset with this id, built in or added by search; null when it is neither. */
    static Asset find(Context ctx, String id) {
        Asset a = findBuiltIn(id);
        if (a != null) return a;
        for (Asset c : custom(ctx)) if (c.id.equals(id)) return c;
        return null;
    }

    /**
     * Whether Binance trades {@code symbol}; a network call, so off the main thread. A typed "pepe" is checked as
     * PEPEUSDT.
     */
    static String validateBinance(String typed) {
        String s = typed.trim().toUpperCase(java.util.Locale.ROOT).replaceAll("[^A-Z0-9]", "");
        if (s.isEmpty()) return null;
        if (!s.endsWith("USDT")) s = s + "USDT";
        try {
            new JSONObject(get("https://api.binance.com/api/v3/ticker/price?symbol=" + s)).getString("price");
            return s;
        } catch (Exception notListed) {
            return null;
        }
    }

    interface Listener {
        /** On the main thread, once per asset per minute. {@code history} may be null. */
        void onQuote(Quote quote);
    }

    static final class Quote {
        final Asset asset;
        final double price;
        final double changePercent;
        final float[] history;

        Quote(Asset asset, double price, double changePercent, float[] history) {
            this.asset = asset;
            this.price = price;
            this.changePercent = changePercent;
            this.history = history;
        }
    }

    private static final long PRICE_MS = 60_000L;
    private static final long HISTORY_MS = 10 * 60_000L;
    private static final String USER_AGENT = "Mozilla/5.0 (Linux; Android 14; TV) CleanYourTV-launcher";

    /** Last quote per asset, kept across Home restarts so the card shows a price at once instead of a blank. */
    private static final Map<String, Quote> last = new HashMap<>();
    private static final Map<String, Long> historyAt = new HashMap<>();

    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Asset> assets;
    private final Listener listener;
    private HandlerThread thread;
    private Handler bg;

    MarketTicker(List<Asset> assets, Listener listener) {
        this.assets = new ArrayList<>(assets);
        this.listener = listener;
    }

    static synchronized Quote last(Asset asset) {
        return last.get(asset.id);
    }

    void start() {
        if (thread != null) return;
        thread = new HandlerThread("market");
        thread.start();
        Handler handler = new Handler(thread.getLooper());
        bg = handler;
        handler.post(new Runnable() {
            @Override
            public void run() {
                for (Asset asset : assets) {
                    if (bg != handler) return;
                    Quote quote = null;
                    try {
                        quote = asset.source == Source.BINANCE ? binance(asset) : yahoo(asset);
                    } catch (Exception ignored) {
                        // offline or the source is busy: keep the last price and try again in a minute
                    }
                    if (quote == null) continue;
                    synchronized (MarketTicker.class) {
                        last.put(asset.id, quote);
                    }
                    Quote q = quote;
                    main.post(() -> {
                        if (bg == handler) listener.onQuote(q);
                    });
                }
                if (bg == handler) handler.postDelayed(this, PRICE_MS);
            }
        });
    }

    void stop() {
        if (thread == null) return;
        bg.removeCallbacksAndMessages(null);
        bg = null;
        thread.quitSafely();
        thread = null;
    }

    private static Quote binance(Asset a) throws Exception {
        JSONObject t = new JSONObject(get("https://api.binance.com/api/v3/ticker/24hr?symbol=" + a.symbol));
        double price = Double.parseDouble(t.getString("lastPrice"));
        double change = Double.parseDouble(t.getString("priceChangePercent"));
        Quote previous;
        Long at;
        synchronized (MarketTicker.class) {
            previous = last.get(a.id);
            at = historyAt.get(a.id);
        }
        float[] history = previous != null ? previous.history : null;
        if (history == null || at == null || System.currentTimeMillis() - at > HISTORY_MS) {
            try {
                JSONArray candles = new JSONArray(get("https://api.binance.com/api/v3/klines?symbol=" + a.symbol
                        + "&interval=1h&limit=24"));
                float[] closes = new float[candles.length()];
                for (int i = 0; i < closes.length; i++) closes[i] = Float.parseFloat(candles.getJSONArray(i).getString(4));
                if (closes.length > 1) {
                    history = closes;
                    synchronized (MarketTicker.class) {
                        historyAt.put(a.id, System.currentTimeMillis());
                    }
                }
            } catch (Exception ignored) {
                // keep the older line; the price alone is still worth showing
            }
        }
        return new Quote(a, price, change, history);
    }

    /** Yahoo's chart: the latest price, the previous close, and today's line every 15 minutes. */
    private static Quote yahoo(Asset a) throws Exception {
        JSONObject result = new JSONObject(get("https://query1.finance.yahoo.com/v8/finance/chart/"
                + URLEncoder.encode(a.symbol, "UTF-8") + "?range=1d&interval=15m"))
                .getJSONObject("chart").getJSONArray("result").getJSONObject(0);
        JSONObject meta = result.getJSONObject("meta");
        double price = meta.getDouble("regularMarketPrice");
        double previous = meta.optDouble("chartPreviousClose", meta.optDouble("previousClose", price));
        List<Float> points = new ArrayList<>();
        JSONArray closes = result.optJSONObject("indicators") == null ? null
                : result.getJSONObject("indicators").getJSONArray("quote").getJSONObject(0).optJSONArray("close");
        if (closes != null) {
            for (int i = 0; i < closes.length(); i++) if (!closes.isNull(i)) points.add((float) closes.getDouble(i));
        }
        float[] history = null;
        if (points.size() > 1) {
            history = new float[points.size()];
            for (int i = 0; i < history.length; i++) history[i] = points.get(i);
        }
        double change = previous != 0 ? (price - previous) / previous * 100.0 : 0;
        return new Quote(a, price, change, history);
    }

    private static String get(String address) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(address).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(8000);
        c.setRequestProperty("Accept", "application/json");
        c.setRequestProperty("User-Agent", USER_AGENT);
        try {
            if (c.getResponseCode() != 200) throw new IllegalStateException("HTTP " + c.getResponseCode());
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) > 0) {
                    if (out.size() > 512 * 1024) throw new IllegalStateException("response too large");
                    out.write(buf, 0, n);
                }
                return out.toString("UTF-8");
            }
        } finally {
            c.disconnect();
        }
    }
}
