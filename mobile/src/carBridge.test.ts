import { beforeEach, describe, expect, it, vi } from 'vitest';

const player = vi.hoisted(() => ({
  playPlaylist: vi.fn(),
  playFrom: vi.fn(),
  seek: vi.fn(),
  pause: vi.fn(),
}));
const http = vi.hoisted(() => ({ api: vi.fn(), send: vi.fn() }));
const lib = vi.hoisted(() => {
  type T = { id: number; downloadStatus: string };
  let byId: Record<string, T> = {};
  const subs = new Set<() => void>();
  const state = {
    get byId() {
      return byId;
    },
    upsert(tracks: T[]) {
      byId = { ...byId };
      for (const t of tracks) byId[String(t.id)] = t;
      subs.forEach((fn) => fn());
    },
  };
  return {
    useLibrary: { getState: () => state, subscribe: (fn: () => void) => (subs.add(fn), () => subs.delete(fn)) },
    reset: () => {
      byId = {};
      subs.clear();
    },
  };
});

vi.mock('./native', () => ({ Media: { addListener: vi.fn(), carSearchResult: vi.fn() } }));
vi.mock('../../frontend/src/api', () => http);
vi.mock('../../frontend/src/library', () => ({ useLibrary: lib.useLibrary }));
vi.mock('../../frontend/src/player/usePlayerAudio', () => ({ playerControls: player }));

const { playCarQueue, playCatalogTrack, searchCatalog, waitUntilReady } = await import('./carBridge');

beforeEach(() => {
  vi.clearAllMocks();
  lib.reset();
});

describe('playCarQueue', () => {
  it('busca as faixas que a interface não conhece e toca a partir do índice', async () => {
    lib.useLibrary.getState().upsert([{ id: 1, downloadStatus: 'ready' }]);
    http.api.mockImplementation(async (path: string) => ({ id: Number(path.split('/')[2]), downloadStatus: 'ready' }));
    await playCarQueue({ ids: ['1', '2', '3'], index: 1, shuffle: false, positionMs: 0, play: true });
    expect(http.api).toHaveBeenCalledTimes(2);
    expect(lib.useLibrary.getState().byId['3']).toBeDefined();
    expect(player.playFrom).toHaveBeenCalledWith(['1', '2', '3'], 1);
    expect(player.seek).not.toHaveBeenCalled();
    expect(player.pause).not.toHaveBeenCalled();
  });

  it('aleatório, continuando de uma posição e pausado (passagem do player nativo)', async () => {
    http.api.mockResolvedValue({ id: 7, downloadStatus: 'ready' });
    await playCarQueue({ ids: ['7'], index: 0, shuffle: true, positionMs: 42_000, play: false });
    expect(player.playPlaylist).toHaveBeenCalledWith(['7'], true);
    expect(player.seek).toHaveBeenCalledWith(42);
    expect(player.pause).toHaveBeenCalled();
  });
});

describe('catálogo', () => {
  it('importa e toca na hora quando já está baixada', async () => {
    http.send.mockResolvedValue({ track: { id: 9, downloadStatus: 'ready' } });
    await playCatalogTrack('123');
    expect(http.send).toHaveBeenCalledWith('POST', '/import/track', { catalogId: '123' });
    expect(player.playFrom).toHaveBeenCalledWith(['9'], 0);
  });

  it('com "tocar sem baixar" ligado não espera o download', async () => {
    http.send.mockResolvedValue({ track: { id: 9, downloadStatus: 'pending' } });
    http.api.mockResolvedValue({ streamWithoutDownload: true });
    await playCatalogTrack('123');
    expect(player.playFrom).toHaveBeenCalledWith(['9'], 0);
  });

  it('espera o download terminar; falhou, não toca', async () => {
    lib.useLibrary.getState().upsert([{ id: 5, downloadStatus: 'pending' }]);
    const ok = waitUntilReady('5', 1000);
    lib.useLibrary.getState().upsert([{ id: 5, downloadStatus: 'ready' }]);
    expect(await ok).toBe(true);

    lib.useLibrary.getState().upsert([{ id: 6, downloadStatus: 'pending' }]);
    const failed = waitUntilReady('6', 1000);
    lib.useLibrary.getState().upsert([{ id: 6, downloadStatus: 'failed' }]);
    expect(await failed).toBe(false);
  });

  it('busca só faixas do catálogo, no formato do Java', async () => {
    http.api.mockResolvedValue({
      local: { tracks: [], albums: [], artists: [], playlists: [] },
      external: {
        tracks: [{ catalogId: '1', title: 'A', artistName: 'B', albumTitle: 'C', coverUrl: null, durationSeconds: 1 }],
        albums: [],
        artists: [],
      },
    });
    expect(await searchCatalog('tempo perdido')).toEqual([{ catalogId: '1', title: 'A', artistName: 'B', albumTitle: 'C' }]);
    expect(http.api).toHaveBeenCalledWith('/search?q=tempo%20perdido&scope=external');
  });
});
