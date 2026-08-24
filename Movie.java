package com.offlineplayer.cineoffline;

import org.json.JSONObject;

public class Movie {
    public String id = "";
    public String title = "Filme offline";
    public String folderPath = "";
    public String playlistPath = "";
    public String coverPath = "";
    public long durationMs = 0;
    public long progressMs = 0;
    public long addedAt = 0;
    public long lastPlayedAt = 0;
    public int playCount = 0;
    public boolean favorite = false;

    public JSONObject toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("id", id);
            o.put("title", title);
            o.put("folderPath", folderPath);
            o.put("playlistPath", playlistPath);
            o.put("coverPath", coverPath);
            o.put("durationMs", durationMs);
            o.put("progressMs", progressMs);
            o.put("addedAt", addedAt);
            o.put("lastPlayedAt", lastPlayedAt);
            o.put("playCount", playCount);
            o.put("favorite", favorite);
        } catch (Exception ignored) {}
        return o;
    }

    public static Movie fromJson(JSONObject o) {
        Movie m = new Movie();
        m.id = o.optString("id", "");
        m.title = o.optString("title", "Filme offline");
        m.folderPath = o.optString("folderPath", "");
        m.playlistPath = o.optString("playlistPath", "");
        m.coverPath = o.optString("coverPath", "");
        m.durationMs = o.optLong("durationMs", 0);
        m.progressMs = o.optLong("progressMs", 0);
        m.addedAt = o.optLong("addedAt", 0);
        m.lastPlayedAt = o.optLong("lastPlayedAt", 0);
        m.playCount = o.optInt("playCount", 0);
        m.favorite = o.optBoolean("favorite", false);
        return m;
    }
}
