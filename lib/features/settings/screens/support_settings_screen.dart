import 'package:flutter/material.dart';
import 'package:lucide_icons_flutter/lucide_icons.dart';
import 'package:flick/features/settings/screens/support_flick_screen.dart';
import 'package:flick/features/settings/screens/usb_support_diagnostics_screen.dart';
import 'package:flick/features/settings/widgets/settings_widgets.dart';
import 'package:flick/l10n/settings_l10n.dart';

class SupportSettingsScreen extends StatelessWidget {
  const SupportSettingsScreen({super.key});

  @override
  Widget build(BuildContext context) => SettingsScaffold(
    title: l10n.support,
    body: SettingsCard(
      children: [
        NavigationSetting(
          icon: LucideIcons.usb,
          title: l10n.usbSupportTitle,
          subtitle: l10n.usbSupportSubtitle,
          onTap: () => Navigator.of(context).push(
            MaterialPageRoute<void>(
              builder: (_) => const UsbSupportDiagnosticsScreen(),
            ),
          ),
        ),
        const SettingsDivider(),
        NavigationSetting(
          icon: LucideIcons.heart,
          title: l10n.supportFlick,
          subtitle: l10n.filipinoDonationNote,
          onTap: () => Navigator.of(context).push(
            MaterialPageRoute<void>(builder: (_) => const SupportFlickScreen()),
          ),
        ),
      ],
    ),
  );
}
