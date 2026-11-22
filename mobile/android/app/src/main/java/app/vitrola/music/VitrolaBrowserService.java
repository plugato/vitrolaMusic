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

    /**
     * Clientes conhecidos (só para o diagnóstico). Não filtramos por pacote: dependendo da
     * versão, o Android Auto conecta pelo próprio app, pelo Google Play Services
     * (`com.google.android.gms`) ou pelo Assistente, e recusar um deles fazia o carro só abrir
     * o app no celular. A biblioteca exposta é só a lista de músicas.
     */
    private static final Set<String> KNOWN = new HashSet<>(Arrays.asList(
        "com.google.android.projection.gearhead", // Android Auto (e o DHU)
        "com.google.android.gms",
        "com.google.android.carassistant",
        "com.google.android.googlequicksearchbox", // Assistente
        "com.android.systemui",
        "com.android.bluetooth" // AVRCP 1.6
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
        CarDebug.log(this, "Serviço do carro iniciado");
    }

    @Override
    public void onDestroy() {
        io.shutdown();
        super.onDestroy();
    }

    @Nullable
    @Override
    public BrowserRoot onGetRoot(@NonNull String clientPackageName, int clientUid, @Nullable Bundle rootHints) {
        boolean recent = rootHints != null && rootHints.getBoolean(BrowserRoot.EXTRA_RECENT);
        CarDebug.log(this, "Conexão de " + clientPackageName + (KNOWN.contains(clientPackageName) || clientUid == Process.myUid() ? "" : " (desconhecido)")
            + (recent ? " [retomada: recusada]" : ""));
        // Retomada de mídia do Android 11+ (pede só "o último que tocou"): não suportamos.
        if (recent) return null;
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
            List<MediaItem> items;
            try {
                items = library.children(parentId);
                CarDebug.log(this, "Lista \"" + parentId + "\": " + items.size() + " itens");
            } catch (Exception e) {
                items = new ArrayList<>();
                CarDebug.log(this, "Erro na lista \"" + parentId + "\": " + e);
            }
            List<MediaItem> out = items;
            main.post(() -> result.sendResult(out));
        });
    }

    @Override
    public void onSearch(@NonNull String query, @Nullable Bundle extras, @NonNull Result<List<MediaItem>> result) {
        result.detach();
        io.execute(() -> {
            List<MediaItem> found;
            try {
                found = library.search(query);
            } catch (Exception e) {
                found = new ArrayList<>();
                CarDebug.log(this, "Erro na busca: " + e);
            }
            List<MediaItem> local = found;
            CarDebug.log(this, "Busca \"" + query + "\": " + local.size() + " na biblioteca");
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
