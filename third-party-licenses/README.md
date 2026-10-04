# Third-party license catalog

The app packages `licenses/third-party.xml` as a generated asset. Read it off the
main thread with `ThirdPartyLicenses.load(context.assets::open)`. The result is
`Loaded(List<ThirdPartyLicense>)` or `Unavailable(reason)`. Each entry contains its
coordinate/ID, upstream name, version, license names and full texts, and separate
NOTICE texts with their sources. The reader does not fetch URLs, localize labels,
or render a page. Source URLs are attribution metadata, not download instructions.

## Choice of approach

| Approach | Benefit | Limitation |
| --- | --- | --- |
| Generate licensing entirely from resolved POMs/archive metadata | Little manual inventory maintenance | Several current JARs/AARs omit license text and NOTICE; Guava declares its license in a parent POM. POM license URLs alone do not supply the required content. |
| Reviewed licensing records, checked against resolution and generated per variant (chosen) | Complete offline texts and explicit review on every artifact/version change; no license plugin or app dependency | Dependency updates require reviewing the new distribution and updating the catalog. |

`catalog.json` is the reviewed union of the currently enabled variants. Generation
selects only the actual variant runtime JAR/AAR components (including transitive
components), deduplicated by Maven coordinate. BOMs and other artifact-free Gradle
metadata are not shipped code and are not listed. Removed or other-variant entries
are never copied into that variant's asset. Build tools and test-only dependencies
are not app runtime dependencies. DeviceTest's app-host dependencies are included
in DeviceTest only.

Every selected coordinate and version must be present. Its original JAR/AAR SHA-256
must match, so even replaced bytes at the same version require a new review. No
unreviewed wildcard group rules or unknown-license fallbacks are used. Non-Maven
runtime dependencies fail and require an explicit coverage design before use.

The task is registered through `androidComponents.onVariants` and reads each
variant's own `runtimeConfiguration`. #47 can introduce Billing and non-Billing
flavors without sharing their generated catalogs. Billing and its transitives will
fail validation until reviewed; their records will only appear in variants that
actually resolve them. This change adds no runtime dependency or Google Play
Services plugin and changes no signing/release workflow.

## Sources reviewed

- The resolved components' POM license declarations are recorded as `pomSource`.
  AndroidX, JetBrains and JSpecify declarations identify Apache-2.0.
- Guava's three artifacts inherit Apache-2.0 from
  [guava-parent 33.3.1-android](https://repo.maven.apache.org/maven2/com/google/guava/guava-parent/33.3.1-android/guava-parent-33.3.1-android.pom)
  or [26.0-android](https://repo.maven.apache.org/maven2/com/google/guava/guava-parent/26.0-android/guava-parent-26.0-android.pom).
- Original distributions were inspected for LICENSE, NOTICE and COPYING documents,
  including nested `classes.jar` and `libs/*.jar`. AndroidX-provided LICENSE text is
  retained verbatim. The artifact's lint tooling is not packaged into the app and
  is outside the runtime inventory. The complete embedded concurrent-futures-ktx
  NOTICE is retained (DeviceTest only with the current graph).
- Where the archive omits license text, `texts/Apache-2.0.txt` is the complete text
  from [Apache](https://www.apache.org/licenses/LICENSE-2.0.txt), selected using the
  reviewed POM declaration, not inferred from a group name.
- The distributions omit library-specific notices available upstream. The full
  [coroutines 1.9.0 NOTICE](https://github.com/Kotlin/kotlinx.coroutines/blob/1.9.0/license/NOTICE.txt)
  and [serialization 1.7.3 NOTICE](https://github.com/Kotlin/kotlinx.serialization/blob/v1.7.3/license/NOTICE.txt)
  are included. The Kotlin compiler's NOTICE is explicitly for the compiler
  distribution; the compiler is not an app runtime component. Guava and Media3's
  checked release trees have LICENSE files and no root NOTICE.
- Instrument Sans and Instrument Serif retain the existing unmodified OFL texts
  in `app/src/main/assets/licenses/fonts`, including their copyright statements.
  Version `1.000` comes from each bundled TTF's name table (name ID 5); all three
  font binaries are pinned by SHA-256. Both font-directory and font-license-file
  membership are checked, so a new or removed font cannot bypass review.

The initial reviewed union is 96 Maven components and two font families. Release
contains 89 unique Maven components plus two fonts; Debug adds four tooling
components, and DeviceTest adds seven. Repeated Gradle artifact selections of the
same file (currently androidx.core:core) are one component, not duplicate licenses.

## Updating dependencies or fonts

1. Resolve the intended variant and inspect its runtime dependency report, e.g.
   `./gradlew :app:dependencies --configuration releaseRuntimeClasspath`.
2. Review the exact version's POM (including inherited licenses), original archive,
   nested runtime JARs, and upstream distribution for additional license/NOTICE
   content. Do not simply update a hash to make the check pass.
3. Add/update the exact coordinate, upstream name/version, SHA-256, metadata source,
   and local license/NOTICE text references in `catalog.json`. Preserve attribution
   text verbatim. Shared texts may be deduplicated; an empty `notices` list means no
   additional NOTICE was found during this review, not a placeholder to fill later.
4. For fonts, also update the font name-table version, binary hashes and original
   OFL asset. New font-specific notices should be stored in the same font license
   directory and referenced in the record.
5. Run `./gradlew :app:checkThirdPartyLicenses` and the focused reader tests, then
   the repository's unit/lint/build checks. Inspect each generated variant asset.
   Remove unused catalog records when they no longer occur in any enabled variant.

## Validation and CI

- `generate<Variant>ThirdPartyLicenses` is a generated-assets dependency of each
  APK/AAB build. Missing records, changed hashes, missing/empty text, duplicate IDs,
  unsupported schema or unreviewed font files fail the build.
- `checkThirdPartyLicenses` validates all enabled variants and is attached to
  `check`. Existing CI assembles Debug, DeviceTest, Profile and Release; no CI
  publishing workflow changes are needed.
- BuildSrc's six generator regressions run before its JAR is used (up-to-date when
  unchanged), so the existing CI commands test dependency additions/upgrades,
  same-version replacement, variant exclusion, document preservation, invalid
  text, and font membership. They use the existing JUnit version only in build
  tooling, never in the application.
- Each enabled host test receives its variant's generated asset. JVM reader tests
  check text preservation, ownership/closure of streams, malformed/unsupported
  catalogs, duplicate entries, external-entity rejection, and real generated data.
- Generation uses local reviewed content and Gradle's resolved artifacts. Once
  dependencies are cached, it works with Gradle `--offline`. There is no license
  retrieval or dependency on network access during app execution.
