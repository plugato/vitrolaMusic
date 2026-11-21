package app.vitrola.music;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.support.v4.media.MediaBrowserCompat.MediaItem;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media.MediaBrowserServiceCompat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * O que faz o Vitrola aparecer no Android Auto: árvore de navegação (Recentes, Playlists,
 * Álbuns, Artistas) e busca, lidas do banco pelo `CarLibrary`. Tocar um item vai para a
 * sessão de mídia (`MediaControls`), que escolhe entre o player do WebView e o nativo.
 */
public class VitrolaBrowserService extends MediaBrowserServiceCompat {

    /** Quem pode navegar na biblioteca: Android Auto, Assistente e o próprio sistema. */
    private static final Set<String> ALLOWED = new HashSet<>(Arrays.asList(
        "com.google.android.projection.gearhead", // Android Auto (e o DHU)
        "com.google.android.carassistant",
        "com.google.android.googlequicksearchbox", // Assistente
        "com.google.android.apps.automotive.templates.host",
        "com.android.systemui",
        "com.android.bluetooth" // AVRCP 1.6 (navegação pela central multimídia via Bluetooth)
    ));
    private static final String SEARCH_SUPPORTED = "android.media.browse.SEARCH_SUPPORTED";

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private CarLibrary library;

    @Override
    public void onCreate() {
        super.onCreate();
        library = new CarLibrary(this);
        setSessionToken(MediaControls.get(this).token());
    }

    @Override
    public void onDestroy() {
        io.shutdown();
        super.onDestroy();
    }

    @Nullable
    @Override
    public BrowserRoot onGetRoot(@NonNull String clientPackageName, int clientUid, @Nullable Bundle rootHints) {
        boolean trusted = clientUid == Process.myUid() || clientUid == Process.SYSTEM_UID || ALLOWED.contains(clientPackageName);
        if (!trusted) return null;
        // Retomada de mídia do Android 11+ (pede só "o último que tocou"): não suportamos.
        if (rootHints != null && rootHints.getBoolean(BrowserRoot.EXTRA_RECENT)) return null;
        Bundle extras = new Bundle();
        extras.putBoolean(SEARCH_SUPPORTED, true);
        extras.putInt(CarLibrary.STYLE_BROWSABLE, CarLibrary.STYLE_LIST);
        extras.putInt(CarLibrary.STYLE_PLAYABLE, CarLibrary.STYLE_LIST);
        return new BrowserRoot(CarLibrary.ROOT, extras);
    }

    @Override
    public void onLoadChildren(@NonNull String parentId, @NonNull Result<List<MediaItem>> result) {
        result.detach();
        io.execute(() -> {
            List<MediaItem> items = library.children(parentId);
            main.post(() -> result.sendResult(items));
        });
    }

    @Override
    public void onSearch(@NonNull String query, @Nullable Bundle extras, @NonNull Result<List<MediaItem>> result) {
        result.detach();
        io.execute(() -> {
            List<MediaItem> local = library.search(query);
            main.post(() -> {
                MediaControls.CarBridge bridge = MediaControls.get(this).bridge();
                if (bridge == null || query.trim().length() < 2) {
                    result.sendResult(local);
                    return;
                }
                // App aberto: junta os resultados do catálogo (Deezer) depois dos da biblioteca.
                bridge.searchCatalog(query, catalog -> {
                    List<MediaItem> all = new ArrayList<>(local);
                    all.addAll(catalog);
                    result.sendResult(all);
                });
            });
        });
    }
}
