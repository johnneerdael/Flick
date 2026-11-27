import 'dart:async';

import 'package:flick/models/song.dart';
import 'package:flick/services/album_art_import_service.dart';
import 'package:flick/services/missing_album_art_service.dart';
import 'package:flutter_test/flutter_test.dart';

Song song(
  String id, {
  String album = 'Album',
  String artist = 'Artist',
  String? albumArtist,
  String? cover,
  int? year,
  String? path,
  String? sourceType,
  bool external = false,
}) => Song(
  id: id,
  title: 'Track $id',
  artist: artist,
  album: album,
  albumArtist: albumArtist,
  albumArt: cover,
  year: year,
  filePath: path ?? '/music/$id.flac',
  sourceType: sourceType,
  isExternal: external,
  duration: const Duration(seconds: 1),
  fileType: 'FLAC',
);

AlbumArtCandidate candidate(
  String url, {
  String title = 'Album',
  String artist = 'Artist',
  String source = 'iTunes',
  String? date,
}) => AlbumArtCandidate(
  previewUrl: url,
  imageUrl: url,
  title: title,
  artist: artist,
  sourceLabel: source,
  releaseDate: date,
);

void main() {
  group('automatic candidate selection', () {
    test('requires exact Unicode-preserving album and artist identities', () {
      final match = candidate(
        'https://example.org/right',
        title: '  ÁLBUM  東京 ',
        artist: ' A  B ',
      );
      final result = AlbumArtImportService.automaticCandidates(
        song('1', album: 'Álbum 東京', artist: 'a b'),
        [
          candidate(
            'https://example.org/other',
            title: 'Álbum 東京',
            artist: 'Other',
          ),
          candidate(
            'https://example.org/punctuation',
            title: 'Album 東京',
            artist: 'A B',
          ),
          candidate(
            'https://example.org/edition',
            title: 'Álbum 東京 Deluxe',
            artist: 'A B',
          ),
          match,
        ],
      );
      expect(result, [match]);
    });

    test('year precedes source and ties have stable URL ordering', () {
      final choices = [
        candidate(
          'https://example.org/z',
          source: 'MusicBrainz release group',
          date: '2000',
        ),
        candidate('https://example.org/b', date: '2001-04-01'),
        candidate('https://example.org/a', date: '2001'),
        candidate('https://example.org/a', date: '2001'),
        candidate('http://example.org/insecure', date: '2001'),
      ];
      List<String> order(Iterable<AlbumArtCandidate> input) =>
          AlbumArtImportService.automaticCandidates(
            song('1', year: 2001),
            input,
          ).map((c) => c.imageUrl).toList();
      expect(order(choices), [
        'https://example.org/a',
        'https://example.org/b',
        'https://example.org/z',
      ]);
      expect(order(choices.reversed), order(choices));
    });
  });

  test(
    'groups by explicit album artist, excludes network/external/unknown, deduplicates IDs',
    () {
      final groups = MissingAlbumArtService.albumGroups([
        song('1', artist: 'Track Artist', albumArtist: 'Album Artist'),
        song('2', artist: 'Other Track Artist', albumArtist: 'Album Artist'),
        song('3', artist: 'Different Album Artist'),
        song('3', artist: 'Different Album Artist'),
        song('4', sourceType: 'webdav'),
        song('5', external: true),
        song('6', album: 'Unknown Album'),
        song('7', path: 'https://example.org/song.flac'),
      ]);
      expect(groups.map((g) => g.map((s) => s.id).toList()), [
        ['1', '2'],
        ['3'],
      ]);
    },
  );

  test(
    'keeps existing art, preserves own embedded art, reuses sibling cover without lookup',
    () async {
      final fills = <String, String>{};
      var searched = false;
      final service = MissingAlbumArtService(
        loadSongs: () async => [
          song('1', cover: 'manual'),
          song('2'),
          song('3'),
        ],
        isUsable: (path) async => {'manual', 'embedded'}.contains(path),
        probeLocal: (path) async => path.contains('/2.') ? 'embedded' : null,
        search: (_) async {
          searched = true;
          return [];
        },
        fill: (songs, path) async {
          for (final song in songs) {
            fills[song.id] = path;
          }
          return songs;
        },
        sync: (_, _) {},
      );
      await service.start();
      expect(fills, {'2': 'embedded', '3': 'manual'});
      expect(searched, isFalse);
      expect(service.progress.value.filled, 2);
    },
  );

  test(
    'same-title albums stay separate; failed image falls back; one job at a time',
    () async {
      final gate = Completer<List<Song>>();
      var loads = 0;
      final fills = <String, String>{};
      final attempted = <String>[];
      final service = MissingAlbumArtService(
        loadSongs: () {
          loads++;
          return gate.future;
        },
        isUsable: (_) async => false,
        probeLocal: (_) async => null,
        search: (track) async => [
          candidate(
            'https://example.org/${track.artist}/a',
            artist: track.artist,
          ),
          candidate(
            'https://example.org/${track.artist}/b',
            artist: track.artist,
          ),
        ],
        download: (choice) async {
          attempted.add(choice.imageUrl);
          if (choice.imageUrl.endsWith('/a')) throw StateError('bad image');
          return choice.imageUrl;
        },
        fill: (songs, path) async {
          for (final song in songs) {
            fills[song.id] = path;
          }
          return songs;
        },
        sync: (_, _) {},
      );
      final first = service.start();
      final second = service.start();
      gate.complete([song('1', artist: 'A'), song('2', artist: 'B')]);
      await Future.wait([first, second]);
      expect(loads, 1);
      expect(fills, {
        '1': 'https://example.org/A/b',
        '2': 'https://example.org/B/b',
      });
      expect(attempted.length, 4);
      expect(service.progress.value.failed, 0);
    },
  );

  test(
    'unmatched and failed albums are reported separately and lookup failures cool down',
    () async {
      var searches = 0;
      final service = MissingAlbumArtService(
        loadSongs: () async => [song('1', artist: 'A'), song('2', artist: 'B')],
        isUsable: (_) async => false,
        probeLocal: (_) async => null,
        search: (track) async {
          searches++;
          if (track.artist == 'B') throw StateError('offline');
          return [];
        },
        fill: (songs, _) async => songs,
        sync: (_, _) {},
      );
      await service.start();
      expect(service.progress.value.unmatched, 1);
      expect(service.progress.value.failed, 1);
      await service.start();
      expect(searches, 2);
      expect(service.progress.value.unmatched, 1);
      expect(service.progress.value.failed, 1);
    },
  );

  test('a lost compare-and-set race is not counted as a fill', () async {
    final service = MissingAlbumArtService(
      loadSongs: () async => [song('1', cover: 'stale')],
      isUsable: (_) async => false,
      probeLocal: (_) async => null,
      search: (_) async => [candidate('https://example.org/cover')],
      download: (_) async => 'downloaded',
      fill: (expected, _) async {
        expect(expected.single.id, '1');
        expect(expected.single.albumArt, 'stale');
        return [];
      },
      sync: (_, _) => fail('must not sync a rejected write'),
    );
    await service.start();
    expect(service.progress.value.filled, 0);
  });
}
