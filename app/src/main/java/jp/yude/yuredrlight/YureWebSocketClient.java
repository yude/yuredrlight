package jp.yude.yuredrlight;

import android.os.Handler;
import android.os.Looper;

import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

/**
 * wss://unstable.kusaremkn.com/yure/ に接続して、JSON文字列を送るクライアント。
 * - Android 4.4(minSdk19) で動くよう OkHttp 3.12 WebSocket を使用
 * - 切断時は指数バックオフで再接続
 */
public class YureWebSocketClient {
    public interface Logger {
        void log(String msg);
    }

    private static final String URL = "wss://unstable.kusaremkn.com/yure/";

    /**
     * ユーザー要望: 証明書/ホスト名検証をスキップ。
     * 危険: 中間者攻撃に弱くなります。
     */
    private static final boolean INSECURE_SKIP_TLS_VERIFICATION = true;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final LinkedBlockingQueue<String> sendQueue = new LinkedBlockingQueue<>();
    private final Object lock = new Object();

    private volatile boolean closed = false;
    private volatile WebSocket ws;
    private volatile OkHttpClient client;

    private Thread worker;
    private final Logger logger;

    private volatile long lastSendOkElapsedMs = 0L;
    private static final long SEND_STALL_TIMEOUT_MS = 2 * 60 * 1000L; // 2分送れてなければ切り直す

    private long backoffMs = 1000;
    private static final long BACKOFF_MAX_MS = 60_000L;
    private static final long DNS_BACKOFF_MIN_MS = 5_000L;

    public YureWebSocketClient(Logger logger) {
        this.logger = logger;
    }

    public void start() {
        log("ws start() called (thread=" + Thread.currentThread().getName() + ")");
        synchronized (lock) {
            if (worker != null) {
                log("ws start() ignored: already started");
                return;
            }
            closed = false;
            worker = new Thread(this::runLoop, "YureWebSocketWorker");
            worker.start();
            log("ws worker started");
        }
    }

    public void close() {
        log("ws close() called");
        closed = true;
        WebSocket current;
        OkHttpClient c;
        synchronized (lock) {
            current = ws;
            ws = null;
            c = client;
            client = null;
        }
        if (current != null) {
            try {
                current.close(1000, "app closed");
            } catch (Exception ignored) {
            }
        }
        if (c != null) {
            try {
                c.dispatcher().executorService().shutdown();
            } catch (Exception ignored) {
            }
        }
        Thread t;
        synchronized (lock) {
            t = worker;
            worker = null;
        }
        if (t != null) t.interrupt();
        sendQueue.clear();
    }

    /** 接続状態に関わらず送信キューに積む（30件まとめたJSON配列想定） */
    public void enqueue(String json) {
        if (closed) return;
        while (sendQueue.size() > 100) {
            sendQueue.poll();
        }
        sendQueue.offer(json);
    }

