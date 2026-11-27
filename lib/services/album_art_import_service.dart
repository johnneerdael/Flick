import 'dart:async';
import 'dart:convert';
import 'dart:ui' as ui;
import 'dart:io';
import 'dart:typed_data';

import 'package:crypto/crypto.dart';
import 'package:http/http.dart' as http;
import 'package:path_provider/path_provider.dart';

import '../core/utils/string_sort_utils.dart';
import '../data/repositories/song_repository.dart';
import '../models/song.dart';
import 'album_art_service.dart';
import 'player_service.dart';
import 'sources/network_source_service.dart';
import 'package:flick/l10n/english_l10n.dart';

class AlbumArtImportException implements Exception {
  const AlbumArtImportException(this.message);

  final String message;

  @override
  String toString() => message;
}

class AlbumArtCandidate {
  const AlbumArtCandidate({
    required this.previewUrl,
    required this.imageUrl,
    required this.title,
    required this.artist,
    required this.sourceLabel,
    this.releaseDate,
  });

  final String previewUrl;
  final String imageUrl;
  final String title;
  final String artist;
  final String sourceLabel;
  final String? releaseDate;
}

enum AlbumArtScope { wholeAlbum, songOnly }

class AlbumArtUpdateResult {
  const AlbumArtUpdateResult({
    required this.albumName,
    required this.albumArtist,
    required this.albumArtPath,
    required this.filePaths,
  });

  final String albumName;
  final String albumArtist;
  final String? albumArtPath;
  final List<String> filePaths;
}

class AlbumArtImportService {
  AlbumArtImportService._({SongRepository? songRepository, http.Client? client})
    : _providedSongRepository = songRepository,
      _client = client ?? http.Client();

  static final AlbumArtImportService instance = AlbumArtImportService._();
  static const String _customArtworkDirectoryName = 'album_art_overrides';
  static const Duration _requestTimeout = Duration(seconds: 12);
  static const String _musicBrainzHost = 'musicbrainz.org';
  static const String _coverArtHost = 'coverartarchive.org';

  final SongRepository? _providedSongRepository;
  late final SongRepository _songRepository =
      _providedSongRepository ?? SongRepository();
  final http.Client _client;

  bool isCustomArtworkPath(String? path) {
    if (path == null || path.isEmpty) {
      return false;
    }

    final normalized = path.replaceAll('\\', '/');
    return normalized.contains('/$_customArtworkDirectoryName/');
  }

  Future<List<AlbumArtCandidate>> searchOnlineCandidates(
    Song song, {
    bool automatic = false,
  }) async {
    final album = _searchAlbumName(song);
    if (album == null) {
      return const [];
    }

    final artist = _searchArtistName(song);
    final candidates = <AlbumArtCandidate>[];
    Object? lastError;

    try {
      candidates.addAll(
        await _searchCoverArtArchive(
          entityType: 'release-group',
          titleField: 'releasegroup',
          album: album,
          artist: artist,
          automatic: automatic,
          year: song.year,
        ),
      );
    } catch (error) {
      lastError = error;
    }

    if (automatic || candidates.length < 8) {
      try {
        candidates.addAll(
          await _searchCoverArtArchive(
            entityType: 'release',
            titleField: 'release',
            album: album,
            artist: artist,
            automatic: automatic,
            year: song.year,
          ),
        );
      } catch (error) {
        lastError ??= error;
      }
    }

    if (automatic || candidates.length < 12) {
      try {
        candidates.addAll(
          await _searchITunes(album: album, artist: artist),
        );
      } catch (error) {
        lastError ??= error;
      }
    }

    if (automatic || candidates.length < 12) {
      try {
        candidates.addAll(
          await _searchDeezer(album: album, artist: artist),
        );
      } catch (error) {
        lastError ??= error;
      }
    }

    final deduped = automatic
        ? automaticCandidates(song, candidates)
        : _dedupeCandidates(candidates);
    if (deduped.isEmpty && lastError != null) {
      throw _asImportException(lastError);
    }

    return deduped;
  }

