import 'package:flick/data/repositories/song_repository.dart';
import 'package:flick/services/music_folder_service.dart';
import 'package:flick/services/permission_service.dart';

enum LibraryAccessRecovery { androidPermissions, folderPicker }

/// Checks access to existing Android library tracks on foreground startup.
class StartupLibraryAccess {
  StartupLibraryAccess({
    PermissionService? permissions,
    MusicFolderService? folders,
    SongRepository? songs,
  }) : _permissions = permissions ?? PermissionService(),
       _folders = folders ?? MusicFolderService(),
       _songs = songs ?? SongRepository();

  final PermissionService _permissions;
  final MusicFolderService _folders;
  final SongRepository _songs;

  Future<LibraryAccessRecovery?> requestIfNeeded({
    required Future<void> Function() waitUntilForeground,
  }) async {
    final folders = await _folders.getSavedFolders();
    if (folders.isEmpty) return null;

    final canReadSharedFiles =
        await _permissions.hasAllFilesAccess() ||
        await _permissions.hasStoragePermission();
    final readableTrees = (await _permissions.getPersistedFolderUris()).toSet();
    var needsAudioPermission = false;
    var needsFolderGrant = false;
    for (final folder in folders) {
      if (folder.isRemovable == true &&
          !(await _folders.resolveStorageInfo(folder.uri)).isMounted) {
        continue;
      }
      if (!canReadSharedFiles && !needsAudioPermission) {
        needsAudioPermission =
            await _songs.hasSongsWithPathPrefix(folder.uri, '/') ||
            await _songs.hasSongsWithPathPrefix(folder.uri, 'file:');
      }
      if (!needsFolderGrant &&
          folder.uri.startsWith('content://') &&
          !readableTrees.contains(folder.uri)) {
        needsFolderGrant = await _songs.hasSongsWithPathPrefix(
          folder.uri,
          'content://',
        );
      }
    }

    if (needsAudioPermission) {
      await waitUntilForeground();
      if (!await _permissions.requestStoragePermission()) {
        return LibraryAccessRecovery.androidPermissions;
      }
    }
    return needsFolderGrant ? LibraryAccessRecovery.folderPicker : null;
  }
}
