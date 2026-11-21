package app.vitrola.music;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.util.Log;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;

/**
 * Capas para a tela do carro. O Android Auto só carrega ícones por `content://`, então este
 * provider baixa a capa do álbum (ou a foto do artista) na primeira vez, guarda em cache e
 * entrega o arquivo. Só serve imagens que já estão no banco (`content://…art/album/12`):
 * não baixa URLs arbitrárias, por isso pode ser exportado.
 */
public class ArtProvider extends ContentProvider {

    private static final String TAG = "VitrolaArt";
    private static final long MAX_BYTES = 4L * 1024 * 1024;

    static Uri album(Context ctx, long id) {
        return uri(ctx, "album", id);
    }

    static Uri artist(Context ctx, long id) {
        return uri(ctx, "artist", id);
    }

    private static Uri uri(Context ctx, String kind, long id) {
        return new Uri.Builder().scheme("content").authority(ctx.getPackageName() + ".art")
            .appendPath(kind).appendPath(String.valueOf(id)).build();
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode)) throw new FileNotFoundException("Somente leitura");
        List<String> seg = uri.getPathSegments();
        if (seg.size() != 2) throw new FileNotFoundException(uri.toString());
        String kind = seg.get(0);
        long id;
        try {
            id = Long.parseLong(seg.get(1));
        } catch (NumberFormatException e) {
            throw new FileNotFoundException(uri.toString());
        }
        Context ctx = getContext();
        if (ctx == null) throw new FileNotFoundException("Sem contexto");
        String url = new CarLibrary(ctx).imageUrl(kind, id);
        if (url == null || !url.startsWith("https://")) throw new FileNotFoundException("Sem imagem");

        File dir = new File(ctx.getCacheDir(), "car-art");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        // A URL entra no nome: se a capa mudar, baixa de novo.
        File file = new File(dir, kind + "-" + id + "-" + Integer.toHexString(url.hashCode()) + ".img");
        if (!file.isFile() && !download(url, file)) throw new FileNotFoundException("Capa não carregou");
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    private static boolean download(String url, File dest) {
        File tmp = new File(dest.getPath() + ".tmp");
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);
            if (conn.getResponseCode() != 200) return false;
            long total = 0;
            try (InputStream in = conn.getInputStream(); OutputStream out = new FileOutputStream(tmp)) {
                byte[] buf = new byte[16 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) {
                    total += n;
                    if (total > MAX_BYTES) return false;
                    out.write(buf, 0, n);
                }
            }
            return tmp.renameTo(dest);
        } catch (Exception e) {
            Log.w(TAG, "Capa não carregou: " + e.getMessage());
            return false;
        } finally {
            if (conn != null) conn.disconnect();
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
        }
    }

    @Override
    public String getType(Uri uri) {
        return "image/jpeg";
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