  // Shared by manual and automatic lookups. Reserve slots before awaiting so
  // concurrent searches cannot send bursts to either metadata provider.
  final Map<String, DateTime> _nextMetadataRequest = {};
  Future<void> _spaceMetadataRequest(String host) async {
    final interval = host == _musicBrainzHost
        ? const Duration(milliseconds: 1100)
        : host == 'itunes.apple.com'
        ? const Duration(milliseconds: 3100)
        : Duration.zero;
    if (interval == Duration.zero) return;
    final now = DateTime.now();
    final reserved = _nextMetadataRequest[host];
    final start = reserved != null && reserved.isAfter(now) ? reserved : now;
    _nextMetadataRequest[host] = start.add(interval);
    final delay = start.difference(now);
    if (delay > Duration.zero) await Future<void>.delayed(delay);
  }

  /// Conservative identity: retain accents, punctuation and non-Latin letters.
  static String normalizeArtworkIdentity(String value) =>
      value.trim().toLowerCase().replaceAll(RegExp(r'\s+'), ' ');

  static int _yearRank(String? date, int? year) =>
      year != null && int.tryParse((date ?? '').split('-').first) == year
      ? 0
      : 1;

  static List<AlbumArtCandidate> automaticCandidates(
    Song song,
    Iterable<AlbumArtCandidate> candidates,
  ) {
    final album = normalizeArtworkIdentity(song.album ?? '');
    final artist = normalizeArtworkIdentity(
      (song.albumArtist?.trim().isNotEmpty ?? false)
          ? song.albumArtist!
          : song.artist,
    );
    if (album.isEmpty || artist.isEmpty) return [];
    const sources = [
      'MusicBrainz release group',
      'MusicBrainz release',
      'iTunes',
      'Deezer',
    ];
    final eligible =
        candidates
            .where(
              (candidate) =>
                  normalizeArtworkIdentity(candidate.title) == album &&
                  normalizeArtworkIdentity(candidate.artist) == artist &&
                  Uri.tryParse(candidate.imageUrl)?.scheme == 'https',
            )
            .toList()
          ..sort((a, b) {
            final year = _yearRank(
              a.releaseDate,
              song.year,
            ).compareTo(_yearRank(b.releaseDate, song.year));
            if (year != 0) return year;
            int rank(String source) => sources.contains(source)
                ? sources.indexOf(source)
                : sources.length;
            final source = rank(a.sourceLabel).compareTo(rank(b.sourceLabel));
            return source != 0 ? source : a.imageUrl.compareTo(b.imageUrl);
          });
    final seen = <String>{};
    return eligible.where((candidate) => seen.add(candidate.imageUrl)).toList();
  }

  /// Separate content-addressed files cannot overwrite a user's manual cover.
  Future<String> downloadAutomaticArtwork(AlbumArtCandidate candidate) async {
    final request = http.Request('GET', Uri.parse(candidate.imageUrl))
      ..headers.addAll(_downloadHeaders());
    final response = await _client.send(request).timeout(_requestTimeout);
    const maxBytes = 20 * 1024 * 1024;
    if (response.statusCode != 200 ||
        (response.contentLength ?? 0) > maxBytes) {
      await response.stream.listen(null).cancel();
      throw AlbumArtImportException(
        response.statusCode != 200
            ? l10n.failedToDownloadAlbumArt(response.statusCode)
            : l10n.selectedFileIsNotASupported,
      );
    }
    final buffer = BytesBuilder(copy: false);
    await for (final chunk in response.stream.timeout(_requestTimeout)) {
      if (buffer.length + chunk.length > maxBytes) {
        throw AlbumArtImportException(l10n.selectedFileIsNotASupported);
      }
      buffer.add(chunk);
    }
    final bytes = buffer.takeBytes();
    if (!_looksLikeImage(bytes)) {
      throw AlbumArtImportException(l10n.selectedFileIsNotASupported);
    }
    final codec = await ui.instantiateImageCodec(bytes, targetWidth: 1000);
    try {
      final frame = await codec.getNextFrame();
      frame.image.dispose();
    } finally {
      codec.dispose();
    }
    return AlbumArtService.instance.cacheDownloadedArtwork(bytes);
  }

  Future<AlbumArtUpdateResult> applyOnlineCandidate({
    required Song song,
    required AlbumArtCandidate candidate,
  }) async {
    final response = await _client
        .get(Uri.parse(candidate.imageUrl), headers: _downloadHeaders())
        .timeout(_requestTimeout);
    if (response.statusCode < 200 || response.statusCode >= 300) {
      throw AlbumArtImportException(
        l10n.failedToDownloadAlbumArt(response.statusCode),
      );
    }

    return applyImageBytes(song: song, bytes: response.bodyBytes);
  }

