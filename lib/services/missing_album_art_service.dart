import 'dart:io';

import 'package:flutter/foundation.dart';

import '../data/repositories/song_repository.dart';
import '../l10n/l10n.dart';
import '../models/song.dart';
import 'album_art_import_service.dart';
import 'album_art_service.dart';
import 'player_service.dart';

class MissingAlbumArtProgress {
  const MissingAlbumArtProgress({
    this.running = false,
    this.completed = 0,
    this.total = 0,
    this.filled = 0,
    this.unmatched = 0,
    this.failed = 0,
    this.finished = false,
  });

  final bool running;
  final int completed;
  final int total;
  final int filled;
  final int unmatched;
  final int failed;
  final bool finished;
}

/// One route-independent, sequential job. Never writes tags or replaces covers.
class MissingAlbumArtService {
  MissingAlbumArtService({
    Future<List<Song>> Function()? loadSongs,
    Future<bool> Function(String?)? isUsable,
    Future<String?> Function(String)? probeLocal,
    Future<List<AlbumArtCandidate>> Function(Song)? search,
    Future<String> Function(AlbumArtCandidate)? download,
    Future<List<Song>> Function(List<Song>, String)? fill,
    void Function(List<Song>, String)? sync,
  }) : _loadSongs = loadSongs,
       _isUsable = isUsable ?? isUsableArtwork,
       _probeLocal =
           probeLocal ?? AlbumArtService.instance.probeLocalArtworkPath,
       _search = search,
       _download = download,
       _fill = fill,
       _sync = sync;

  static final instance = MissingAlbumArtService();
  final progress = ValueNotifier(const MissingAlbumArtProgress());
  late final SongRepository _repository = SongRepository();
  final Future<List<Song>> Function()? _loadSongs;
  final Future<bool> Function(String?) _isUsable;
  final Future<String?> Function(String) _probeLocal;
  final Future<List<AlbumArtCandidate>> Function(Song)? _search;
  final Future<String> Function(AlbumArtCandidate)? _download;
  final Future<List<Song>> Function(List<Song>, String)? _fill;
  final void Function(List<Song>, String)? _sync;
  Future<void>? _job;

  // Repeated taps/runs do not repeat a failed or unmatched online lookup.
  final Map<String, ({DateTime time, List<AlbumArtCandidate>? candidates})>
  _lookups = {};

  Future<void> start() => _job ??= _run().whenComplete(() => _job = null);

  static String _normalize(String value) =>
      AlbumArtImportService.normalizeArtworkIdentity(value);

  static bool _known(String? value) =>
      value != null &&
      !{
        '',
        'unknown',
        'unknown album',
        'unknown artist',
        '<unknown>',
      }.contains(_normalize(value));

  /// Album artist is authoritative when explicitly tagged, including compilations.
  static List<List<Song>> albumGroups(Iterable<Song> songs) {
    final groups = <String, List<Song>>{};
    final seen = <String>{};
    for (final song in songs) {
      final scheme = Uri.tryParse(song.filePath ?? '')?.scheme ?? '';
      if (song.isExternal ||
          song.sourceType != null ||
          song.filePath == null ||
          song.filePath!.isEmpty ||
          !{'', 'file', 'content'}.contains(scheme) ||
          !seen.add(song.id))
        continue;
      final artist = (song.albumArtist?.trim().isNotEmpty ?? false)
          ? song.albumArtist!
          : song.artist;
      if (!_known(song.album) || !_known(artist)) continue;
      // Length-prefixing avoids delimiter collisions without losing Unicode.
      final album = _normalize(song.album!);
      final normalizedArtist = _normalize(artist);
      final key = '${album.length}:$album$normalizedArtist';
      groups.putIfAbsent(key, () => []).add(song);
    }
    final keys = groups.keys.toList()..sort();
    return keys
        .map((key) => groups[key]!..sort((a, b) => a.id.compareTo(b.id)))
        .toList();
  }

  static Future<bool> isUsableArtwork(String? path) async {
    if (path == null || path.trim().isEmpty) return false;
    final uri = Uri.tryParse(path);
    if (uri?.scheme == 'http' || uri?.scheme == 'https')
      return uri!.host.isNotEmpty;
    try {
      final file = uri?.scheme == 'file' ? File.fromUri(uri!) : File(path);
      return await file.exists() && await file.length() > 0;
    } catch (_) {
      return false;
    }
  }

