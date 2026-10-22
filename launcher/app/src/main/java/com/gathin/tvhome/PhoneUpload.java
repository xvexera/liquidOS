package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.media.ExifInterface;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.os.Handler;
import android.os.Looper;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.Locale;

/**
 * "Upload from phone": a tiny web server on the home network, open only while its QR code is on screen. The phone
 * scans the code, gets a page to pick a photo (or paste a link), and the picture lands in the wallpapers folder as
 * upload_*.jpg, cropped to the screen's 16:9 at 1920x1080.
 *
 * <p>The address carries a random token, so only someone who can see the TV can send to it; it serves nothing
 * else, takes images only (up to {@link #MAX_BYTES}) and shuts itself down after {@link #LIFETIME_MS}.
 */
final class PhoneUpload {
    /** File names of uploaded pictures start with this; they get their own folder in the wallpaper menu. */
    static final String PREFIX = "upload_";
    private static final int MAX_BYTES = 30 * 1024 * 1024;
    private static final long LIFETIME_MS = 10 * 60 * 1000;
    private static final int OUT_W = 1920, OUT_H = 1080;

    interface Listener {
        /** On the main thread: a picture was saved as {@code file}. */
        void onUploaded(File file);
    }

    private final Context ctx;
    private final File folder;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final String token;
    private ServerSocket server;
    private volatile boolean running;

    private PhoneUpload(Context ctx, File folder, Listener listener) {
        this.ctx = ctx.getApplicationContext();
        this.folder = folder;
        this.listener = listener;
        String abc = "abcdefghijkmnpqrstuvwxyz23456789";
        SecureRandom random = new SecureRandom();
        StringBuilder t = new StringBuilder();
        for (int i = 0; i < 10; i++) t.append(abc.charAt(random.nextInt(abc.length())));
        token = t.toString();
    }

    /** Starts the server; null when the TV has no address on the home network (or the port cannot be opened). */
    static PhoneUpload start(Context ctx, File folder, Listener listener) {
        PhoneUpload upload = new PhoneUpload(ctx, folder, listener);
        return upload.open() ? upload : null;
    }

    /** What the QR code holds: the page on this TV, with its one-time token. */
    String url() {
        return "http://" + address(ctx) + ":" + server.getLocalPort() + "/" + token;
    }

    void stop() {
        running = false;
        main.removeCallbacksAndMessages(null);
        try {
            if (server != null) server.close();
        } catch (IOException ignored) {
        }
    }

    private boolean open() {
        if (address(ctx) == null) return false;
        try {
            server = new ServerSocket(0);
        } catch (IOException e) {
            return false;
        }
        running = true;
        main.postDelayed(this::stop, LIFETIME_MS);
        Thread thread = new Thread(() -> {
            while (running) {
                try (Socket socket = server.accept()) {
                    socket.setSoTimeout(20000);
                    serve(socket);
                } catch (IOException ignored) {
                    // a closed server (stop) or a client that went away
                }
            }
        }, "phone-upload");
        thread.setDaemon(true);
        thread.start();
        return true;
    }

    /** The TV's IPv4 address on the network it is connected to. */
    static String address(Context ctx) {
        ConnectivityManager cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return null;
        Network net = cm.getActiveNetwork();
        LinkProperties props = net == null ? null : cm.getLinkProperties(net);
        if (props == null) return null;
        for (LinkAddress link : props.getLinkAddresses()) {
            InetAddress a = link.getAddress();
            if (a instanceof Inet4Address && !a.isLoopbackAddress()) return a.getHostAddress();
        }
        return null;
    }