  Future<AlbumArtUpdateResult> applyOnlineCandidateToSong({
    required Song song,
    required AlbumArtCandidate candidate,
  }) async {
    final response = await _client
        .get(Uri.parse(candidate.imageUrl), headers: _downloadHeaders())
        .timeout(_requestTimeout);
    if (response.statusCode < 200 || response.statusCode >= 300) {
      throw AlbumArtImportException(
        l10n.failedToDownloadAlbumArt(response.statusCode),
      );
    }

    return applyImageBytesToSong(song: song, bytes: response.bodyBytes);
  }

  Future<AlbumArtUpdateResult> applyImageBytes({
    required Song song,
    required Uint8List bytes,
  }) async {
    if (!_looksLikeImage(bytes)) {
      throw AlbumArtImportException(l10n.selectedFileIsNotASupported);
    }

    final group = await _resolveAlbumGroup(song);
    final filePaths = _albumFilePaths(group);
    final file = await _writeCustomArtwork(group.key, bytes);

    await _songRepository.updateAlbumArtPaths(filePaths, file.path);

    return AlbumArtUpdateResult(
      albumName: group.albumName,
      albumArtist: group.albumArtist,
      albumArtPath: file.path,
      filePaths: filePaths,
    );
  }

  Future<AlbumArtUpdateResult> removeCustomArtwork(Song song) async {
    final group = await _resolveAlbumGroup(song);
    final filePaths = _albumFilePaths(group);
    final customPaths = group.songs
        .map((entry) => entry.albumArt)
        .whereType<String>()
        .where(isCustomArtworkPath)
        .toSet();

    if (customPaths.isEmpty) {
      throw AlbumArtImportException(l10n.noCustomAlbumArtIsSet);
    }

    await _songRepository.updateAlbumArtPaths(filePaths, null);

    for (final path in customPaths) {
      try {
        final file = File(path);
        if (await file.exists()) {
          await file.delete();
        }
      } catch (_) {
        // Best effort cleanup.
      }
    }

    return AlbumArtUpdateResult(
      albumName: group.albumName,
      albumArtist: group.albumArtist,
      albumArtPath: null,
      filePaths: filePaths,
    );
  }

  Future<AlbumArtUpdateResult> applyImageBytesToSong({
    required Song song,
    required Uint8List bytes,
  }) async {
    if (!_looksLikeImage(bytes)) {
      throw AlbumArtImportException(l10n.selectedFileIsNotASupported);
    }

    final filePath = song.filePath;
    if (filePath == null || filePath.isEmpty) {
      throw AlbumArtImportException(l10n.songHasNoFilePath);
    }

    final file = await _writeCustomArtwork(filePath, bytes);
    await _songRepository.updateAlbumArtPath(filePath, file.path);

    return AlbumArtUpdateResult(
      albumName: song.album ?? song.title,
      albumArtist: song.albumArtist ?? song.artist,
      albumArtPath: file.path,
      filePaths: [filePath],
    );
  }

  Future<AlbumArtUpdateResult> removeCustomArtworkForSong(Song song) async {
    final filePath = song.filePath;
    if (filePath == null || filePath.isEmpty) {
      throw AlbumArtImportException(l10n.songHasNoFilePath);
    }

    final currentPath = song.albumArt;
    if (currentPath == null || !isCustomArtworkPath(currentPath)) {
      throw AlbumArtImportException(l10n.noCustomAlbumArtIsSet);
    }

    await _songRepository.updateAlbumArtPath(filePath, null);

    try {
      final file = File(currentPath);
      if (await file.exists()) {
        await file.delete();
      }
    } catch (_) {
      // Best effort cleanup.
    }

    return AlbumArtUpdateResult(
      albumName: song.album ?? song.title,
      albumArtist: song.albumArtist ?? song.artist,
      albumArtPath: null,
      filePaths: [filePath],
    );
  }

  /// Clears auto-resolved artwork for an album and re-derives it from the
  /// files themselves. Custom (user-picked) artwork is left untouched.
  Future<AlbumArtUpdateResult> resetArtwork(Song song) async {
    final group = await _resolveAlbumGroup(song);
    return _resetArtworkEntries(
      albumName: group.albumName,
      albumArtist: group.albumArtist,
      songs: group.songs,
    );
  }

