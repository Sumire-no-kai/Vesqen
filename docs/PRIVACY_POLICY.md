# Vesqen Privacy Policy

> **Draft — not in effect.** Items marked `[TBD: …]` need a decision before publication.
>
> [TBD: Builds that include the Google Play Billing Library add the Internet permission and a Google data-transport component (see [MONETIZATION.md](MONETIZATION.md)). Before the paid version ships, rewrite every statement about Internet access, add the trial start time and cached unlock status to the on-device data, and describe how the purchase is checked, all based on verified device behavior.]

- Effective date: [TBD: effective date]
- Last updated: [TBD: date]
- 中文版：[PRIVACY_POLICY.zh-CN.md](PRIVACY_POLICY.zh-CN.md)

This policy explains what information the Vesqen Android app handles, where that information stays, and what we receive when you contact us or unlock Vesqen with an in-app purchase. It applies to every build of Vesqen we distribute, including Google Play and GitHub Releases.

## Summary

- Vesqen plays music stored on your device. It has no account, advertising, analytics or tracking.
- Vesqen does not request Android's Internet permission, so the app itself cannot send or receive data over the network.
- Your library, favorites, playlists and listening statistics stay in the app's private storage on your device. We never receive them.
- We only receive what you choose to send us (for example, a support email) and the limited order information Google Play provides when you buy the in-app unlock.

## Who we are

Vesqen is developed and published by [TBD: developer name], an independent developer. You can contact us about privacy at [TBD: contact email].

## Information the app handles on your device

Vesqen processes the following information only on your device. None of it is sent to us or to anyone else.

- **Your music library.** From the audio files and folders you allow Vesqen to access, it reads the file name, folder name, size, modification time, tags (such as title, artist, album, album artist, genre, year, and track and disc numbers), embedded artwork, and audio format details (such as codec, sample rate, bit depth, channel count and bitrate).
- **Your activity in the app.** Favorites and their order, playlists (names, contents, order, and when they were created or changed), how many times each track has been played and when it was last played, your current queue and playback position, and your settings.
- **Your playback chain.** To show the Chain view and to operate strict USB output, Vesqen reads audio route information that Android provides: the current output device's name and type (including connected Bluetooth audio devices), and for USB audio devices, the vendor ID, product ID, name, USB descriptor version and supported audio formats. Vesqen does not request direct access to USB devices.
- **Output verification records.** If you import a verification record file, Vesqen stores it in its private storage. To decide whether to show `BIT-PERFECT VERIFIED`, it compares the record with your current setup: the installed app's version and package hash, your phone's manufacturer, model and Android version, a hash of the system build fingerprint computed on the device, the connected USB audio device, and the audio format being played.

Vesqen never modifies or deletes your music files.

## Permissions

| Permission | Why Vesqen uses it |
| --- | --- |
| Read audio files (`READ_MEDIA_AUDIO` on Android 13 and later; `READ_EXTERNAL_STORAGE` on Android 12L and earlier) | To find and play the music on your device. |
| Folder access you grant in the system picker | To read music from the folders you choose. This access is read-only, and you can remove a folder at any time. |
| Notifications (`POST_NOTIFICATIONS`) | To show playback controls while music is playing. |
| Foreground service for media playback, and wake lock | To keep playing when the screen is off or Vesqen is in the background. |
| Modify audio settings (`MODIFY_AUDIO_SETTINGS`) | To request Android's bit-perfect USB output mode when you turn on strict USB output. |
| Network state (`ACCESS_NETWORK_STATE`) | Declared by Google's Media3 playback library, which Vesqen uses. It only reveals whether a network connection exists. Without the Internet permission, Vesqen cannot use a connection. |
| USB host (optional hardware feature) | To recognize USB audio devices. Vesqen still installs on phones without USB host support. |

Vesqen does not request the Internet, location, contacts, phone, camera, microphone or Bluetooth permissions.

## When information leaves the app

- **Android media controls.** While music plays, Vesqen gives Android the current track's title, artist, album, artwork and playback position for the notification and lock-screen controls. Android may pass this to devices and services you connect or authorize, such as Bluetooth car audio, watches or voice assistants. Vesqen only lets Android's system components and trusted controllers connect to its playback session; ordinary apps on your phone cannot read it.
- **Moving to a new phone.** Vesqen turns off Android cloud backup, so its data is not backed up to Google Drive. On some phones, Android's device-to-device transfer can still copy app data directly to a new phone when you choose to transfer your data.
- **Nothing else.** Vesqen contains no advertising, analytics, crash-reporting or tracking software. Public builds include no diagnostic recording or export.

## Information we receive

- **Emails you send us.** If you contact us, we receive your email address and whatever you include in your message. We use it only to reply to you.
- **In-app purchase through Google Play.** Vesqen is free to download. After the trial, you can unlock it with a one-time in-app purchase. Google handles payment. We never receive your card or other payment details, and Google's privacy policy applies to your purchase. Google Play Console lets us see order records for the in-app purchase, such as the order number, date, price and country or region, and lets us look up an order by the buyer's email address. We use this only to handle order questions and refunds.
- **Google Play statistics.** Google may give us aggregated statistics about installs and app stability, including crash reports from people who have chosen to share usage and diagnostics data with Google. We use them only to find and fix problems.
- **GitHub downloads.** If you download Vesqen from GitHub, GitHub's privacy policy applies to that download.

## Retention and deletion

- **On your device.** Data stays until you remove it. Removing a music folder deletes its library records. You can also delete playlists and remove favorites in the app. To delete all app data, open Android Settings → Apps → Vesqen → Storage and clear storage, or uninstall Vesqen.
- **Emails.** We delete support conversations within [TBD: 12 months] after they end, or sooner if you ask.
- **Order records.** Google keeps order records. We do not copy them elsewhere except when handling a specific order question or refund.

## Security

- Data on your device is kept in Vesqen's private storage, protected by Android's app sandbox, and is never transmitted by the app.
- Vesqen's access to your music folders is read-only.
- Our support mailbox is protected with two-step verification. [TBD: confirm]

## Children

Vesqen is not directed at children, and we do not knowingly collect personal information from children. If you believe a child has sent us personal information, contact us and we will delete it.

## Your choices and rights

You control everything Vesqen stores on your device, as described above. To ask what information we hold about you, or to correct or delete it, contact us at [TBD: contact email]. Depending on where you live, you may have additional rights under local law.

## Changes to this policy

When this policy changes, we will update this page and its "Last updated" date. Significant changes will also be mentioned in the release notes.

## Contact

[TBD: developer name] — [TBD: contact email]
