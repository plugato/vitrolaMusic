package app.vitrola.music;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.support.v4.media.MediaBrowserCompat.MediaItem;
import android.support.v4.media.MediaDescriptionCompat;
import android.util.Log;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Biblioteca para o Android Auto, lida direto do arquivo do banco (`files/vitrola/vitrola.db`).
 *
 * O banco "de verdade" é o sql.js do WebView, gravado inteiro no arquivo a cada alteração
 * (com debounce, por .tmp + rename: ver `mobile/src/server/persist.ts`). Aqui só se lê, e
 * cada consulta abre e fecha o arquivo, então o carro vê a biblioteca atual mesmo com o app
 * fechado. **Nunca grave neste arquivo pelo Java**: a próxima gravação do JS apagaria.
 *
 * IDs de mídia:
 * - navegáveis: `recent`, `playlists`, `albums`, `artists`, `playlist:N`, `album:N`, `artist:N`;
 * - faixa: `t:<contexto>:<id>`, onde o contexto (`recent`, `all`, `one`, `playlist.N`,
 *   `album.N`, `artist.N`) define a fila que continua depois dela;
 * - aleatório: `s:<contexto>`;
 * - catálogo externo (só com o app aberto): `catalog:<id>`.
 */
final class CarLibrary {

    private static final String TAG = "VitrolaCar";

    static final String ROOT = "root";
    static final String RECENT = "recent";
    static final String PLAYLISTS = "playlists";
    static final String ALBUMS = "albums";
    static final String ARTISTS = "artists";
    static final String CATALOG_PREFIX = "catalog:";

    // Dicas de layout do Android Auto (androidx.media.utils.MediaConstants).
    static final String STYLE_BROWSABLE = "android.media.browse.CONTENT_STYLE_BROWSABLE_HINT";
    static final String STYLE_PLAYABLE = "android.media.browse.CONTENT_STYLE_PLAYABLE_HINT";
    static final String STYLE_GROUP_TITLE = "android.media.browse.CONTENT_STYLE_GROUP_TITLE_HINT";
    static final int STYLE_LIST = 1;
    static final int STYLE_GRID = 2;

    private static final int LIST_LIMIT = 500;
    private static final int SEARCH_LIMIT = 15;
    private static final String EXTRA_PLAYLIST = "android.intent.extra.playlist";

    private static final String TRACK_SELECT =
        "SELECT t.id, t.title, ar.name, al.title, al.cover_url, t.duration_seconds, t.download_status, t.file_path, t.album_id "
            + "FROM tracks t JOIN albums al ON al.id = t.album_id JOIN artists ar ON ar.id = t.artist_id ";

    /** Uma faixa como o carro e o player nativo precisam. */
    static final class Track {
        long id;
        String title;
        String artist;
        String album;
        String coverUrl;
        long albumId;
        long durationMs;
        boolean ready;
        /** Arquivo baixado (caminho absoluto) ou null. */
        String file;

        boolean playableOffline() {
            return ready && file != null && new File(file).isFile();
        }
    }

    /** Fila pedida pelo carro: as faixas do contexto e por onde começar. */
    static final class Queue {
        final List<Track> tracks;
        final int index;
        final boolean shuffle;

        Queue(List<Track> tracks, int index, boolean shuffle) {
            this.tracks = tracks;
            this.index = Math.max(0, Math.min(index, Math.max(0, tracks.size() - 1)));
            this.shuffle = shuffle;
        }

        boolean isEmpty() {
            return tracks.isEmpty();
        }
    }

    private final Context context;
    private final File dataDir;

    CarLibrary(Context context) {
        this.context = context.getApplicationContext();
        // Directory.Data do Capacitor = getFilesDir() (ver mobile/src/bootstrap.ts).
        this.dataDir = new File(this.context.getFilesDir(), "vitrola");
    }

    // ---------------------------------------------------------------- banco

    private interface Query<T> {
        T run(SQLiteDatabase db);
    }

    private <T> T read(Query<T> q, T fallback) {
        File file = new File(dataDir, "vitrola.db");
        if (!file.isFile()) return fallback;
        SQLiteDatabase db = null;
        try {
            db = SQLiteDatabase.openDatabase(file.getPath(), null,
                SQLiteDatabase.OPEN_READONLY | SQLiteDatabase.NO_LOCALIZED_COLLATORS);
            return q.run(db);
        } catch (Exception e) {
            Log.w(TAG, "Falha ao ler o banco: " + e.getMessage());
            return fallback;
        } finally {
            if (db != null) db.close();
        }
    }

