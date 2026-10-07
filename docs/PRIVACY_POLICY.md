# Vesqen Privacy Policy

- Effective date: September 26, 2026
- Last updated: October 7, 2026

This policy explains what information the Vesqen Android app handles, where that information stays, what the app sends over the network, and what we receive when you contact us or join a test. It applies to every build of Vesqen we distribute, including Google Play and GitHub Releases, and to the Vesqen website.

## Summary

- Vesqen plays music stored on your device. It has no account, advertising or tracking, and it contains no third-party analytics or crash-reporting software.
- Playback and your library never use the network. Your library, favorites, playlists and play history stay in the app's private storage on your device, and we never receive them.
- From version 1.0.0-beta.2, the GitHub version of Vesqen uses the network for three things: a usage statistics message at most once a day, device reports that you choose to upload, and update checks. Each is described below, and you can turn each of them off.
- Usage statistics carry no install or device ID, and never include your location, your music or what you listen to. In the EU, the European Economic Area and the UK, Vesqen asks before sending them. Elsewhere they are on by default, and the first screen lets you turn them off.
- We also receive what you choose to send us, such as a support email, or your email address if you join a test.
- The Vesqen website has no advertising or tracking cookies. It counts visits with Cloudflare Web Analytics, which uses no cookies, and uses a cookie to remember your language only if you allow it.

## Who we are

Vesqen is developed and published by Sumire Studio. Sumire Studio is the publishing name of an independent developer, who is responsible for the processing described in this policy. You can contact us about privacy at vesqen@sumirenokai.com.

## Information the app handles on your device

Vesqen processes the following information on your device. None of it is sent to us or to anyone else, unless you put it in a device report yourself.

- **Your music library.** From the audio files and folders you allow Vesqen to access, it reads the file name, folder name, size, modification time, tags (such as title, artist, album, album artist, genre, year, and track and disc numbers), embedded artwork, and audio format details (such as codec, sample rate, bit depth, channel count and bitrate).
- **Your activity in the app.** Favorites and their order, playlists (names, contents, order, and when they were created or changed), how many times each track has been played and when it was last played, your current queue and playback position, and your settings.
- **Your playback chain.** To show the Chain view and to operate strict USB output, Vesqen reads audio route information that Android provides: the current output device's name and type (including connected Bluetooth audio devices), and for USB audio devices, the vendor ID, product ID, name, USB descriptor version and supported audio formats. Vesqen does not request direct access to USB devices.
- **Output verification records.** If you import a verification record file, Vesqen stores it in its private storage. To decide whether to show `BIT-PERFECT VERIFIED`, it compares the record with your current setup: the installed app's version and package hash, your phone's manufacturer, model and Android version, a hash of the system build fingerprint computed on the device, the connected USB audio device, and the audio format being played.
- **Recent errors.** Vesqen keeps a short record of recent playback errors, strict USB stops and the reasons Android gave for closing the app: at most 100 events from the last 7 days. For a track that failed to play, it keeps the track's format and file name. The record exists so you can choose to include it in a device report.
- **Your region.** To decide whether to ask before sending usage statistics, Vesqen reads the region of your phone's language settings and the country codes of your SIM card and mobile network. This needs no permission. The result is used on the device and is not stored or sent.

Vesqen never modifies or deletes your music files.

## Permissions

| Permission | Why Vesqen uses it |
| --- | --- |
| Read audio files (`READ_MEDIA_AUDIO` on Android 13 and later; `READ_EXTERNAL_STORAGE` on Android 12L and earlier) | To find and play the music on your device. |
| Folder access you grant in the system picker | To read music from the folders you choose. This access is read-only, and you can remove a folder at any time. |
| Notifications (`POST_NOTIFICATIONS`) | To show playback controls while music is playing. |
| Foreground service for media playback, and wake lock | To keep playing when the screen is off or Vesqen is in the background. |
| Modify audio settings (`MODIFY_AUDIO_SETTINGS`) | To request Android's bit-perfect USB output mode when you turn on strict USB output. |
| Internet (`INTERNET`) | For usage statistics, device report uploads and update checks, described below. Playback and your library never use it. |
| Network state (`ACCESS_NETWORK_STATE`) | To check that a working connection exists before sending usage statistics. Google's Media3 playback library, which Vesqen uses, also declares it. |
| Install apps (`REQUEST_INSTALL_PACKAGES`) | To install an update you downloaded in Vesqen. Android asks you to confirm every installation. |
| USB host (optional hardware feature) | To recognize USB audio devices. Vesqen still installs on phones without USB host support. |

