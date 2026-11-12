package com.qualityverifier.fundi.reports

import androidx.core.content.FileProvider

/**
 * Hands a rendered report to the share sheet as a content URI (plan v7 PR 3c).
 *
 * A subclass with an empty body rather than `androidx.core.content.FileProvider` named in
 * the manifest: the Android reference warns that using FileProvider directly is not
 * reliable on some devices, and a subclass keeps this provider from colliding with one a
 * library declares. The paths it may serve are in `res/xml/file_paths.xml`: the reports
 * folder under the cache directory, and nothing else.
 */
class ReportFileProvider : FileProvider()
