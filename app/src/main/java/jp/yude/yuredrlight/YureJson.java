package jp.yude.yuredrlight;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** 送信用JSONを生成するユーティリティ */
public final class YureJson {
    private YureJson() {
    }

    /**
     * JS例と同じく、AccelerationDataの配列をそのままJSON配列で送る。
     * 各要素は {yureId,x,y,z,t,userAgent}。
     */
    public static String toJsonArray(AccelerationData[] data) {
        try {
            JSONArray arr = new JSONArray();
            for (AccelerationData d : data) {
                JSONObject obj = new JSONObject();
                obj.put("yureId", d.yureId);
                obj.put("x", d.x);
                obj.put("y", d.y);
                obj.put("z", d.z);
                obj.put("t", d.t);
                obj.put("userAgent", d.userAgent);
                arr.put(obj);
            }
            return arr.toString();
        } catch (JSONException e) {
            // ここで例外が出るケースはほぼ無い（キーがnull等）ので、安全側に空配列を返す
            return "[]";
        }
    }
}
