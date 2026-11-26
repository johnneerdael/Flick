import 'package:flick/data/repositories/song_repository.dart';
import 'package:flick/services/music_folder_service.dart';
import 'package:flick/services/permission_service.dart';
import 'package:flick/services/startup_library_access.dart';
import 'package:flutter_test/flutter_test.dart';

const music = 'content://provider/tree/primary%3AMusic';
const albums = 'content://provider/tree/primary%3AAlbums';

class _Permissions extends PermissionService {
  bool allFiles = false;
  bool media = false;
  bool grant = true;
  List<String> readableTrees = [];
  int requests = 0;

  @override
  Future<bool> hasAllFilesAccess() async => allFiles;

  @override
  Future<bool> hasStoragePermission() async => media;

  @override
  Future<List<String>> getPersistedFolderUris() async => readableTrees;

  @override
  Future<bool> requestStoragePermission({int? sdkInt}) async {
    requests++;
    return grant;
  }
}

class _Folders extends MusicFolderService {
  List<String> saved = [music];
  bool removable = false;

  @override
  Future<List<MusicFolder>> getSavedFolders() async => [
    for (final uri in saved)
      MusicFolder(
        uri: uri,
        displayName: 'Music',
        dateAdded: DateTime(2026),
        isRemovable: removable,
      ),
  ];

  @override
  Future<StorageVolumeInfo> resolveStorageInfo(String uri) async =>
      const StorageVolumeInfo(isRemovable: true, state: 'unmounted');
}

class _Songs implements SongRepository {
  final Map<String, List<String>> paths = {};

  @override
  Future<bool> hasSongsWithPathPrefix(String folderUri, String prefix) async =>
      paths[folderUri]?.any((path) => path.startsWith(prefix)) ?? false;

  @override
  dynamic noSuchMethod(Invocation invocation) => super.noSuchMethod(invocation);
}

void main() {
  late _Permissions permissions;
  late _Folders folders;
  late _Songs songs;
  late StartupLibraryAccess startup;

  setUp(() {
    permissions = _Permissions();
    folders = _Folders();
    songs = _Songs();
    startup = StartupLibraryAccess(
      permissions: permissions,
      folders: folders,
      songs: songs,
    );
  });

  Future<LibraryAccessRecovery?> check() =>
      startup.requestIfNeeded(waitUntilForeground: () async {});

  test(
    'requests media access once for cached file paths after foreground',
    () async {
      folders.saved = [music, albums];
      songs.paths[music] = ['/storage/emulated/0/Music/track.flac'];
      songs.paths[albums] = ['file:///storage/emulated/0/Albums/track.flac'];

      var reachedForeground = false;
      final recovery = await startup.requestIfNeeded(
        waitUntilForeground: () async {
          expect(permissions.requests, 0);
          reachedForeground = true;
        },
      );
      expect(reachedForeground, isTrue);
      expect(permissions.requests, 1);
      expect(recovery, isNull);
    },
  );

  test('offers Android settings when the media request is denied', () async {
    songs.paths[music] = ['file:///storage/emulated/0/Music/track.flac'];
    permissions.grant = false;

    expect(await check(), LibraryAccessRecovery.androidPermissions);
    expect(permissions.requests, 1);
  });

  test('skips the prompt for a new or already readable library', () async {
    expect(await check(), isNull);
    songs.paths[music] = ['content://provider/tree/primary%3AMusic/document/x'];
    permissions.readableTrees = [music];
    expect(await check(), isNull);
    songs.paths[music] = ['/storage/emulated/0/Music/track.flac'];
    permissions.allFiles = true;
    expect(await check(), isNull);
    permissions.allFiles = false;
    permissions.media = true;
    expect(await check(), isNull);
    expect(permissions.requests, 0);
  });

  test(
    'offers folder reselection when a saved SAF track lost its grant',
    () async {
      songs.paths[music] = [
        'content://provider/tree/primary%3AMusic/document/x',
      ];
      permissions.media = true;

      expect(await check(), LibraryAccessRecovery.folderPicker);
      expect(permissions.requests, 0);
    },
  );

  test(
    'skips missing permissions for an unmounted removable library',
    () async {
      folders.removable = true;
      songs.paths[music] = ['/storage/removable/Music/track.flac'];

      expect(await check(), isNull);
      expect(permissions.requests, 0);
    },
  );
}
