package app.vitrola.music;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import androidx.core.content.ContextCompat;
import java.util.ArrayList;
import java.util.List;

/**
 * Player nativo, usado quando o carro pede música e o app (WebView) não está aberto.
 *
 * Toca só faixas baixadas (o stream precisa do yt-dlp, que é chamado pelo JS). Quando o app
 * abre, `takeOver()` para este player e devolve a fila e a posição para o player do WebView
 * continuar de onde parou (`MediaPlugin.load`). Só roda na thread principal.
 */
final class NativePlayer implements MediaPlayer.OnPreparedListener, MediaPlayer.OnCompletionListener,
    MediaPlayer.OnErrorListener, AudioManager.OnAudioFocusChangeListener {

    private static final String TAG = "VitrolaNative";
    /** Pausado por tanto tempo: libera o serviço em primeiro plano (igual ao keepAlive.ts). */
    private static final long PAUSED_GRACE_MS = 10 * 60 * 1000;
    private static final long PREVIOUS_RESTART_MS = 3000;

    private static NativePlayer instance;

    static synchronized NativePlayer get(Context ctx) {
        if (instance == null) instance = new NativePlayer(ctx.getApplicationContext());
        return instance;
    }

    /** Já existe e tem fila? (sem criar). */
    static NativePlayer active() {
        NativePlayer p = instance;
        return p != null && p.hasQueue() ? p : null;
    }

    /** Fila e posição entregues ao player do WebView. */
    static final class Snapshot {
        final List<Long> ids;
        final int index;
        final long positionMs;
        final boolean playing;

        Snapshot(List<Long> ids, int index, long positionMs, boolean playing) {
            this.ids = ids;
            this.index = index;
            this.positionMs = positionMs;
            this.playing = playing;
        }
    }

    private final Context context;
    private final AudioManager audio;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AudioAttributes attributes = new AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .build();

    private List<CarLibrary.Track> queue = new ArrayList<>();
    private int index;
    private MediaPlayer player;
    private boolean prepared;
    /** O usuário quer tocando (vale também enquanto prepara). */
    private boolean wantPlaying;
    private long pendingSeekMs;
    private boolean resumeOnFocusGain;
    private AudioFocusRequest focusRequest;
    private boolean noisyRegistered;
    private boolean foreground;

    private final Runnable stopForegroundLater = this::stopForeground;

    private final BroadcastReceiver noisy = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent intent) {
            if (AudioManager.ACTION_AUDIO_BECOMING_NOISY.equals(intent.getAction())) pause();
        }
    };

    private NativePlayer(Context context) {
        this.context = context;
        this.audio = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
    }

    boolean hasQueue() {
        return !queue.isEmpty();
    }

    /** Começa uma fila. Só entram faixas baixadas; devolve false se nenhuma serve. */
    boolean playQueue(CarLibrary.Queue q, long startPositionMs) {
        List<CarLibrary.Track> list = new ArrayList<>();
        for (CarLibrary.Track t : q.tracks) {
            if (t.playableOffline()) list.add(t);
        }
        if (list.isEmpty()) return false;
        // Começa pela escolhida ou, se ela não está baixada, pela próxima que está.
        int start = 0;
        for (int i = q.index; i < q.tracks.size(); i++) {
            if (q.tracks.get(i).playableOffline()) {
                start = list.indexOf(q.tracks.get(i));
                break;
            }
        }
        if (q.shuffle) {
            list = CarLibrary.shuffled(list);
            start = 0;
        }
        queue = list;
        index = Math.max(0, start);
        wantPlaying = true;
        load(startPositionMs);
        return true;
    }

    /** Botões do carro, do volante, da notificação e do fone. */
    void handle(String action, long positionMs) {
        switch (action) {
            case "play": play(); break;
            case "pause": pause(); break;
            case "next": next(); break;
            case "previous": previous(); break;
            case "seekTo": seekTo(positionMs); break;
            default: break;
        }
    }

    void play() {
        if (queue.isEmpty()) {
            // "Tocar" sem nada carregado (ex.: "Ok Google, tocar música no Vitrola").
            MediaControls.get(context).playEverything();
            return;
        }
        wantPlaying = true;
        if (player == null) {
            load(pendingSeekMs);
            return;
        }
        if (prepared && requestFocus()) {
            player.start();
            started();
        }
        publish();
    }

    void pause() {
        wantPlaying = false;
        resumeOnFocusGain = false;
        if (player != null && prepared && player.isPlaying()) player.pause();
        unregisterNoisy();
        publish();
        main.removeCallbacks(stopForegroundLater);
        main.postDelayed(stopForegroundLater, PAUSED_GRACE_MS);
    }

    void next() {
        if (queue.isEmpty()) return;
        if (index + 1 >= queue.size()) {
            // Fim da fila: para no começo da última faixa.
            wantPlaying = false;
            index = queue.size() - 1;
            load(0);
            return;
        }
        index++;
        load(0);
    }

    void previous() {
        if (queue.isEmpty()) return;
        if (position() > PREVIOUS_RESTART_MS || index == 0) {
            seekTo(0);
            return;
        }
        index--;
        load(0);
    }

    void seekTo(long ms) {
        if (player != null && prepared) player.seekTo((int) ms);
        else pendingSeekMs = ms;
        publish();
    }

    /** O app abriu: para aqui e devolve o que tocava para o WebView continuar. */
    Snapshot takeOver() {
        if (queue.isEmpty()) return null;
        Snapshot s = new Snapshot(CarLibrary.ids(queue), index, position(), wantPlaying);
        release();
        queue = new ArrayList<>();
        // A sessão passa a refletir o player do WebView (que manda o estado logo em seguida).
        stopForeground();
        return s;
    }

    private long position() {
        if (player != null && prepared) return player.getCurrentPosition();
        return pendingSeekMs;
    }

    private void load(long seekMs) {
        release();
        CarLibrary.Track t = queue.get(index);
        pendingSeekMs = seekMs;
        try {
            MediaPlayer mp = new MediaPlayer();
            mp.setAudioAttributes(attributes);
            mp.setWakeMode(context, android.os.PowerManager.PARTIAL_WAKE_LOCK);
            mp.setOnPreparedListener(this);
            mp.setOnCompletionListener(this);
            mp.setOnErrorListener(this);
            mp.setDataSource(t.file);
            player = mp;
            prepared = false;
            mp.prepareAsync();
        } catch (Exception e) {
            Log.w(TAG, "Não abriu " + t.file + ": " + e.getMessage());
            skipBroken();
            return;
        }
        publish();
    }

    @Override
    public void onPrepared(MediaPlayer mp) {
        if (mp != player) return;
        prepared = true;
        if (pendingSeekMs > 0) mp.seekTo((int) pendingSeekMs);
        pendingSeekMs = 0;
        if (wantPlaying && requestFocus()) {
            mp.start();
            started();
        }
        publish();
    }

    @Override
    public void onCompletion(MediaPlayer mp) {
        if (mp != player) return;
        next();
    }

    @Override
    public boolean onError(MediaPlayer mp, int what, int extra) {
        if (mp != player) return true;
        Log.w(TAG, "Erro no player: " + what + "/" + extra);
        skipBroken();
        return true;
    }

    /** Arquivo com problema: tira da fila e segue. */
    private void skipBroken() {
        release();
        if (index < queue.size()) queue.remove(index);
        if (queue.isEmpty()) {
            wantPlaying = false;
            MediaControls.get(context).showError("Nenhuma música baixada para tocar");
            stopForeground();
            return;
        }
        if (index >= queue.size()) index = 0;
        load(0);
    }

    private void started() {
        registerNoisy();
        main.removeCallbacks(stopForegroundLater);
        startForeground();
    }

    private void release() {
        if (player != null) {
            try {
                player.release();
            } catch (Exception ignored) {
                // já liberado
            }
        }
        player = null;
        prepared = false;
    }

    /** Manda título, capa, posição e tocando/pausado para o carro e a notificação. */
    private void publish() {
        if (queue.isEmpty()) {
            MediaControls.get(context).update(false, false, "", "", "", null, 0, 0);
            return;
        }
        CarLibrary.Track t = queue.get(index);
        boolean playing = player != null && prepared && player.isPlaying();
        long duration = player != null && prepared ? Math.max(0, player.getDuration()) : t.durationMs;
        MediaControls.get(context).update(true, playing || (wantPlaying && !prepared), t.title, t.artist, t.album,
            t.coverUrl, duration, position());
    }

    // ---------------------------------------------------------------- foco de áudio

    private boolean requestFocus() {
        int r;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (focusRequest == null) {
                focusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(attributes)
                    .setOnAudioFocusChangeListener(this, main)
                    .setWillPauseWhenDucked(false)
                    .build();
            }
            r = audio.requestAudioFocus(focusRequest);
        } else {
            r = audio.requestAudioFocus(this, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);
        }
        return r == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
    }

    private void abandonFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (focusRequest != null) audio.abandonAudioFocusRequest(focusRequest);
        } else {
            audio.abandonAudioFocus(this);
        }
    }

    @Override
    public void onAudioFocusChange(int change) {
        switch (change) {
            case AudioManager.AUDIOFOCUS_LOSS:
                pause();
                abandonFocus();
                break;
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT: {
                // Navegação falando, ligação: pausa e volta sozinho.
                boolean was = wantPlaying;
                pause();
                resumeOnFocusGain = was;
                break;
            }
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
                if (player != null) player.setVolume(0.25f, 0.25f);
                break;
            case AudioManager.AUDIOFOCUS_GAIN:
                if (player != null) player.setVolume(1f, 1f);
                if (resumeOnFocusGain) {
                    resumeOnFocusGain = false;
                    play();
                }
                break;
            default:
                break;
        }
    }

    // ---------------------------------------------------------------- sistema

    private void registerNoisy() {
        if (noisyRegistered) return;
        ContextCompat.registerReceiver(context, noisy, new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
            ContextCompat.RECEIVER_NOT_EXPORTED);
        noisyRegistered = true;
    }

    private void unregisterNoisy() {
        if (!noisyRegistered) return;
        try {
            context.unregisterReceiver(noisy);
        } catch (IllegalArgumentException ignored) {
            // já removido
        }
        noisyRegistered = false;
    }

    /** Notificação de player + processo vivo com a tela apagada (o mesmo serviço do JS). */
    private void startForeground() {
        if (foreground) {
            KeepAliveService.refresh(context);
            return;
        }
        Intent i = new Intent(context, KeepAliveService.class).putExtra(KeepAliveService.EXTRA_PLAYING, true);
        try {
            ContextCompat.startForegroundService(context, i);
            foreground = true;
        } catch (Exception e) {
            // Android 12+ pode recusar com o app em segundo plano; enquanto o carro está
            // conectado ao serviço de navegação, o processo continua vivo mesmo assim.
            Log.w(TAG, "Serviço em primeiro plano recusado: " + e.getMessage());
        }
    }

    private void stopForeground() {
        main.removeCallbacks(stopForegroundLater);
        if (!foreground) return;
        foreground = false;
        if (!wantPlaying) abandonFocus();
        context.stopService(new Intent(context, KeepAliveService.class));
    }
}
