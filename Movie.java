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
    public boolean watched = false;

    // Modos usados pelas versões novas do Cine Offline:
    // "zip"     = importado de ZIP e copiado para o app
    // "copied"  = pasta copiada para o app
    // "linked"  = usa a pasta original sem copiar os vídeos
    public String storageMode = "copied";

    // URI persistente da pasta original quando storageMode == "linked".
    public String sourceUri = "";

    public boolean isLinked() {
        return "linked".equalsIgnoreCase(storageMode);
    }

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
            o.put("watched", watched);
            o.put("storageMode", storageMode);
            o.put("sourceUri", sourceUri);
        } catch (Exception ignored) {
        }
        return o;
    }

    public static Movie fromJson(JSONObject o) {
        Movie m = new Movie();

        if (o == null) {
            return m;
        }

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
        // Compatibilidade: versões antigas não tinham o campo watched. Quando havia
        // reprodução registrada, posição zerada e duração conhecida, o caso mais comum
        // era um filme concluído até o fim.
        m.watched = o.has("watched")
                ? o.optBoolean("watched", false)
                : (m.playCount > 0 && m.lastPlayedAt > 0 && m.durationMs > 0 && m.progressMs == 0);

        // Compatibilidade com filmes cadastrados antes do modo rápido.
        m.storageMode = o.optString("storageMode", "copied");
        if (m.storageMode == null || m.storageMode.trim().isEmpty()) {
            m.storageMode = "copied";
        }

        m.sourceUri = o.optString("sourceUri", "");
        if (m.sourceUri == null) {
            m.sourceUri = "";
        }

        return m;
    }
}
