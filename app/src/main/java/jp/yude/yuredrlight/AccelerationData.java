package jp.yude.yuredrlight;

/** 送信用に整形した加速度データ(1サンプル) */
public class AccelerationData {
    public final String yureId;
    public final double x;
    public final double y;
    public final double z;
    /** UNIX time millis */
    public final long t;
    public final String userAgent;

    public AccelerationData(String yureId, double x, double y, double z, long t, String userAgent) {
        this.yureId = yureId;
        this.x = x;
        this.y = y;
        this.z = z;
        this.t = t;
        this.userAgent = userAgent;
    }
}