  Future<int> _apply(List<Song> songs, String path) async {
    if (songs.isEmpty) return 0;
    final changed =
        await (_fill?.call(songs, path) ??
            _repository.fillMissingAlbumArt(songs, path));
    if (changed.isNotEmpty) {
      if (_sync != null) {
        _sync(changed, path);
      } else {
        PlayerService().syncAlbumArtPaths(
          filePaths: changed.map((song) => song.filePath!).toSet(),
          songIds: changed.map((song) => song.id).toSet(),
          albumArtPath: path,
        );
      }
    }
    return changed.length;
  }

  Future<List<AlbumArtCandidate>> _candidates(Song song) async {
    final key =
        '${_normalize(song.album!)}\u0000${_normalize(song.albumArtist?.trim().isNotEmpty == true ? song.albumArtist! : song.artist)}\u0000${song.year}';
    final cached = _lookups[key];
    final lifetime = cached?.candidates == null
        ? const Duration(seconds: 30)
        : const Duration(minutes: 5);
    if (cached != null && DateTime.now().difference(cached.time) < lifetime) {
      if (cached.candidates == null) {
        throw AlbumArtImportException(l10n.artworkLookupTemporarilyUnavailable);
      }
      return cached.candidates!;
    }
    // Bound memory for a long-running application without persisting negative results.
    if (_lookups.length >= 1000) _lookups.remove(_lookups.keys.first);
    try {
      final candidates =
          await (_search?.call(song) ??
              AlbumArtImportService.instance.searchOnlineCandidates(
                song,
                automatic: true,
              ));
      final eligible = AlbumArtImportService.automaticCandidates(
        song,
        candidates,
      );
      _lookups[key] = (time: DateTime.now(), candidates: eligible);
      return eligible;
    } catch (_) {
      _lookups[key] = (time: DateTime.now(), candidates: null);
      rethrow;
    }
  }

  Future<void> _run() async {
    var completed = 0, total = 0, filled = 0, unmatched = 0, failed = 0;
    void publish({bool finished = false}) {
      progress.value = MissingAlbumArtProgress(
        running: !finished,
        completed: completed,
        total: total,
        filled: filled,
        unmatched: unmatched,
        failed: failed,
        finished: finished,
      );
    }

    publish();
    try {
      final groups = albumGroups(
        await (_loadSongs?.call() ?? _repository.getAllSongs()),
      );
      // Only include groups with genuinely missing pointers in the progress total.
      final pending = <List<Song>>[];
      for (final group in groups) {
        for (final song in group) {
          if (!await _isUsable(song.albumArt)) {
            pending.add(group);
            break;
          }
        }
      }
      total = pending.length;
      publish();
      for (final group in pending) {
        try {
          final missing = <Song>[];
          String? reusable;
          for (final song in group) {
            if (await _isUsable(song.albumArt)) {
              reusable ??= song.albumArt;
            } else {
              missing.add(song);
            }
          }
          // Read-only probes preserve each track's own embedded/folder cover.
          final unresolved = <Song>[];
          final probed = <String, String?>{};
          for (final song in missing) {
            final source = song.filePath!;
            if (!probed.containsKey(source)) {
              try {
                probed[source] = await _probeLocal(source);
              } catch (_) {
                probed[source] = null;
              }
            }
            final local = probed[source];
            if (local != null && await _isUsable(local)) {
              reusable ??= local;
              filled += await _apply([song], local);
            } else {
              unresolved.add(song);
            }
          }
          if (unresolved.isNotEmpty) {
            if (reusable == null) {
              final years =
                  group
                      .map((song) => song.year)
                      .whereType<int>()
                      .toSet()
                      .toList()
                    ..sort();
              // Conflicting tags use the earliest known year deterministically.
              final representative = years.isEmpty
                  ? group.first
                  : group.first.copyWith(year: years.first);
              final candidates = await _candidates(representative);
              if (candidates.isEmpty) {
                unmatched++;
              } else {
                for (final candidate in candidates.take(4)) {
                  try {
                    reusable =
                        await (_download?.call(candidate) ??
                            AlbumArtImportService.instance
                                .downloadAutomaticArtwork(candidate));
                    break;
                  } catch (_) {
                    // A dead image must not prevent a compatible fallback.
                  }
                }
                if (reusable == null) failed++;
              }
            }
            if (reusable != null) filled += await _apply(unresolved, reusable);
          }
        } catch (_) {
          failed++;
        }
        completed++;
        publish();
      }
    } catch (_) {
      failed++;
    } finally {
      publish(finished: true);
    }
  }
}