  /// Like [resetArtwork] but only for [song].
  Future<AlbumArtUpdateResult> resetArtworkForSong(Song song) async {
    return _resetArtworkEntries(
      albumName: song.album ?? song.title,
      albumArtist: song.albumArtist ?? song.artist,
      songs: [song],
    );
  }

  Future<AlbumArtUpdateResult> _resetArtworkEntries({
    required String albumName,
    required String albumArtist,
    required List<Song> songs,
  }) async {
    final filePaths = <String>[];
    for (final entry in songs) {
      final path = entry.filePath;
      if (path == null || path.isEmpty) continue;
      if (isCustomArtworkPath(entry.albumArt)) continue;
      // Network songs keep their server cover marker in the DB; re-resolving
      // without it would drop the artwork instead of refreshing it.
      if (isSupportedNetworkProtocol(Uri.tryParse(path)?.scheme)) continue;
      filePaths.add(path);
    }

    if (filePaths.isEmpty) {
      throw AlbumArtImportException(l10n.noLibraryFilesForThisAlbum);
    }

    for (final path in filePaths) {
      await _songRepository.updateAlbumArtPath(path, null);
    }

    // Drop stale in-memory artwork first so a reset is visible immediately,
    // then re-derive per file (a successful resolve syncs on its own).
    PlayerService().syncAlbumArtPaths(filePaths: filePaths, albumArtPath: null);

    final artService = AlbumArtService.instance;
    for (final path in filePaths) {
      artService.evictFolderCoverCache(path);
      try {
        await artService.resolveArtworkPath(audioSourcePath: path);
      } catch (_) {
        // Best effort: CachedImageWidget retries on the next build.
      }
    }

    return AlbumArtUpdateResult(
      albumName: albumName,
      albumArtist: albumArtist,
      albumArtPath: null,
      filePaths: filePaths,
    );
  }

  Future<List<AlbumArtCandidate>> _searchCoverArtArchive({
    required String entityType,
    required String titleField,
    required String album,
    required String artist,
    bool automatic = false,
    int? year,
  }) async {
    final matches = await _searchMusicBrainz(
      entityType: entityType,
      titleField: titleField,
      album: album,
      artist: artist,
    );
    if (matches.isEmpty) {
      return const [];
    }

    final results = await Future.wait(
      (automatic
              ? (matches
                    .where(
                      (match) =>
                          normalizeArtworkIdentity(match.title) ==
                              normalizeArtworkIdentity(album) &&
                          normalizeArtworkIdentity(match.artist) ==
                              normalizeArtworkIdentity(artist),
                    )
                    .toList()
                  ..sort((a, b) {
                    final yearOrder = _yearRank(
                      a.releaseDate,
                      year,
                    ).compareTo(_yearRank(b.releaseDate, year));
                    return yearOrder != 0 ? yearOrder : a.id.compareTo(b.id);
                  }))
              : matches)
          .take(4)
          .map(
            (match) =>
                _loadCoverArtCandidatesForMatch(
                  match,
                  frontOnly: automatic,
                ).catchError((Object error) {
                  if (automatic) return <AlbumArtCandidate>[];
                  throw error;
                }),
          ),
    );

    return results.expand((items) => items).toList();
  }

  Future<List<_MusicBrainzMatch>> _searchMusicBrainz({
    required String entityType,
    required String titleField,
    required String album,
    required String artist,
  }) async {
    final query = _buildMusicBrainzQuery(
      titleField: titleField,
      album: album,
      artist: artist,
    );
    final response = await _getJson(
      Uri.https(_musicBrainzHost, '/ws/2/$entityType', {
        'query': query,
        'fmt': 'json',
        'limit': '8',
      }),
    );

    final listKey = entityType == 'release-group'
        ? 'release-groups'
        : 'releases';
    final entries = response[listKey];
    if (entries is! List) {
      return const [];
    }

    final matches = <_MusicBrainzMatch>[];
    for (final entry in entries) {
      if (entry is! Map<String, dynamic>) {
        continue;
      }

      final id = (entry['id'] as String?)?.trim();
      final title = (entry['title'] as String?)?.trim();
      if (id == null || id.isEmpty || title == null || title.isEmpty) {
        continue;
      }

      final resultArtist = _artistCreditLabel(entry['artist-credit']);
      final releaseDate =
          ((entry['first-release-date'] ?? entry['date']) as String?)?.trim();
      final primaryType = (entry['primary-type'] as String?)?.trim();
      final score = _matchScore(
        title: title,
        artist: resultArtist,
        album: album,
        expectedArtist: artist,
        primaryType: primaryType,
      );

      matches.add(
        _MusicBrainzMatch(
          id: id,
          entityType: entityType,
          title: title,
          artist: resultArtist,
          releaseDate: releaseDate,
          score: score,
        ),
      );
    }

    matches.sort((a, b) {
      final scoreCompare = b.score.compareTo(a.score);
      if (scoreCompare != 0) {
        return scoreCompare;
      }
      final titleOrder = compareCaseInsensitive(a.title, b.title);
      return titleOrder != 0 ? titleOrder : a.id.compareTo(b.id);
    });

    return matches;
  }