    private static String setting(SQLiteDatabase db, String key) {
        try (Cursor c = db.rawQuery("SELECT value FROM settings WHERE key = ?", new String[] { key })) {
            return c.moveToFirst() ? c.getString(0) : "";
        }
    }

    /** "Tocar sem baixar" ligado: faixas ainda não baixadas também aparecem (com o app aberto). */
    private static boolean streaming(SQLiteDatabase db) {
        String v = setting(db, "streamWithoutDownload");
        return "1".equals(v) || "true".equals(v);
    }

    /** Pasta das músicas (`mediaDir` é relativo a DATA_DIR, igual a `api/src/settings.ts`). */
    private File mediaDir(SQLiteDatabase db) {
        String configured = setting(db, "mediaDir").trim();
        if (!configured.isEmpty()) {
            File dir = inside(new File(dataDir, configured));
            if (dir != null) return dir;
        }
        return new File(dataDir, "media");
    }

    /** Só aceita caminhos dentro de DATA_DIR (como `resolveTrackFile`). */
    private File inside(File f) {
        try {
            String base = dataDir.getCanonicalPath() + File.separator;
            File c = f.getCanonicalFile();
            return c.getPath().startsWith(base) ? c : null;
        } catch (Exception e) {
            return null;
        }
    }

    private List<Track> tracks(SQLiteDatabase db, String where, String[] args) {
        List<Track> list = new ArrayList<>();
        boolean streaming = streaming(db);
        File media = mediaDir(db);
        try (Cursor c = db.rawQuery(TRACK_SELECT + where, args)) {
            while (c.moveToNext()) {
                Track t = new Track();
                t.id = c.getLong(0);
                t.title = str(c, 1);
                t.artist = str(c, 2);
                t.album = str(c, 3);
                t.coverUrl = c.isNull(4) ? null : c.getString(4);
                t.durationMs = c.isNull(5) ? 0 : c.getLong(5) * 1000;
                t.ready = "ready".equals(c.getString(6));
                if (t.ready && !c.isNull(7)) {
                    String p = c.getString(7);
                    File f = inside(p.startsWith("/") ? new File(p) : new File(media, p));
                    t.file = f != null ? f.getPath() : null;
                }
                t.albumId = c.getLong(8);
                if (t.ready || streaming) list.add(t);
            }
        }
        return list;
    }

    private static String str(Cursor c, int i) {
        return c.isNull(i) ? "" : c.getString(i);
    }

    // ---------------------------------------------------------------- contextos

    /** Faixas de um contexto (`recent`, `all`, `playlist.N`, `album.N`, `artist.N`, `one.N`). */
    private List<Track> contextTracks(SQLiteDatabase db, String ctx) {
        String[] parts = ctx.split("\\.", 2);
        String kind = parts[0];
        String id = parts.length > 1 ? String.valueOf(parseId(parts[1])) : "0";
        switch (kind) {
            case "recent":
                return tracks(db, "WHERE t.last_played_at IS NOT NULL ORDER BY t.last_played_at DESC LIMIT 100", null);
            case "all":
                return tracks(db, "WHERE t.download_status = 'ready' ORDER BY t.title LIMIT 2000", null);
            case "playlist":
                return tracks(db,
                    "JOIN playlist_entries e ON e.track_id = t.id WHERE e.playlist_id = ? ORDER BY e.position, e.id",
                    new String[] { id });
            case "album":
                return tracks(db, "WHERE t.album_id = ? ORDER BY t.track_number IS NULL, t.track_number, t.id", new String[] { id });
            case "artist":
                return tracks(db,
                    "WHERE t.artist_id = ? ORDER BY al.release_year DESC, al.title, t.track_number IS NULL, t.track_number, t.id",
                    new String[] { id });
            case "one":
                return tracks(db, "WHERE t.id = ?", new String[] { id });
            default:
                return new ArrayList<>();
        }
    }

