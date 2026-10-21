package com.gathin.tvhome;

import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * BTC/USDT for the Home widget: Binance's 24 h ticker once a minute and its hourly candles every ten minutes,
 * with CoinGecko's BTC/USD as a fallback when Binance does not answer. Runs only between start() and stop(),
 * which Home calls from onResume and onPause, so nothing is fetched while an app is in front.
 */
final class CryptoTicker {
    interface Listener {
        /** On the main thread. {@code history} is null when only the price could be read. */
        void onQuote(Quote quote);
    }

    static final class Quote {
        final double price;
        final double changePercent;
        final float[] history;
        final String pair;

        Quote(double price, double changePercent, float[] history, String pair) {
            this.price = price;
            this.changePercent = changePercent;
            this.history = history;
            this.pair = pair;
        }
    }

    private static final long PRICE_MS = 60_000L;
    private static final long HISTORY_MS = 10 * 60_000L;
    private static final String BINANCE_TICKER = "https://api.binance.com/api/v3/ticker/24hr?symbol=BTCUSDT";
    private static final String BINANCE_CANDLES = "https://api.binance.com/api/v3/klines?symbol=BTCUSDT&interval=1h&limit=24";
    private static final String COINGECKO = "https://api.coingecko.com/api/v3/simple/price?ids=bitcoin&vs_currencies=usd&include_24hr_change=true";

    /** The last quote, kept across Home restarts so the card shows a price at once instead of a blank. */
    private static volatile Quote last;
    private static volatile long historyAt;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Listener listener;
    private HandlerThread thread;
    private Handler bg;

    CryptoTicker(Listener listener) {
        this.listener = listener;
    }

    static Quote last() {
        return last;
    }

    void start() {
        if (thread != null) return;
        thread = new HandlerThread("btc");
        thread.start();
        Handler handler = new Handler(thread.getLooper());
        bg = handler;
        handler.post(new Runnable() {
            @Override
            public void run() {
                if (bg != handler) return;
                Quote quote = fetch();
                if (quote != null && bg == handler) {
                    last = quote;
                    main.post(() -> {
                        if (bg == handler) listener.onQuote(quote);
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

    private static Quote fetch() {
        float[] history = last != null ? last.history : null;
        try {
            JSONObject t = new JSONObject(get(BINANCE_TICKER));
            double price = Double.parseDouble(t.getString("lastPrice"));
            double change = Double.parseDouble(t.getString("priceChangePercent"));
            if (history == null || System.currentTimeMillis() - historyAt > HISTORY_MS) {
                try {
                    JSONArray candles = new JSONArray(get(BINANCE_CANDLES));
                    float[] closes = new float[candles.length()];
                    for (int i = 0; i < closes.length; i++) {
                        closes[i] = Float.parseFloat(candles.getJSONArray(i).getString(4));
                    }
                    if (closes.length > 1) {
                        history = closes;
                        historyAt = System.currentTimeMillis();
                    }
                } catch (Exception ignored) {
                    // keep the older line; the price alone is still worth showing
                }
            }
            return new Quote(price, change, history, "BTC/USDT");
        } catch (Exception binanceFailed) {
            try {
                JSONObject btc = new JSONObject(get(COINGECKO)).getJSONObject("bitcoin");
                return new Quote(btc.getDouble("usd"), btc.optDouble("usd_24h_change", 0), history, "BTC/USD");
            } catch (Exception ignored) {
                return null;
            }
        }
    }

    private static String get(String address) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(address).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(8000);
        c.setRequestProperty("Accept", "application/json");
        try {
            if (c.getResponseCode() != 200) throw new IllegalStateException("HTTP " + c.getResponseCode());
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) > 0) {
                    if (out.size() > 256 * 1024) throw new IllegalStateException("response too large");
                    out.write(buf, 0, n);
                }
                return out.toString("UTF-8");
            }
        } finally {
            c.disconnect();
        }
    }
}