Only reading your music is essential. If you refuse notifications or folder access, Vesqen still plays the music it can read. Vesqen does not request location, contacts, phone, camera, microphone or Bluetooth permissions.

## When information leaves the app

- **Android media controls.** While music plays, Vesqen gives Android the current track's title, artist, album, artwork and playback position for the notification and lock-screen controls. Android may pass this to devices and services you connect or authorize, such as Bluetooth car audio, watches or voice assistants. Only Android's system components, and apps you have allowed to read notifications or control media (such as a smartwatch app), can connect to Vesqen's playback session. Other apps on your phone cannot.
- **Moving to a new phone.** Vesqen does not move its data to a new phone. Your library, favorites, playlists, play counts and last-played times, queue, settings and output verification records stay only on your old phone. On the new phone, you need to allow Vesqen to read your audio files again, or add your music folders again. Vesqen turns off Android cloud backup, so this data is not backed up to Google Drive, and it asks Android not to copy this data during device-to-device transfer. Some phone makers' transfer tools may not follow this request and may still copy the app's data to the new phone.
- **Network features.** The app uses the network only for usage statistics, device report uploads and update checks, including the status file and downloads that belong to them. The next three sections describe them.

## Usage statistics

- **What is sent.** At most once a day, when you open Vesqen, it sends one message with: the app version and the distribution channel; the Android version, phone manufacturer, model and system build name; whether a connected USB audio device offers Android's bit-perfect mode, when Android can tell; whether a USB audio device was connected in the last 7 days; and whether this is the first message today, this week and this month.
- **What is never sent.** No install or device ID, no account, no location or country, and nothing about your music, file names or what you listen to.
- **What stays on your phone.** Vesqen remembers when it last sent statistics and when it last saw a USB audio device. It needs these times to send at most once a day and to fill in the fields above.
- **Where it goes.** The message goes to our statistics service, which runs on Cloudflare. The service adds the message to daily totals and does not keep the message itself. It does not record IP addresses. Cloudflare handles your IP address to deliver the request and to protect the service, as our service provider. The reply can include the latest version information, which Vesqen uses for update checks.
- **Your choice.** In the EU, the European Economic Area and the UK, and whenever Vesqen cannot read a country, it asks first, with neither answer selected, and sends nothing unless you agree. Elsewhere statistics are on by default: the first screen explains them and has the switch, and nothing is sent before you leave that screen. You can turn statistics off at any time in Settings → Privacy & data → Usage statistics, and Vesqen stops sending at once. Statistics already sent are part of daily totals that are not linked to you, so they cannot be removed one by one.
- **Pausing.** Right before it sends statistics or uploads a report, Vesqen reads a small status file on our website to check whether we have paused the service, at most once every 10 minutes. With statistics off, it reads the file only when you upload a report. The request carries nothing about you beyond what every web request carries, such as your IP address.

## Device reports

