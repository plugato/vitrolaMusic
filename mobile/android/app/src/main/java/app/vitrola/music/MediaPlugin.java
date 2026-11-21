package app.vitrola.music;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothClass;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.support.v4.media.MediaBrowserCompat;
import androidx.core.content.ContextCompat;
import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.PermissionState;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONObject;

/**
 * Integração com o carro e os controles do sistema:
 * - sessão de mídia nativa (MediaControls): título/artista/álbum e botões no carro e na notificação;
 * - "becoming noisy": o áudio ia sair pelo alto-falante (Bluetooth do carro desligou, fone saiu) → JS pausa;
 * - detecção do carro: saída Bluetooth conectada cuja classe é "som de carro" ou "viva-voz";
 * - Android Auto (`CarBridge`): o carro pede para tocar uma fila, buscar ou tocar do catálogo,
 *   e o JS (`mobile/src/carBridge.ts`) atende com o player e a API do WebView.
 */
@CapacitorPlugin(
    name = "Media",
    permissions = { @Permission(alias = "bluetooth", strings = { Manifest.permission.BLUETOOTH_CONNECT }) }
)
public class MediaPlugin extends Plugin implements MediaControls.CarBridge {

    private static final long CATALOG_TIMEOUT_MS = 6000;

    private AudioManager audioManager;
    private final Handler main = new Handler(Looper.getMainLooper());
    private boolean carConnected;
    private String carName = "";
    private MediaControls.Listener actionListener;
    private int nextRequestId = 1;
    private final Map<Integer, MediaControls.CatalogCallback> pendingSearches = new HashMap<>();