  Future<List<AlbumArtCandidate>> _searchITunes({
    required String album,
    required String artist,
  }) async {
    final query = '$album $artist';
    final response = await _getJson(
      Uri.https('itunes.apple.com', '/search', {
        'term': query,
        'entity': 'album',
        'limit': '10',
        'country': 'US',
      }),
    );

    final results = response['results'];
    if (results is! List || results.isEmpty) {
      return const [];
    }

    final candidates = <AlbumArtCandidate>[];
    for (final result in results.take(8)) {
      if (result is! Map<String, dynamic>) {
        continue;
      }

      final title = (result['collectionName'] as String?)?.trim();
      if (title == null || title.isEmpty) {
        continue;
      }

      final itunesArtist =
          (result['artistName'] as String?)?.trim() ?? '';
      final releaseDate = (result['releaseDate'] as String?)?.trim();
      final artworkUrl = (result['artworkUrl100'] as String?)?.trim();
      if (artworkUrl == null || artworkUrl.isEmpty) {
        continue;
      }

      final previewUrl = artworkUrl.replaceAll('100x100bb', '600x600bb');
      final imageUrl = artworkUrl.replaceAll('100x100bb', '3000x3000bb');

      candidates.add(
        AlbumArtCandidate(
          previewUrl: previewUrl,
          imageUrl: imageUrl,
          title: title,
          artist: itunesArtist,
          sourceLabel: 'iTunes',
          releaseDate: releaseDate,
        ),
      );
    }

    return candidates;
  }

  Future<List<AlbumArtCandidate>> _searchDeezer({
    required String album,
    required String artist,
  }) async {
    final query = 'album:"$album" artist:"$artist"';
    final response = await _getJson(
      Uri.https('api.deezer.com', '/search/album', {
        'q': query,
        'limit': '10',
      }),
    );

    final data = response['data'];
    if (data is! List || data.isEmpty) {
      return const [];
    }

    final candidates = <AlbumArtCandidate>[];
    for (final item in data.take(8)) {
      if (item is! Map<String, dynamic>) {
        continue;
      }

      final title = (item['title'] as String?)?.trim();
      if (title == null || title.isEmpty) {
        continue;
      }

      final deezerArtist = item['artist'] is Map<String, dynamic>
          ? ((item['artist'] as Map<String, dynamic>)['name'] as String?)
                  ?.trim() ??
              ''
          : '';
      final releaseDate = (item['release_date'] as String?)?.trim();
      final coverUrl = (item['cover_big'] as String?)?.trim();
      if (coverUrl == null || coverUrl.isEmpty) {
        continue;
      }

      candidates.add(
        AlbumArtCandidate(
          previewUrl: coverUrl,
          imageUrl: coverUrl,
          title: title,
          artist: deezerArtist,
          sourceLabel: 'Deezer',
          releaseDate: releaseDate,
        ),
      );
    }

    return candidates;
  }