    /** The QR code of {@code text}: black modules on white with a quiet zone, {@code size} pixels square or less. */
    static Bitmap qr(String text, int size) {
        try {
            BitMatrix m = new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0,
                    Collections.singletonMap(EncodeHintType.MARGIN, 2));
            int cell = Math.max(1, size / m.getWidth());
            Bitmap b = Bitmap.createBitmap(m.getWidth() * cell, m.getHeight() * cell, Bitmap.Config.ARGB_8888);
            b.eraseColor(0xFFFFFFFF);
            Canvas c = new Canvas(b);
            Paint p = new Paint();
            p.setColor(0xFF000000);
            for (int y = 0; y < m.getHeight(); y++) {
                for (int x = 0; x < m.getWidth(); x++) {
                    if (m.get(x, y)) c.drawRect(x * cell, y * cell, (x + 1) * cell, (y + 1) * cell, p);
                }
            }
            return b;
        } catch (Exception e) {
            return null;
        }
    }

    // ---- HTTP ------------------------------------------------------------------------------------------

    private void serve(Socket socket) throws IOException {
        InputStream in = socket.getInputStream();
        OutputStream out = socket.getOutputStream();
        String head = readHead(in);
        if (head == null) return;
        String[] lines = head.split("\r\n");
        String[] request = lines[0].split(" ");
        if (request.length < 2) return;
        String method = request[0], path = request[1];
        int length = -1;
        for (String line : lines) {
            int colon = line.indexOf(':');
            if (colon > 0 && line.substring(0, colon).trim().equalsIgnoreCase("Content-Length")) {
                try {
                    length = Integer.parseInt(line.substring(colon + 1).trim());
                } catch (NumberFormatException ignored) {
                }
            }
        }
        String base = "/" + token;
        if (method.equals("GET") && path.equals(base)) {
            send(out, 200, "text/html; charset=utf-8", page().getBytes(StandardCharsets.UTF_8));
        } else if (method.equals("POST") && (path.equals(base + "/photo") || path.equals(base + "/link"))) {
            if (length <= 0 || length > MAX_BYTES) {
                send(out, 413, "text/plain", "too big".getBytes(StandardCharsets.UTF_8));
                return;
            }
            byte[] body = readFully(in, length);
            byte[] image = path.endsWith("/link") ? download(new String(body, StandardCharsets.UTF_8).trim()) : body;
            File saved = image == null ? null : save(image);
            if (saved == null) {
                send(out, 415, "text/plain", "not an image".getBytes(StandardCharsets.UTF_8));
                return;
            }
            send(out, 200, "text/plain", "ok".getBytes(StandardCharsets.UTF_8));
            main.post(() -> {
                if (running) listener.onUploaded(saved);
            });
        } else {
            send(out, 404, "text/plain", "not found".getBytes(StandardCharsets.UTF_8));
        }
    }

    /** The request line and headers, up to the blank line; null past 16 KB (not a browser we serve). */
    private static String readHead(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int last = 0, b;   // the last four bytes, to spot the blank line (CR LF CR LF)
        while ((b = in.read()) != -1) {
            buf.write(b);
            last = (last << 8) | b;
            if (last == 0x0D0A0D0A) return buf.toString("ISO-8859-1");
            if (buf.size() > 16 * 1024) return null;
        }
        return null;
    }

    private static byte[] readFully(InputStream in, int length) throws IOException {
        byte[] body = new byte[length];
        int at = 0;
        while (at < length) {
            int n = in.read(body, at, length - at);
            if (n < 0) throw new IOException("short body");
            at += n;
        }
        return body;
    }

    private static void send(OutputStream out, int code, String type, byte[] body) throws IOException {
        String head = "HTTP/1.1 " + code + (code == 200 ? " OK" : " Error") + "\r\nContent-Type: " + type
                + "\r\nContent-Length: " + body.length + "\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.ISO_8859_1));
        out.write(body);
        out.flush();
    }

    /** The picture behind a pasted link: http(s) only, at most MAX_BYTES. */
    private static byte[] download(String link) {
        if (!link.startsWith("http://") && !link.startsWith("https://")) return null;
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(link).openConnection();
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(20000);
            conn.setRequestProperty("User-Agent", "CleanYourTV-launcher");
            if (conn.getResponseCode() != 200) return null;
            try (InputStream in = conn.getInputStream()) {
                ByteArrayOutputStream buf = new ByteArrayOutputStream();
                byte[] chunk = new byte[16384];
                int n;
                while ((n = in.read(chunk)) > 0) {
                    buf.write(chunk, 0, n);
                    if (buf.size() > MAX_BYTES) return null;
                }
                return buf.toByteArray();
            }
        } catch (Exception e) {
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    // ---- the picture -----------------------------------------------------------------------------------

    /** Turns upright, crops to 16:9 round the centre, scales to 1920x1080 and saves as JPEG; null if not an image. */
    private File save(byte[] data) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
        BitmapFactory.Options opts = new BitmapFactory.Options();
        // Decode no bigger than needed (a 48 MP photo would not fit in memory on a 2 GB box), in 16 bits.
        opts.inSampleSize = 1;
        while (bounds.outWidth / (opts.inSampleSize * 2) >= OUT_W && bounds.outHeight / (opts.inSampleSize * 2) >= OUT_H) {
            opts.inSampleSize *= 2;
        }
        opts.inPreferredConfig = Bitmap.Config.RGB_565;
        Bitmap src = BitmapFactory.decodeByteArray(data, 0, data.length, opts);
        if (src == null) return null;
        int rotation = 0;
        try {
            int o = new ExifInterface(new ByteArrayInputStream(data))
                    .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
            rotation = o == ExifInterface.ORIENTATION_ROTATE_90 ? 90 : o == ExifInterface.ORIENTATION_ROTATE_180 ? 180
                    : o == ExifInterface.ORIENTATION_ROTATE_270 ? 270 : 0;
        } catch (Exception ignored) {
        }
        if (rotation != 0) {
            Matrix m = new Matrix();
            m.postRotate(rotation);
            Bitmap turned = Bitmap.createBitmap(src, 0, 0, src.getWidth(), src.getHeight(), m, true);
            if (turned != src) src.recycle();
            src = turned;
        }
        int w = src.getWidth(), h = src.getHeight();
        Rect crop = w * OUT_H > h * OUT_W
                ? new Rect((w - h * OUT_W / OUT_H) / 2, 0, (w + h * OUT_W / OUT_H) / 2, h)
                : new Rect(0, (h - w * OUT_H / OUT_W) / 2, w, (h + w * OUT_H / OUT_W) / 2);
        Bitmap out = Bitmap.createBitmap(OUT_W, OUT_H, Bitmap.Config.ARGB_8888);
        new Canvas(out).drawBitmap(src, crop, new Rect(0, 0, OUT_W, OUT_H), new Paint(Paint.FILTER_BITMAP_FLAG));
        src.recycle();
        if (!folder.isDirectory() && !folder.mkdirs()) return null;
        File file = new File(folder, PREFIX + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT).format(new Date()) + ".jpg");
        try (FileOutputStream fos = new FileOutputStream(file)) {
            out.compress(Bitmap.CompressFormat.JPEG, 92, fos);
        } catch (IOException e) {
            return null;
        } finally {
            out.recycle();
        }
        return file;
    }

    // ---- the page --------------------------------------------------------------------------------------

    /** The phone's page: pick a photo (shrunk on the phone first, so it sends fast) or paste a link. */
    private String page() {
        return "<!doctype html><html><head><meta charset='utf-8'>"
                + "<meta name='viewport' content='width=device-width,initial-scale=1'><title>" + esc(R.string.upload_phone) + "</title>"
                + "<style>"
                + "body{margin:0;min-height:100vh;background:#05070d;color:#fff;font:16px system-ui,sans-serif;"
                + "display:flex;align-items:center;justify-content:center}"
                + ".card{width:min(92vw,420px);padding:24px;border-radius:28px;background:rgba(255,255,255,.07);"
                + "border:1px solid rgba(255,255,255,.14);box-sizing:border-box}"
                + "h1{font-size:22px;margin:0 0 4px}p{color:rgba(255,255,255,.6);margin:0 0 20px;font-size:14px}"
                + "label,button{display:block;width:100%;box-sizing:border-box;padding:16px;border-radius:999px;border:0;"
                + "font:600 16px system-ui,sans-serif;text-align:center;cursor:pointer}"
                + "label{background:#fff;color:#000}button{background:rgba(255,255,255,.14);color:#fff;margin-top:10px}"
                + "input[type=url]{width:100%;box-sizing:border-box;margin-top:22px;padding:14px 18px;border-radius:999px;"
                + "border:1px solid rgba(255,255,255,.2);background:transparent;color:#fff;font-size:15px}"
                + "img{width:100%;border-radius:16px;margin:0 0 14px;display:none}#s{margin-top:16px;text-align:center;min-height:20px}"
                + "</style></head><body><div class='card'><h1>" + esc(R.string.upload_phone) + "</h1><p>"
                + esc(R.string.upload_page_hint) + "</p><img id='v'>"
                + "<label>" + esc(R.string.upload_pick) + "<input id='f' type='file' accept='image/*' hidden></label>"
                + "<input id='u' type='url' placeholder='" + esc(R.string.upload_link_hint) + "'>"
                + "<button id='l'>" + esc(R.string.upload_send_link) + "</button><div id='s'></div></div><script>"
                + "const s=document.getElementById('s'),T=location.pathname;"
                + "function say(t){s.textContent=t}"
                + "async function post(p,b){say('" + esc(R.string.upload_sending) + "');"
                + "try{const r=await fetch(T+p,{method:'POST',body:b});"
                + "say(r.ok?'" + esc(R.string.upload_done) + "':'" + esc(R.string.upload_failed) + "')}"
                + "catch(e){say('" + esc(R.string.upload_failed) + "')}}"
                + "async function shrink(f){try{const i=await createImageBitmap(f);"
                + "const k=Math.min(1,3840/Math.max(i.width,i.height)),c=document.createElement('canvas');"
                + "c.width=Math.round(i.width*k);c.height=Math.round(i.height*k);c.getContext('2d').drawImage(i,0,0,c.width,c.height);"
                + "return await new Promise(r=>c.toBlob(b=>r(b||f),'image/jpeg',.92))}catch(e){return f}}"
                + "document.getElementById('f').onchange=async e=>{const f=e.target.files[0];if(!f)return;"
                + "const v=document.getElementById('v');v.src=URL.createObjectURL(f);v.style.display='block';"
                + "post('/photo',await shrink(f))};"
                + "document.getElementById('l').onclick=()=>{const u=document.getElementById('u').value.trim();if(u)post('/link',u)};"
                + "</script></body></html>";
    }

    /** A string for the page, safe both in its HTML and inside its script's quoted strings. */
    private String esc(int id) {
        return ctx.getString(id).replace("&", "&amp;").replace("<", "&lt;").replace("'", "’").replace("\\", "");
    }
}