    private final BroadcastReceiver noisyReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (AudioManager.ACTION_AUDIO_BECOMING_NOISY.equals(intent.getAction())) {
                notifyListeners("noisy", new JSObject());
            }
        }
    };

    private final AudioDeviceCallback deviceCallback = new AudioDeviceCallback() {
        @Override public void onAudioDevicesAdded(AudioDeviceInfo[] added) { main.postDelayed(MediaPlugin.this::checkCar, 500); }
        @Override public void onAudioDevicesRemoved(AudioDeviceInfo[] removed) { checkCar(); }
    };

    @Override
    public void load() {
        Context ctx = getContext();
        audioManager = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
        actionListener = (action, pos) -> {
            JSObject data = new JSObject();
            data.put("action", action);
            data.put("positionMs", pos);
            notifyListeners("action", data);
        };
        MediaControls media = MediaControls.get(ctx);
        media.setListener(actionListener);
        media.setBridge(this);
        // O carro tocava pelo player nativo (app fechado): o WebView continua de onde parou.
        main.post(() -> {
            NativePlayer player = NativePlayer.active();
            NativePlayer.Snapshot s = player != null ? player.takeOver() : null;
            if (s != null) playQueue(s.ids, s.index, false, s.positionMs, s.playing);
        });
        ContextCompat.registerReceiver(ctx, noisyReceiver, new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY), ContextCompat.RECEIVER_NOT_EXPORTED);
        audioManager.registerAudioDeviceCallback(deviceCallback, main);
        checkCar();
    }

    @Override
    protected void handleOnDestroy() {
        MediaControls.get(getContext()).detach(actionListener, this);
        for (MediaControls.CatalogCallback cb : pendingSearches.values()) cb.done(new ArrayList<>());
        pendingSearches.clear();
        try {
            getContext().unregisterReceiver(noisyReceiver);
        } catch (IllegalArgumentException ignored) {
            // já removido
        }
        audioManager.unregisterAudioDeviceCallback(deviceCallback);
    }

    /** Estado do player para o carro, a notificação e a tela de bloqueio. */
    @PluginMethod
    public void update(PluginCall call) {
        MediaControls.get(getContext()).update(
            Boolean.TRUE.equals(call.getBoolean("hasTrack", false)),
            Boolean.TRUE.equals(call.getBoolean("playing", false)),
            call.getString("title", ""),
            call.getString("artist", ""),
            call.getString("album", ""),
            call.getString("artworkUrl", null),
            Math.max(0, call.getDouble("durationMs", 0.0).longValue()),
            Math.max(0, call.getDouble("positionMs", 0.0).longValue())
        );
        call.resolve();
    }

    // ---------------------------------------------------------------- Android Auto (CarBridge)

    @Override
    public void playQueue(List<Long> ids, int index, boolean shuffle, long positionMs, boolean play) {
        JSArray list = new JSArray();
        for (long id : ids) list.put(String.valueOf(id));
        JSObject data = new JSObject();
        data.put("ids", list);
        data.put("index", index);
        data.put("shuffle", shuffle);
        data.put("positionMs", positionMs);
        data.put("play", play);
        // Retido: na abertura do app o JS ainda não registrou o ouvinte.
        notifyListeners("carPlayQueue", data, true);
    }

    @Override
    public void playCatalog(String catalogId) {
        JSObject data = new JSObject();
        data.put("catalogId", catalogId);
        notifyListeners("carPlayCatalog", data, true);
    }

    /** Pede ao JS a busca no catálogo; sem resposta em 6 s, segue só com a biblioteca. */
    @Override
    public void searchCatalog(String query, MediaControls.CatalogCallback callback) {
        main.post(() -> {
            int id = nextRequestId++;
            pendingSearches.put(id, callback);
            JSObject data = new JSObject();
            data.put("requestId", id);
            data.put("query", query);
            notifyListeners("carSearch", data);
            main.postDelayed(() -> finishSearch(id, new ArrayList<>()), CATALOG_TIMEOUT_MS);
        });
    }

    private void finishSearch(int id, List<MediaBrowserCompat.MediaItem> items) {
        MediaControls.CatalogCallback cb = pendingSearches.remove(id);
        if (cb != null) cb.done(items);
    }

    /** Resposta do JS ao evento `carSearch`. */
    @PluginMethod
    public void carSearchResult(PluginCall call) {
        int id = call.getInt("requestId", 0);
        JSArray tracks = call.getArray("tracks", new JSArray());
        List<MediaBrowserCompat.MediaItem> items = new ArrayList<>();
        for (int i = 0; i < tracks.length(); i++) {
            JSONObject t = tracks.optJSONObject(i);
            if (t == null) continue;
            String catalogId = t.optString("catalogId", "");
            if (!catalogId.matches("\\d{1,20}")) continue;
            items.add(CarLibrary.catalogItem(catalogId, t.optString("title", ""), t.optString("artistName", ""), t.optString("albumTitle", "")));
        }
        main.post(() -> finishSearch(id, items));
        call.resolve();
    }

    @PluginMethod
    public void getCarState(PluginCall call) {
        checkCar();
        call.resolve(carState());
    }

    /** Android 12+: ler a classe do aparelho Bluetooth (carro?) exige "Dispositivos próximos". */
    @PluginMethod
    public void requestCarPermission(PluginCall call) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || getPermissionState("bluetooth") == PermissionState.GRANTED) {
            checkCar();
            call.resolve(carState());
            return;
        }
        requestPermissionForAlias("bluetooth", call, "carPermissionResult");
    }

    @PermissionCallback
    private void carPermissionResult(PluginCall call) {
        checkCar();
        call.resolve(carState());
    }

    private JSObject carState() {
        JSObject r = new JSObject();
        r.put("connected", carConnected);
        r.put("name", carName);
        r.put("permission", hasBluetoothPermission());
        return r;
    }

    private boolean hasBluetoothPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true;
        return ContextCompat.checkSelfPermission(getContext(), Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
    }

    /** Procura entre as saídas de áudio um Bluetooth que seja som de carro (ou viva-voz de carro). */
    private void checkCar() {
        boolean found = false;
        String name = "";
        if (hasBluetoothPermission() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            BluetoothManager bm = (BluetoothManager) getContext().getSystemService(Context.BLUETOOTH_SERVICE);
            BluetoothAdapter adapter = bm != null ? bm.getAdapter() : null;
            for (AudioDeviceInfo d : audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
                int type = d.getType();
                if (type != AudioDeviceInfo.TYPE_BLUETOOTH_A2DP && type != AudioDeviceInfo.TYPE_BLUETOOTH_SCO) continue;
                String address = d.getAddress();
                if (adapter == null || address == null || !BluetoothAdapter.checkBluetoothAddress(address)) continue;
                try {
                    BluetoothDevice device = adapter.getRemoteDevice(address);
                    BluetoothClass cls = device.getBluetoothClass();
                    if (cls == null) continue;
                    int dc = cls.getDeviceClass();
                    if (dc == BluetoothClass.Device.AUDIO_VIDEO_CAR_AUDIO || dc == BluetoothClass.Device.AUDIO_VIDEO_HANDSFREE) {
                        found = true;
                        name = String.valueOf(d.getProductName());
                        break;
                    }
                } catch (SecurityException | IllegalArgumentException ignored) {
                    // sem permissão ou endereço inválido: não dá para saber
                }
            }
        }
        if (found != carConnected || !name.equals(carName)) {
            carConnected = found;
            carName = name;
            notifyListeners("carChanged", carState());
        }
    }
}