  Future<List<AlbumArtCandidate>> _loadCoverArtCandidatesForMatch(
    _MusicBrainzMatch match, {
    bool frontOnly = false,
  }) async {
    try {
      final response = await _getJson(
        Uri.https(_coverArtHost, '/${match.entityType}/${match.id}'),
      );
      final images = response['images'];
      if (images is! List || images.isEmpty) {
        return const [];
      }

      final frontImages = images.whereType<Map<String, dynamic>>().where((
        image,
      ) {
        return image['front'] == true;
      }).toList();
      final preferredImages = frontOnly || frontImages.isNotEmpty
          ? frontImages
          : images.whereType<Map<String, dynamic>>().take(1).toList();

      final candidates = <AlbumArtCandidate>[];
      for (final image in preferredImages.take(2)) {
        final rawImageUrl = (image['image'] as String?)?.trim();
        final imageUrl = rawImageUrl?.replaceFirst(
          'http://coverartarchive.org/',
          'https://coverartarchive.org/',
        );
        if (imageUrl == null || imageUrl.isEmpty) {
          continue;
        }

        final thumbnails = image['thumbnails'];
        final previewUrl = thumbnails is Map<String, dynamic>
            ? ((thumbnails['500'] ?? thumbnails['250']) as String?)?.trim() ??
                  imageUrl
            : imageUrl;

        candidates.add(
          AlbumArtCandidate(
            previewUrl: previewUrl,
            imageUrl: imageUrl,
            title: match.title,
            artist: match.artist,
            sourceLabel: match.entityType == 'release-group'
                ? 'MusicBrainz release group'
                : 'MusicBrainz release',
            releaseDate: match.releaseDate,
          ),
        );
      }

      return candidates;
    } on _AlbumArtHttpException catch (error) {
      if (error.statusCode == 404) {
        return const [];
      }
      rethrow;
    }
  }

  Future<Map<String, dynamic>> _getJson(Uri uri) async {
    await _spaceMetadataRequest(uri.host);
    final response = await _client
        .get(uri, headers: _jsonHeaders())
        .timeout(_requestTimeout);
    if (response.statusCode < 200 || response.statusCode >= 300) {
      throw _AlbumArtHttpException(
        statusCode: response.statusCode,
        message: 'HTTP ${response.statusCode}',
      );
    }

    final decoded = jsonDecode(response.body);
    if (decoded is! Map<String, dynamic>) {
      throw AlbumArtImportException(l10n.artworkLookupInvalidData);
    }

    return decoded;
  }

  Map<String, String> _jsonHeaders() {
    return const {
      'Accept': 'application/json',
      'User-Agent': 'FlickPlayer (https://github.com/johnneerdael/Flick)',
    };
  }

  Map<String, String> _downloadHeaders() {
    return const {
      'User-Agent': 'FlickPlayer (https://github.com/johnneerdael/Flick)',
    };
  }

  Future<AlbumGroup> _resolveAlbumGroup(Song song) async {
    final group = await _songRepository.getAlbumGroupForSong(song);
    if (group == null || group.songs.isEmpty) {
      throw AlbumArtImportException(l10n.couldNotResolveTheAlbum);
    }
    return group;
  }

  List<String> _albumFilePaths(AlbumGroup group) {
    final filePaths = group.songs
        .map((entry) => entry.filePath)
        .whereType<String>()
        .where((path) => path.isNotEmpty)
        .toSet()
        .toList();
    if (filePaths.isEmpty) {
      throw AlbumArtImportException(l10n.noLibraryFilesForThisAlbum);
    }
    return filePaths;
  }

  Future<File> _writeCustomArtwork(String albumKey, Uint8List bytes) async {
    final documentsDirectory = await getApplicationDocumentsDirectory();
    final artworkDirectory = Directory(
      '${documentsDirectory.path}${Platform.pathSeparator}$_customArtworkDirectoryName',
    );
    if (!await artworkDirectory.exists()) {
      await artworkDirectory.create(recursive: true);
    }

    final digest = md5.convert(utf8.encode(albumKey)).toString();
    final file = File(
      '${artworkDirectory.path}${Platform.pathSeparator}$digest.cover',
    );
    await file.writeAsBytes(bytes, flush: true);
    return file;
  }

  List<AlbumArtCandidate> _dedupeCandidates(
    List<AlbumArtCandidate> candidates,
  ) {
    final deduped = <String, AlbumArtCandidate>{};
    for (final candidate in candidates) {
      deduped.putIfAbsent(candidate.imageUrl, () => candidate);
    }
    return deduped.values.take(12).toList(growable: false);
  }

  String _buildMusicBrainzQuery({
    required String titleField,
    required String album,
    required String artist,
  }) {
    final clauses = <String>['$titleField:"${_escapeQueryValue(album)}"'];
    if (artist.trim().isNotEmpty) {
      clauses.add('artist:"${_escapeQueryValue(artist)}"');
    }
    return clauses.join(' AND ');
  }

  String _escapeQueryValue(String value) {
    return value.replaceAll('"', ' ').trim();
  }