- **Creating a report.** In Settings → Privacy & data → Export device report, you can create a report to help us with a problem. The report always includes the app version and build, your phone's manufacturer, model, Android version and system build name, and when it was made. You choose whether to add: your audio output's capabilities (including USB audio devices' vendor and product IDs and supported formats); the values on the Chain page (such as formats, decoder, buffering, battery and processor readings); the recent errors described above; the formats of tracks that failed; and, separately, their file names. Bluetooth device names, output route names, decoder names and the processor model are removed. You see the complete report before it leaves the app.
- **Sending it.** You can share the report with an app you choose, email it to us, or upload it. Sharing and email go through the app you pick. Vesqen keeps the latest report files in its cache for sharing, and removes files older than a day the next time you share.
- **Uploading it.** An upload sends the report to our service on Cloudflare. The service stores the report with a random receipt number and the time it arrived, and does not record IP addresses. The report is not linked to you unless you send us its receipt number. We keep uploaded reports for 7 days and then delete them. To delete a report sooner, email us its receipt number.

## Update checks

- **Checking.** The GitHub version of Vesqen can check for new versions. It checks automatically at most once a day when you open the app, and whenever you tap Check for updates. A check reads a version file from our website, vesqen.sumirenokai.com, which Cloudflare hosts. When usage statistics are on, the version information can arrive with the statistics reply instead. A check carries no identifiers, only what every web request carries, such as your IP address. You can turn automatic checks off in Settings → Updates → Check automatically.
- **Downloading.** Vesqen downloads an update only when you tap Download. The file comes from GitHub, and GitHub's privacy statement applies to that download. Before installing, Vesqen checks the file's SHA-256 and signing certificate, and Android asks you to confirm. The downloaded file is deleted once it is handed to Android's installer, or if the update fails.
- **Other stores.** If Google Play installed Vesqen, it does not check GitHub for updates. If another app, such as an F-Droid client, manages Vesqen's updates, automatic checks start turned off.

## Information we receive

- **Emails you send us.** If you contact us, we receive your email address and whatever you include in your message, including any device report you attach. We use it only to reply to you. Mail to our sumirenokai.com addresses is forwarded by Cloudflare's email routing and stored in our Microsoft Outlook mailbox.
- **Statistics and reports.** From usage statistics we see only daily totals. From device reports we see the reports you upload, for the 7 days we keep them.
- **Closed testing.** If you join a test of Vesqen on Google Play, the email address of your Google account is added to the test's tester list in Google Play Console, so Google can give you access. We also see any feedback you send through Google Play. We use this only to run the test.
- **Purchases.** Vesqen has no in-app purchases yet. If a later version adds one, we will update this policy before that version is released.
- **Google Play statistics.** Google may give us aggregated statistics about installs and app stability, including crash reports from people who have chosen to share usage and diagnostics data with Google. We use them only to find and fix problems.
- **GitHub downloads.** If you download Vesqen from GitHub, in a browser or through the app's update check, GitHub's privacy statement applies to that download.

## The Vesqen website

The Vesqen website (vesqen.sumirenokai.com) is hosted by Cloudflare. It has no accounts, forms or advertising. It also serves the version files and the status file that the app reads.

- **Serving the site.** To deliver pages and files and protect the site from abuse, Cloudflare processes each request, including your IP address, browser information and the address of the page. Cloudflare does this on our behalf, and it may also use this network data to protect its own services, as its privacy policy describes. Cloudflare shows us aggregated statistics, such as the number of requests by country or region. We don't use this information to identify visitors.
- **Choosing a language.** When you open the home page, the site shows English or Chinese based on your browser's language setting. If that doesn't decide it, the site uses the country or region Cloudflare estimates from your IP address. This check runs on each visit and stores nothing.
- **Visit statistics.** The site counts visits with Cloudflare Web Analytics. A small Cloudflare script on each page reports the address of the page, the page you came from and how quickly the page loaded, and Cloudflare adds the country or region and the type of browser and device. The script uses no cookies and stores nothing in your browser. We only see aggregated statistics, which Cloudflare shows us for up to six months. Visits through Cloudflare's data centers in the EU, the European Economic Area and the UK are not counted.
- **Cookies.** On your first visit, the site asks whether it may remember your language. It sets at most two cookies, and neither is used for tracking:

| Cookie | Purpose | Kept for |
| --- | --- | --- |
| `vesqen-consent` | Remembers whether you accepted or rejected cookies, so the site doesn't ask again. | 6 months |
| `vesqen-lang` | Remembers your language. Set only after you accept. | 6 months |

You can change your answer at any time with "Cookie settings" at the bottom of each page. If you reject, the site deletes `vesqen-lang`. If Cloudflare needs to check that a visit isn't automated, it may set its own security cookies, such as `__cf_bm` or `cf_clearance`. Cloudflare needs these cookies to run the service, and they aren't used to track you. Neither the app nor the website tracks you across other sites or apps, so a browser's Do Not Track setting changes nothing here.

## Service providers and international transfers

| Provider | What it does for Vesqen | Where |
| --- | --- | --- |
| Cloudflare, Inc. | Hosts the website, the statistics and report service and the version files, and forwards our email | United States, with data centers worldwide |
| Microsoft Corporation | Stores our mailbox | United States and other countries |
| GitHub, Inc. | Hosts the app's downloads under its own privacy statement | United States |

Cloudflare acts as our service provider for the website, the statistics and report service and email forwarding. It is certified under the EU-U.S. Data Privacy Framework, its UK Extension and the Swiss-U.S. Data Privacy Framework, and its data processing terms include the EU Standard Contractual Clauses. Information in this policy may be processed outside your country, including in the United States. It reaches these providers over encrypted connections when you use a feature that needs them, and each provider's privacy policy explains how to contact it. If you don't want this, turn statistics and automatic checks off and don't upload reports. Playback and your library keep working.

## Retention and deletion

| Information | Kept for |
| --- | --- |
| Data in the app on your device | Until you delete it |
| Recent errors on your device | 7 days, at most 100 events |
| Usage statistics | Daily totals for 90 days |
| Uploaded device reports | 7 days |
| Support emails | Up to 12 months after the conversation ends |
| Test tester list | Up to 2 months after the test ends |
| Website visit statistics | Shown to us for up to 6 months |

- **On your device.** Removing a music folder deletes its library records. You can also delete playlists and remove favorites in the app. To delete all app data, open Android Settings → Apps → Vesqen → Storage and clear storage, or uninstall Vesqen.
- **On our service.** Expired statistics and reports are deleted within about an hour of the time shown above. Cloudflare's database keeps its own point-in-time backups for up to 30 days, after which deleted records are gone from those backups too.
- **Earlier deletion.** We delete emails, tester entries and uploaded reports sooner if you ask.

## Security

- Data on your device is kept in Vesqen's private storage, protected by Android's app sandbox. The app sends only the network messages described in this policy.
- All network requests use HTTPS. Vesqen installs an update only after checking its SHA-256 and that it is signed with the same key as the installed app.
- Vesqen's access to your music folders is read-only.
- Our statistics and report service keeps no IP addresses and no request logs.
- Our support mailbox is protected with two-step verification.

## Children

Vesqen is not directed at children, and we do not knowingly collect personal information from children. If you believe a child has sent us personal information, contact us and we will delete it.

## Your choices and rights

- **In the app.** You control everything Vesqen stores on your device, as described above. You can turn usage statistics off in Settings → Privacy & data → Usage statistics, and automatic update checks off in Settings → Updates. Uploading a report is always your choice.
- **Your rights.** You can ask us to confirm whether we hold information about you, to access, copy, correct or delete it, to explain how we use it and who we share it with, to restrict or object to that use, or to withdraw consent. Email vesqen@sumirenokai.com. We reply within 15 working days.
- **Complaints.** You can complain to us at the same address. We acknowledge a complaint within 30 days and tell you what we did about it. You can also complain to the data protection authority where you live, such as the Information Commissioner's Office in the UK or the ANPD in Brazil.
- **Legal bases.** Where EU or UK data protection law applies, we rely on your consent for usage statistics, the language cookie and the reports you upload. Where Brazilian law applies to statistics, we rely on our legitimate interest in learning which phones and Android versions Vesqen runs on, and you can object at any time by turning statistics off. For update checks, answering you, running tests, and keeping the website and our service available and secure, we rely on our legitimate interests in those things.

## Changes to this policy

When this policy changes, we will update this page and its "Last updated" date. Significant changes will also be mentioned in the release notes. Earlier versions remain in the history of the Vesqen repository on GitHub.

## Contact

Sumire Studio — vesqen@sumirenokai.com
