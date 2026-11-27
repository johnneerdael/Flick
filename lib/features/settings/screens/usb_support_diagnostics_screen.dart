import 'dart:async';
import 'dart:convert';
import 'dart:io';
import 'dart:typed_data';

import 'package:file_picker/file_picker.dart';
import 'package:flutter/material.dart';
import 'package:path_provider/path_provider.dart';
import 'package:share_plus/share_plus.dart';
import 'package:flick/features/settings/widgets/settings_widgets.dart';
import 'package:flick/l10n/settings_l10n.dart';
import 'package:flick/services/usb_support_diagnostics_service.dart';
import 'package:flick/widgets/common/flick_snackbar.dart';

class UsbSupportDiagnosticsScreen extends StatefulWidget {
  const UsbSupportDiagnosticsScreen({super.key});

  @override
  State<UsbSupportDiagnosticsScreen> createState() =>
      _UsbSupportDiagnosticsScreenState();
}

class _UsbSupportDiagnosticsScreenState
    extends State<UsbSupportDiagnosticsScreen> {
  final _service = UsbSupportDiagnosticsService.instance;
  final _expected = TextEditingController();
  final _observed = TextEditingController();
  final _steps = TextEditingController();
  final _setup = TextEditingController();
  DateTime? _loadedAttempt;
  bool _working = true;

  @override
  void initState() {
    super.initState();
    _service.addListener(_changed);
    unawaited(_run(_service.load));
  }

  @override
  void dispose() {
    _service.removeListener(_changed);
    _expected.dispose();
    _observed.dispose();
    _steps.dispose();
    _setup.dispose();
    super.dispose();
  }

  void _changed() {
    if (!mounted) return;
    final report = _service.report;
    if (report?.startedAt != _loadedAttempt) {
      _loadedAttempt = report?.startedAt;
      _expected.text = report?.expected ?? '';
      _observed.text = report?.observed ?? '';
      _steps.text = report?.steps ?? '';
      _setup.text = report?.setup ?? '';
    }
    setState(() {});
  }

  void _message(String message) {
    if (!mounted) return;
    ScaffoldMessenger.of(
      context,
    ).showFlickSnackBar(SnackBar(content: Text(message)));
  }

  Future<void> _run(Future<void> Function() action) async {
    setState(() => _working = true);
    try {
      await action();
      _changed();
    } catch (_) {
      _message(l10n.usbSupportActionFailed);
    } finally {
      if (mounted) setState(() => _working = false);
    }
  }

  Future<void> _saveNotes() => _service.updateNotes(
    expected: _expected.text,
    observed: _observed.text,
    steps: _steps.text,
    setup: _setup.text,
  );

  Future<void> _export({required bool share}) async {
    await _saveNotes();
    final report = _service.report;
    if (report == null) return;
    final bytes = Uint8List.fromList(utf8.encode(report.toExportJson()));
    final stamp = report.startedAt.toUtc().toIso8601String().replaceAll(
      ':',
      '-',
    );
    final name = 'flick-usb-support-$stamp.json';
    if (!share) {
      final result = await FilePicker.saveFile(
        dialogTitle: l10n.usbSupportSave,
        fileName: name,
        type: FileType.custom,
        allowedExtensions: const ['json'],
        bytes: bytes,
      );
      if (result != null) _message(l10n.usbSupportSaved);
      return;
    }
    final temporary = await getTemporaryDirectory();
    // Own this directory so cleanup never touches another feature's files.
    final directory = Directory('${temporary.path}/usb-support-share');
    await directory.create(recursive: true);
    await for (final entry in directory.list()) {
      if (entry is File) await entry.delete();
    }
    final file = File('${directory.path}/$name');
    await file.writeAsBytes(bytes, flush: true);
    await Share.shareXFiles([
      XFile(file.path, mimeType: 'application/json'),
    ], subject: l10n.usbSupportTitle);
  }

  Future<void> _delete() async {
    await _service.deleteReport();
    final temporary = await getTemporaryDirectory();
    final directory = Directory('${temporary.path}/usb-support-share');
    if (await directory.exists()) await directory.delete(recursive: true);
    _message(l10n.usbSupportDeleted);
  }

  // Only display typed diagnostic identifiers here; details belong in preview.
  String _identifier(String? value) =>
      value != null && RegExp(r'^[a-zA-Z0-9_. -]{1,100}$').hasMatch(value)
      ? value
      : l10n.usbSupportUnknown;

  Future<void> _preview() async {
    await _saveNotes();
    final report = _service.report;
    if (!mounted || report == null) return;
    await showDialog<void>(
      context: context,
      builder: (context) => AlertDialog(
        title: Text(l10n.usbSupportPreview),
        content: SizedBox(
          width: double.maxFinite,
          child: SingleChildScrollView(
            child: SelectableText(report.toExportJson()),
          ),
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context),
            child: Text(l10n.close),
          ),
        ],
      ),
    );
  }

  Widget _field(TextEditingController controller, String label, bool enabled) =>
      Padding(
        padding: const EdgeInsets.only(bottom: 16),
        child: TextField(
          controller: controller,
          enabled: enabled,
          minLines: 2,
          maxLines: 5,
          maxLength: 2000,
          decoration: InputDecoration(
            labelText: label,
            alignLabelWithHint: true,
            border: const OutlineInputBorder(),
          ),
        ),
      );

  @override
  Widget build(BuildContext context) {
    final recording = _service.isRecording;
    final busy = _working || _service.isBusy;
    final report = _service.report;
    final canEdit = !busy && !recording;
    return SettingsScaffold(
      title: l10n.usbSupportTitle,
      body: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          SettingsCard(
            children: [
              Padding(
                padding: const EdgeInsets.all(16),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(l10n.usbSupportPrivacy),
                    const SizedBox(height: 12),
                    Text(l10n.usbSupportContents),
                    const SizedBox(height: 12),
                    Text(l10n.usbSupportRestartNotice),
                    const SizedBox(height: 16),
                    if (busy) const LinearProgressIndicator(),
                    const SizedBox(height: 8),
                    FilledButton.icon(
                      onPressed: busy
                          ? null
                          : () => _run(
                              recording
                                  ? _service.stop
                                  : () async {
                                      if (report != null) await _saveNotes();
                                      await _service.start();
                                    },
                            ),
                      icon: Icon(
                        recording ? Icons.stop : Icons.fiber_manual_record,
                      ),
                      label: Text(
                        recording ? l10n.usbSupportStop : l10n.usbSupportStart,
                      ),
                    ),
                    if (recording) ...[
                      const SizedBox(height: 12),
                      Text(l10n.usbSupportRecording),
                      const SizedBox(height: 8),
                      Text(l10n.usbSupportReproduce),
                    ],
                    if (report != null && !recording) ...[
                      const SizedBox(height: 12),
                      Text(
                        l10n.usbSupportReady,
                        style: Theme.of(context).textTheme.titleMedium,
                      ),
                    ],
                  ],
                ),
              ),
            ],
          ),
          if (report != null) ...[
            const SizedBox(height: 24),
            SettingsSectionHeader(l10n.usbSupportReport),
            Text(
              MaterialLocalizations.of(
                context,
              ).formatFullDate(report.startedAt.toLocal()),
            ),
            const SizedBox(height: 8),
            Text(l10n.usbSupportStage(_identifier(report.failingStage))),
            Text(l10n.usbSupportRoute(_identifier(report.lastRoute))),
            const SizedBox(height: 8),
            Text(l10n.usbSupportSummaryHint),
            const SizedBox(height: 24),
            SettingsSectionHeader(l10n.usbSupportContext),
            Text(l10n.usbSupportNotesPrivacy),
            const SizedBox(height: 16),
            _field(_expected, l10n.usbSupportExpected, canEdit),
            _field(_observed, l10n.usbSupportObserved, canEdit),
            _field(_steps, l10n.usbSupportSteps, canEdit),
            _field(_setup, l10n.usbSupportSetup, canEdit),
            Wrap(
              spacing: 8,
              runSpacing: 8,
              children: [
                OutlinedButton(
                  onPressed: canEdit
                      ? () => _run(() async {
                          await _saveNotes();
                          _message(l10n.usbSupportNotesSaved);
                        })
                      : null,
                  child: Text(l10n.usbSupportSaveNotes),
                ),
                OutlinedButton(
                  onPressed: canEdit ? () => _run(_preview) : null,
                  child: Text(l10n.usbSupportPreview),
                ),
                FilledButton.icon(
                  onPressed: canEdit
                      ? () => _run(() => _export(share: false))
                      : null,
                  icon: const Icon(Icons.save_alt),
                  label: Text(l10n.usbSupportSave),
                ),
                FilledButton.icon(
                  onPressed: canEdit
                      ? () => _run(() => _export(share: true))
                      : null,
                  icon: const Icon(Icons.share),
                  label: Text(l10n.usbSupportShare),
                ),
                TextButton.icon(
                  onPressed: canEdit ? () => _run(_delete) : null,
                  icon: const Icon(Icons.delete_outline),
                  label: Text(l10n.usbSupportDelete),
                ),
              ],
            ),
            const SizedBox(height: 12),
            Text(l10n.usbSupportRetained),
          ],
          const SizedBox(height: 24),
        ],
      ),
    );
  }
}