  String _artistCreditLabel(Object? artistCredit) {
    if (artistCredit is! List) {
      return '';
    }

    final parts = <String>[];
    for (final item in artistCredit) {
      if (item is! Map<String, dynamic>) {
        continue;
      }

      final name = (item['name'] as String?)?.trim();
      if (name != null && name.isNotEmpty) {
        parts.add(name);
      }

      final joinPhrase = item['joinphrase'] as String?;
      if (joinPhrase != null && joinPhrase.isNotEmpty) {
        parts.add(joinPhrase);
      }
    }

    return parts.join();
  }

  int _matchScore({
    required String title,
    required String artist,
    required String album,
    required String expectedArtist,
    String? primaryType,
  }) {
    final normalizedTitle = _normalize(title);
    final normalizedAlbum = _normalize(album);
    final normalizedArtist = _normalize(artist);
    final normalizedExpectedArtist = _normalize(expectedArtist);

    var score = 0;
    if (normalizedTitle == normalizedAlbum) {
      score += 120;
    } else if (normalizedTitle.contains(normalizedAlbum) ||
        normalizedAlbum.contains(normalizedTitle)) {
      score += 70;
    }

    if (normalizedExpectedArtist.isNotEmpty) {
      if (normalizedArtist == normalizedExpectedArtist) {
        score += 60;
      } else if (normalizedArtist.contains(normalizedExpectedArtist) ||
          normalizedExpectedArtist.contains(normalizedArtist)) {
        score += 30;
      }
    }

    if ((primaryType ?? '').toLowerCase() == 'album') {
      score += 10;
    }

    return score;
  }

  String _normalize(String value) {
    return value.toLowerCase().replaceAll(RegExp(r'[^a-z0-9]+'), ' ').trim();
  }

  String? _searchAlbumName(Song song) {
    final album = song.album?.trim();
    if (album == null || album.isEmpty) {
      return null;
    }
    return album;
  }

  String _searchArtistName(Song song) {
    final albumArtist = song.albumArtist?.trim();
    if (albumArtist != null && albumArtist.isNotEmpty) {
      return albumArtist;
    }
    return song.artist.trim();
  }

  bool _looksLikeImage(Uint8List bytes) {
    if (bytes.isEmpty) {
      return false;
    }

    if (bytes.length >= 8 &&
        bytes[0] == 0x89 &&
        bytes[1] == 0x50 &&
        bytes[2] == 0x4E &&
        bytes[3] == 0x47) {
      return true;
    }

    if (bytes.length >= 3 &&
        bytes[0] == 0xFF &&
        bytes[1] == 0xD8 &&
        bytes[2] == 0xFF) {
      return true;
    }

    if (bytes.length >= 12 &&
        bytes[0] == 0x52 &&
        bytes[1] == 0x49 &&
        bytes[2] == 0x46 &&
        bytes[3] == 0x46 &&
        bytes[8] == 0x57 &&
        bytes[9] == 0x45 &&
        bytes[10] == 0x42 &&
        bytes[11] == 0x50) {
      return true;
    }

    if (bytes.length >= 6 &&
        bytes[0] == 0x47 &&
        bytes[1] == 0x49 &&
        bytes[2] == 0x46) {
      return true;
    }

    if (bytes.length >= 2 && bytes[0] == 0x42 && bytes[1] == 0x4D) {
      return true;
    }

    return false;
  }

  AlbumArtImportException _asImportException(Object error) {
    if (error is AlbumArtImportException) {
      return error;
    }
    if (error is _AlbumArtHttpException) {
      if (error.statusCode == 503) {
        return AlbumArtImportException(
          l10n.artworkLookupTemporarilyUnavailable,
        );
      }
      return AlbumArtImportException(
        l10n.artworkLookupFailed(error.statusCode),
      );
    }
    return AlbumArtImportException(l10n.failedToSearchAlbumArtOnline);
  }
}

class _MusicBrainzMatch {
  const _MusicBrainzMatch({
    required this.id,
    required this.entityType,
    required this.title,
    required this.artist,
    required this.releaseDate,
    required this.score,
  });

  final String id;
  final String entityType;
  final String title;
  final String artist;
  final String? releaseDate;
  final int score;
}

class _AlbumArtHttpException implements Exception {
  const _AlbumArtHttpException({
    required this.statusCode,
    required this.message,
  });

  final int statusCode;
  final String message;

  @override
  String toString() => message;
}