    private static long parseId(String s) {
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** Fila de um item tocável (`t:...`, `s:...`). Vazia se o ID não for reconhecido. */
    Queue resolve(String mediaId) {
        return read(db -> {
            if (mediaId.startsWith("s:")) {
                return new Queue(contextTracks(db, mediaId.substring(2)), 0, true);
            }
            if (mediaId.startsWith("t:")) {
                int last = mediaId.lastIndexOf(':');
                String ctx = mediaId.substring(2, last);
                long trackId = parseId(mediaId.substring(last + 1));
                List<Track> list = contextTracks(db, ctx);
                int index = 0;
                for (int i = 0; i < list.size(); i++) {
                    if (list.get(i).id == trackId) {
                        index = i;
                        break;
                    }
                }
                return new Queue(list, index, false);
            }
            return new Queue(new ArrayList<>(), 0, false);
        }, new Queue(new ArrayList<>(), 0, false));
    }

    /** "Tocar música no Vitrola" sem dizer o quê: toda a biblioteca baixada, aleatória. */
    Queue everything() {
        return read(db -> new Queue(contextTracks(db, "all"), 0, true), new Queue(new ArrayList<>(), 0, true));
    }

    /** Faixas pelos IDs, na ordem pedida (o player nativo devolve a fila ao JS por eles). */
    List<Track> byIds(List<Long> ids) {
        return read(db -> {
            List<Track> out = new ArrayList<>();
            for (long id : ids) {
                List<Track> one = tracks(db, "WHERE t.id = ?", new String[] { String.valueOf(id) });
                if (!one.isEmpty()) out.add(one.get(0));
            }
            return out;
        }, new ArrayList<>());
    }

    // ---------------------------------------------------------------- voz

    /**
     * Pedido por voz ("Ok Google, tocar X no Vitrola"). Usa as pistas do Assistente
     * (artista, álbum, música, playlist) e, sem elas, tenta nessa ordem: nome exato de
     * artista/álbum/playlist/música e depois parecido.
     */
    Queue forVoice(String query, Bundle extras) {
        String q = query == null ? "" : query.trim();
        if (q.isEmpty() && (extras == null || extras.isEmpty())) return everything();
        return read(db -> {
            String focus = extras != null ? extras.getString(MediaStore.EXTRA_MEDIA_FOCUS) : null;
            String artist = extras != null ? extras.getString(MediaStore.EXTRA_MEDIA_ARTIST) : null;
            String album = extras != null ? extras.getString(MediaStore.EXTRA_MEDIA_ALBUM) : null;
            String title = extras != null ? extras.getString(MediaStore.EXTRA_MEDIA_TITLE) : null;
            String playlist = extras != null ? extras.getString(EXTRA_PLAYLIST) : null;

            if (MediaStore.Audio.Playlists.ENTRY_CONTENT_TYPE.equals(focus) && notEmpty(playlist)) {
                Queue r = byName(db, "playlist", playlist, false);
                if (r != null) return r;
            }
            if (MediaStore.Audio.Albums.ENTRY_CONTENT_TYPE.equals(focus) && notEmpty(album)) {
                Queue r = byName(db, "album", album, false);
                if (r != null) return r;
            }
            if (MediaStore.Audio.Artists.ENTRY_CONTENT_TYPE.equals(focus) && notEmpty(artist)) {
                Queue r = byName(db, "artist", artist, false);
                if (r != null) return r;
            }
            if (notEmpty(title)) {
                Queue r = trackByTitle(db, title, artist);
                if (r != null) return r;
            }
            if (q.isEmpty()) return new Queue(contextTracks(db, "all"), 0, true);
            for (boolean exact : new boolean[] { true, false }) {
                for (String kind : new String[] { "artist", "album", "playlist" }) {
                    Queue r = byName(db, kind, q, exact);
                    if (r != null) return r;
                }
                Queue r = exact ? trackByTitle(db, q, null) : trackLike(db, q);
                if (r != null) return r;
            }
            return new Queue(new ArrayList<>(), 0, false);
        }, new Queue(new ArrayList<>(), 0, false));
    }

    private static boolean notEmpty(String s) {
        return s != null && !s.trim().isEmpty();
    }

    /** Artista/álbum/playlist com esse nome. Artista toca aleatório; álbum e playlist, em ordem. */
    private Queue byName(SQLiteDatabase db, String kind, String name, boolean exact) {
        String sql;
        switch (kind) {
            case "artist": sql = "SELECT id FROM artists WHERE name " + (exact ? "= ? COLLATE NOCASE" : "LIKE ?") + " ORDER BY length(name) LIMIT 1"; break;
            case "album": sql = "SELECT id FROM albums WHERE title " + (exact ? "= ? COLLATE NOCASE" : "LIKE ?") + " ORDER BY length(title) LIMIT 1"; break;
            case "playlist": sql = "SELECT id FROM playlists WHERE name " + (exact ? "= ? COLLATE NOCASE" : "LIKE ?") + " ORDER BY length(name) LIMIT 1"; break;
            default: return null;
        }
        String arg = exact ? name.trim() : "%" + name.trim() + "%";
        try (Cursor c = db.rawQuery(sql, new String[] { arg })) {
            if (!c.moveToFirst()) return null;
            List<Track> list = contextTracks(db, kind + "." + c.getLong(0));
            return list.isEmpty() ? null : new Queue(list, 0, kind.equals("artist"));
        }
    }

    /** Música pelo título (e artista, se veio): toca ela e segue o álbum. */
    private Queue trackByTitle(SQLiteDatabase db, String title, String artist) {
        List<Track> found = notEmpty(artist)
            ? tracks(db, "WHERE t.title = ? COLLATE NOCASE AND ar.name LIKE ? LIMIT 1", new String[] { title.trim(), "%" + artist.trim() + "%" })
            : new ArrayList<>();
        if (found.isEmpty()) found = tracks(db, "WHERE t.title = ? COLLATE NOCASE LIMIT 1", new String[] { title.trim() });
        if (found.isEmpty()) found = tracks(db, "WHERE t.title LIKE ? ORDER BY length(t.title) LIMIT 1", new String[] { "%" + title.trim() + "%" });
        return found.isEmpty() ? null : withAlbum(db, found.get(0));
    }

    /** Busca livre ("tempo perdido legião"): título, ou título + artista juntos. */
    private Queue trackLike(SQLiteDatabase db, String q) {
        String like = "%" + q + "%";
        List<Track> found = tracks(db,
            "WHERE t.title LIKE ? OR (t.title || ' ' || ar.name) LIKE ? OR (ar.name || ' ' || t.title) LIKE ? ORDER BY length(t.title) LIMIT 1",
            new String[] { like, like, like });
        return found.isEmpty() ? null : withAlbum(db, found.get(0));
    }

    private Queue withAlbum(SQLiteDatabase db, Track t) {
        List<Track> album = contextTracks(db, "album." + t.albumId);
        for (int i = 0; i < album.size(); i++) {
            if (album.get(i).id == t.id) return new Queue(album, i, false);
        }
        List<Track> one = new ArrayList<>();
        one.add(t);
        return new Queue(one, 0, false);
    }

    // ---------------------------------------------------------------- navegação

    List<MediaItem> children(String parentId) {
        return read(db -> {
            List<MediaItem> out = new ArrayList<>();
            switch (parentId) {
                case ROOT:
                    out.add(browsable(RECENT, "Recentes", null, null, STYLE_LIST));
                    out.add(browsable(PLAYLISTS, "Playlists", null, null, STYLE_LIST));
                    out.add(browsable(ALBUMS, "Álbuns", null, null, STYLE_GRID));
                    out.add(browsable(ARTISTS, "Artistas", null, null, STYLE_LIST));
                    return out;
                case RECENT:
                    out.add(playable("s:all", "Aleatório", "Toda a biblioteca", null, null));
                    addTracks(out, contextTracks(db, "recent"), "recent", null);
                    return out;
                case PLAYLISTS:
                    try (Cursor c = db.rawQuery(
                        "SELECT p.id, p.name, (SELECT COUNT(*) FROM playlist_entries e WHERE e.playlist_id = p.id) "
                            + "FROM playlists p ORDER BY p.name COLLATE NOCASE LIMIT " + LIST_LIMIT, null)) {
                        while (c.moveToNext()) {
                            out.add(browsable("playlist:" + c.getLong(0), str(c, 1), plural(c.getInt(2), "música", "músicas"), null, STYLE_LIST));
                        }
                    }
                    return out;
                case ALBUMS:
                    try (Cursor c = db.rawQuery(
                        "SELECT al.id, al.title, ar.name FROM albums al JOIN artists ar ON ar.id = al.artist_id "
                            + "WHERE EXISTS (SELECT 1 FROM tracks t WHERE t.album_id = al.id AND t.download_status = 'ready') "
                            + "ORDER BY al.title COLLATE NOCASE LIMIT " + LIST_LIMIT, null)) {
                        while (c.moveToNext()) {
                            out.add(browsable("album:" + c.getLong(0), str(c, 1), str(c, 2), ArtProvider.album(context, c.getLong(0)), STYLE_LIST));
                        }
                    }
                    return out;
                case ARTISTS:
                    try (Cursor c = db.rawQuery(
                        "SELECT ar.id, ar.name FROM artists ar "
                            + "WHERE EXISTS (SELECT 1 FROM tracks t WHERE t.artist_id = ar.id AND t.download_status = 'ready') "
                            + "ORDER BY ar.name COLLATE NOCASE LIMIT " + LIST_LIMIT, null)) {
                        while (c.moveToNext()) {
                            out.add(browsable("artist:" + c.getLong(0), str(c, 1), null, ArtProvider.artist(context, c.getLong(0)), STYLE_GRID));
                        }
                    }
                    return out;
                default:
                    break;
            }
            int colon = parentId.indexOf(':');
            if (colon < 0) return out;
            String kind = parentId.substring(0, colon);
            long id = parseId(parentId.substring(colon + 1));
            String ctx = kind + "." + id;
            switch (kind) {
                case "playlist":
                case "album": {
                    List<Track> list = contextTracks(db, ctx);
                    if (list.size() > 1) out.add(playable("s:" + ctx, "Aleatório", plural(list.size(), "música", "músicas"), null, null));
                    addTracks(out, list, ctx, kind.equals("album") ? ArtProvider.album(context, id) : null);
                    return out;
                }
                case "artist": {
                    out.add(playable("s:" + ctx, "Aleatório", "Todas as músicas do artista", ArtProvider.artist(context, id), null));
                    try (Cursor c = db.rawQuery(
                        "SELECT al.id, al.title, al.release_year FROM albums al WHERE al.artist_id = ? "
                            + "AND EXISTS (SELECT 1 FROM tracks t WHERE t.album_id = al.id AND t.download_status = 'ready') "
                            + "ORDER BY al.release_year DESC, al.title", new String[] { String.valueOf(id) })) {
                        while (c.moveToNext()) {
                            String year = c.isNull(2) ? null : String.valueOf(c.getInt(2));
                            out.add(browsable("album:" + c.getLong(0), str(c, 1), year, ArtProvider.album(context, c.getLong(0)), STYLE_LIST));
                        }
                    }
                    return out;
                }
                default:
                    return out;
            }
        }, new ArrayList<>());
    }

    /** Busca do carro (teclado com o carro parado ou voz na tela de busca). */
    List<MediaItem> search(String query) {
        String q = query == null ? "" : query.trim();
        if (q.isEmpty()) return new ArrayList<>();
        String like = "%" + q + "%";
        return read(db -> {
            List<MediaItem> out = new ArrayList<>();
            Bundle songs = group("Músicas");
            for (Track t : tracks(db, "WHERE t.title LIKE ? OR ar.name LIKE ? OR al.title LIKE ? ORDER BY t.title LIMIT " + SEARCH_LIMIT,
                new String[] { like, like, like })) {
                out.add(playable("t:album." + t.albumId + ":" + t.id, t.title, t.artist + " · " + t.album, ArtProvider.album(context, t.albumId), songs));
            }
            Bundle albums = group("Álbuns");
            try (Cursor c = db.rawQuery(
                "SELECT al.id, al.title, ar.name FROM albums al JOIN artists ar ON ar.id = al.artist_id "
                    + "WHERE (al.title LIKE ? OR ar.name LIKE ?) "
                    + "AND EXISTS (SELECT 1 FROM tracks t WHERE t.album_id = al.id AND t.download_status = 'ready') "
                    + "ORDER BY al.title LIMIT " + SEARCH_LIMIT, new String[] { like, like })) {
                while (c.moveToNext()) {
                    out.add(browsable("album:" + c.getLong(0), str(c, 1), str(c, 2), ArtProvider.album(context, c.getLong(0)), STYLE_LIST, albums));
                }
            }
            Bundle artists = group("Artistas");
            try (Cursor c = db.rawQuery("SELECT id, name FROM artists WHERE name LIKE ? ORDER BY name LIMIT " + SEARCH_LIMIT, new String[] { like })) {
                while (c.moveToNext()) {
                    out.add(browsable("artist:" + c.getLong(0), str(c, 1), null, ArtProvider.artist(context, c.getLong(0)), STYLE_GRID, artists));
                }
            }
            Bundle playlists = group("Playlists");
            try (Cursor c = db.rawQuery("SELECT id, name FROM playlists WHERE name LIKE ? ORDER BY name LIMIT " + SEARCH_LIMIT, new String[] { like })) {
                while (c.moveToNext()) {
                    out.add(browsable("playlist:" + c.getLong(0), str(c, 1), null, null, STYLE_LIST, playlists));
                }
            }
            return out;
        }, new ArrayList<>());
    }

    /** Resultado do catálogo externo (busca feita pelo JS, com o app aberto). */
    static MediaItem catalogItem(String catalogId, String title, String artist, String album) {
        String sub = album == null || album.isEmpty() ? artist : artist + " · " + album;
        return playable(CATALOG_PREFIX + catalogId, title, sub, null, group("No catálogo (baixa ao tocar)"));
    }

    private void addTracks(List<MediaItem> out, List<Track> list, String ctx, Uri icon) {
        for (Track t : list) {
            String sub = ctx.startsWith("album.") ? t.artist : t.artist + " · " + t.album;
            if (!t.ready) sub = "Streaming · " + sub;
            out.add(playable("t:" + ctx + ":" + t.id, t.title, sub, icon != null ? icon : ArtProvider.album(context, t.albumId), null));
        }
    }

    private static Bundle group(String title) {
        Bundle b = new Bundle();
        b.putString(STYLE_GROUP_TITLE, title);
        return b;
    }

    private static String plural(int n, String one, String many) {
        return String.format(Locale.ROOT, "%d %s", n, n == 1 ? one : many);
    }

    private static MediaItem browsable(String id, String title, String subtitle, Uri icon, int childStyle) {
        return browsable(id, title, subtitle, icon, childStyle, null);
    }

    private static MediaItem browsable(String id, String title, String subtitle, Uri icon, int childStyle, Bundle group) {
        Bundle extras = new Bundle();
        if (group != null) extras.putAll(group);
        extras.putInt(STYLE_BROWSABLE, childStyle);
        extras.putInt(STYLE_PLAYABLE, STYLE_LIST);
        MediaDescriptionCompat d = new MediaDescriptionCompat.Builder()
            .setMediaId(id)
            .setTitle(title)
            .setSubtitle(subtitle)
            .setIconUri(icon)
            .setExtras(extras)
            .build();
        return new MediaItem(d, MediaItem.FLAG_BROWSABLE);
    }

    private static MediaItem playable(String id, String title, String subtitle, Uri icon, Bundle extras) {
        MediaDescriptionCompat d = new MediaDescriptionCompat.Builder()
            .setMediaId(id)
            .setTitle(title)
            .setSubtitle(subtitle)
            .setIconUri(icon)
            .setExtras(extras != null ? extras : new Bundle())
            .build();
        return new MediaItem(d, MediaItem.FLAG_PLAYABLE);
    }

    /** Capa de um álbum ou foto de um artista (para o ArtProvider). */
    String imageUrl(String kind, long id) {
        String sql = "artist".equals(kind) ? "SELECT image_url FROM artists WHERE id = ?"
            : "album".equals(kind) ? "SELECT cover_url FROM albums WHERE id = ?" : null;
        if (sql == null) return null;
        return read(db -> {
            try (Cursor c = db.rawQuery(sql, new String[] { String.valueOf(id) })) {
                return c.moveToFirst() && !c.isNull(0) ? c.getString(0) : null;
            }
        }, null);
    }

    static List<Long> ids(List<Track> tracks) {
        List<Long> out = new ArrayList<>(tracks.size());
        for (Track t : tracks) out.add(t.id);
        return out;
    }

    static <T> List<T> shuffled(List<T> list) {
        List<T> copy = new ArrayList<>(list);
        Collections.shuffle(copy);
        return copy;
    }
}
