/**
 * Android Auto ↔ player do WebView (`MediaPlugin.java`, `CarBridge`).
 *
 * A navegação do carro (Recentes, Playlists, Álbuns, Artistas) é lida direto do banco pelo
 * Java (`CarLibrary.java`), inclusive com o app fechado. Com o app aberto, quem toca é este
 * player, então o Java manda para cá:
 * - `carPlayQueue`: tocar estas faixas a partir de um índice (também usado para continuar
 *   aqui o que o player nativo tocava antes de o app abrir);
 * - `carSearch`: buscar no catálogo externo (resultado em `Media.carSearchResult`);
 * - `carPlayCatalog`: importar uma faixa do catálogo e tocar (espera baixar, se preciso).
 */
import { api, send, type AppSettings, type SearchResponse, type TrackDto } from '../../frontend/src/api';
import { useLibrary } from '../../frontend/src/library';
import { playerControls } from '../../frontend/src/player/usePlayerAudio';
import { Media } from './native';

/** Quanto esperar o download de uma faixa do catálogo antes de desistir. */
export const CATALOG_WAIT_MS = 3 * 60 * 1000;
const FETCH_BATCH = 20;
const CATALOG_RESULTS = 15;

/** Garante que a biblioteca da interface conhece as faixas (o player usa título/capa/status dela). */
export async function loadTracks(ids: string[]): Promise<void> {
  const missing = ids.filter((id) => !useLibrary.getState().byId[id]);
  const found: TrackDto[] = [];
  for (let i = 0; i < missing.length; i += FETCH_BATCH) {
    const batch = await Promise.all(
      missing.slice(i, i + FETCH_BATCH).map((id) => api<TrackDto>(`/tracks/${id}`).catch(() => null)),
    );
    for (const t of batch) if (t) found.push(t);
  }
  useLibrary.getState().upsert(found);
}

export interface CarQueue {
  ids: string[];
  index: number;
  shuffle: boolean;
  positionMs: number;
  play: boolean;
}

export async function playCarQueue(q: CarQueue): Promise<void> {
  if (q.ids.length === 0) return;
  await loadTracks(q.ids);
  if (q.shuffle) await playerControls.playPlaylist(q.ids, true);
  else await playerControls.playFrom(q.ids, Math.max(0, Math.min(q.index, q.ids.length - 1)));
  if (q.positionMs > 0) playerControls.seek(q.positionMs / 1000);
  if (!q.play) playerControls.pause();
}

/** Espera a faixa ficar pronta (o `useLibrary` acompanha o download sozinho). */
export function waitUntilReady(id: string, timeoutMs = CATALOG_WAIT_MS): Promise<boolean> {
  const status = () => useLibrary.getState().byId[id]?.downloadStatus;
  if (status() === 'ready') return Promise.resolve(true);
  return new Promise((resolve) => {
    let unsubscribe = () => {};
    const timer = setTimeout(() => finish(false), timeoutMs);
    function finish(ok: boolean) {
      clearTimeout(timer);
      unsubscribe();
      resolve(ok);
    }
    unsubscribe = useLibrary.subscribe(() => {
      const s = status();
      if (s === 'ready') finish(true);
      else if (s === 'failed') finish(false);
    });
  });
}

export async function playCatalogTrack(catalogId: string): Promise<void> {
  const { track } = await send<{ track: TrackDto }>('POST', '/import/track', { catalogId });
  useLibrary.getState().upsert([track]);
  const id = String(track.id);
  if (track.downloadStatus !== 'ready') {
    const settings = await api<AppSettings>('/settings').catch(() => null);
    // Sem "tocar sem baixar", só dá para tocar depois do download.
    if (!settings?.streamWithoutDownload && !(await waitUntilReady(id))) return;
  }
  await playerControls.playFrom([id], 0);
}

export async function searchCatalog(query: string) {
  const r = await api<SearchResponse>(`/search?q=${encodeURIComponent(query)}&scope=external`);
  return r.external.tracks.slice(0, CATALOG_RESULTS).map((t) => ({
    catalogId: t.catalogId,
    title: t.title,
    artistName: t.artistName,
    albumTitle: t.albumTitle,
  }));
}

export function startCarBridge(): void {
  void Media.addListener('carPlayQueue', (q) => {
    playCarQueue(q).catch((err) => console.error('Carro: falha ao tocar a fila', err));
  });

  void Media.addListener('carPlayCatalog', ({ catalogId }) => {
    playCatalogTrack(catalogId).catch((err) => console.error('Carro: falha ao tocar do catálogo', err));
  });

  void Media.addListener('carSearch', ({ requestId, query }) => {
    searchCatalog(query)
      .catch(() => [])
      .then((tracks) => Media.carSearchResult({ requestId, tracks }))
      .catch((err) => console.error('Carro: falha ao responder a busca', err));
  });
}