    private void runLoop() {
        log("ws runLoop entered");

        // 初期化
        lastSendOkElapsedMs = android.os.SystemClock.elapsedRealtime();
        backoffMs = 1000;

        while (!closed && !Thread.currentThread().isInterrupted()) {
            try {
                ensureConnected();

                long now = android.os.SystemClock.elapsedRealtime();
                if (now - lastSendOkElapsedMs > SEND_STALL_TIMEOUT_MS) {
                    log("ws watchdog: send stalled, reconnecting");
                    disconnectQuietly();
                    // 次の接続は少し待つ
                    sleepQuietly(backoffMs);
                }

                String msg = sendQueue.poll(1, TimeUnit.SECONDS);
                if (msg == null) continue;

                WebSocket current = ws;
                if (current != null) {
                    boolean ok = current.send(msg);
                    log("ws send ok=" + ok + " bytes=" + msg.length());
                    if (ok) {
                        lastSendOkElapsedMs = android.os.SystemClock.elapsedRealtime();
                        backoffMs = 1000; // 成功したらリセット
                    } else {
                        sendQueue.offer(msg);
                        disconnectQuietly();
                        sleepQuietly(backoffMs);
                        backoffMs = Math.min(backoffMs * 2, BACKOFF_MAX_MS);
                    }
                } else {
                    // 未接続なら戻して待つ
                    sendQueue.offer(msg);
                    sleepQuietly(backoffMs);
                    backoffMs = Math.min(backoffMs * 2, BACKOFF_MAX_MS);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                log("ws loop error: " + e);
                disconnectQuietly();
                sleepQuietly(backoffMs);
                backoffMs = Math.min(backoffMs * 2, BACKOFF_MAX_MS);
            }
        }

        log("ws runLoop exiting closed=" + closed + " interrupted=" + Thread.currentThread().isInterrupted());
        disconnectQuietly();
    }

    private void ensureConnected() {
        WebSocket current = ws;
        if (current != null) return;

        OkHttpClient c;
        synchronized (lock) {
            if (closed) return;
            c = client;
            if (c == null) {
                c = buildClient();
                client = c;
            }
        }

        Request request = new Request.Builder().url(URL).build();
        log("ws connecting to " + URL);

        c.newWebSocket(request, new WebSocketListener() {
            @Override
            public void onOpen(WebSocket webSocket, Response response) {
                log("ws connected");
                synchronized (lock) {
                    ws = webSocket;
                }
                backoffMs = 1000;
                lastSendOkElapsedMs = android.os.SystemClock.elapsedRealtime();
            }

            @Override
            public void onMessage(WebSocket webSocket, String text) {
                // server message (unused)
            }

            @Override
            public void onClosing(WebSocket webSocket, int code, String reason) {
                log("ws closing code=" + code + " reason=" + reason);
            }

            @Override
            public void onClosed(WebSocket webSocket, int code, String reason) {
                log("ws closed code=" + code + " reason=" + reason);
                synchronized (lock) {
                    if (ws == webSocket) ws = null;
                }
            }

            @Override
            public void onFailure(WebSocket webSocket, Throwable t, Response response) {
                log("ws failure: " + t);
                synchronized (lock) {
                    if (ws == webSocket) ws = null;
                }

                // DNS/回線が死んでいる間は無駄な接続連打を避ける
                if (t instanceof java.net.UnknownHostException) {
                    backoffMs = Math.max(backoffMs, DNS_BACKOFF_MIN_MS);
                    backoffMs = Math.min(backoffMs * 2, BACKOFF_MAX_MS);
                } else if (t instanceof javax.net.ssl.SSLException || t instanceof java.net.SocketTimeoutException) {
                    backoffMs = Math.max(backoffMs, 2000);
                    backoffMs = Math.min(backoffMs * 2, BACKOFF_MAX_MS);
                }
            }
        });
    }

    private OkHttpClient buildClient() {
        OkHttpClient.Builder b = new OkHttpClient.Builder();

        // 過酷環境向け: 低速/不安定回線で固まらないようにタイムアウトを設定
        b.connectTimeout(10, TimeUnit.SECONDS);
        b.readTimeout(0, TimeUnit.SECONDS); // WebSocketなので無制限
        b.writeTimeout(10, TimeUnit.SECONDS);

        // 過酷環境向け: NAT/省電力でアイドル切断されないように定期ping
        b.pingInterval(20, TimeUnit.SECONDS);

        if (INSECURE_SKIP_TLS_VERIFICATION) {
            SSLSocketFactory sslSocketFactory = buildInsecureSocketFactory();
            X509TrustManager tm = buildInsecureTrustManager();
            b.sslSocketFactory(sslSocketFactory, tm);
            b.hostnameVerifier((hostname, session) -> true);
        }

        return b.build();
    }

    private static X509TrustManager buildInsecureTrustManager() {
        return new X509TrustManager() {
            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType) {
            }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType) {
            }

            @Override
            public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }
        };
    }

    private static SSLSocketFactory buildInsecureSocketFactory() {
        try {
            TrustManager[] trustAll = new TrustManager[]{buildInsecureTrustManager()};
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, trustAll, new SecureRandom());
            return context.getSocketFactory();
        } catch (Exception e) {
            return (SSLSocketFactory) SSLSocketFactory.getDefault();
        }
    }

    private void disconnectQuietly() {
        WebSocket current;
        synchronized (lock) {
            current = ws;
            ws = null;
        }
        if (current != null) {
            try {
                current.cancel();
            } catch (Exception ignored) {
            }
        }
    }

    private void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void log(String msg) {
        if (logger == null) return;
        mainHandler.post(() -> logger.log(msg));
    }

    /**
     * 直近で送信成功しているか（サービス側の送信間引き判定用）
     */
    public boolean wasSendOkRecently(long withinMs) {
        long now = android.os.SystemClock.elapsedRealtime();
        return now - lastSendOkElapsedMs <= withinMs;
    }
}
