/*
 * Catroid: An on-device visual programming system for Android devices
 * Copyright (C) 2010-2016 The Catrobat Team
 * (<http://developer.catrobat.org/credits>)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * An additional term exception under section 7 of the GNU Affero
 * General Public License, version 3, is available at
 * http://developer.catrobat.org/license_additional_term
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.catrobat.catroid.export;

import android.content.Context;
import android.util.Log;

import org.catrobat.catroid.common.Constants;
import org.catrobat.catroid.utils.UtilZip;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * Exports the currently open project as a standalone, installable APK.
 *
 * How it works:
 *  1. The app's own already-installed APK (getPackageCodePath()) is copied
 *     as the base for the new APK - this guarantees the exported app has
 *     working code, resources, and the whole KorexCode engine bundled.
 *  2. The current project is zipped (re-using the existing project-zipping
 *     code used for project upload) and inserted into the copy as
 *     assets/embedded_project.krx
 *  3. The modified copy is re-signed (v1/JAR signing) with a per-device
 *     generated key so Android will allow installing it.
 *  4. On next launch, {@code EmbeddedProjectLauncher} in the main activity
 *     checks for that asset - if present, the app skips the main menu and
 *     opens straight into that project, giving a "standalone app" feel.
 *
 * This is a lightweight approach and does not attempt to strip out the
 * KorexCode editor UI or produce a minimal/optimized APK - the exported
 * app is a full copy of KorexCode that auto-loads one project.
 */
public final class ApkExporter {

	private static final String TAG = ApkExporter.class.getSimpleName();
	public static final String EMBEDDED_PROJECT_ASSET_NAME = "embedded_project" + Constants.CATROBAT_EXTENSION;
	private static final String EMBEDDED_PROJECT_ZIP_ENTRY_PATH = "assets/" + EMBEDDED_PROJECT_ASSET_NAME;

	public interface ExportCallback {
		void onSuccess(File exportedApkFile);

		void onFailure(String errorMessage);
	}

	private ApkExporter() {
	}

	public static void export(Context context, String projectPath, String projectName, ExportCallback callback) {
		try {
			File tempDir = new File(Constants.TMP_PATH);
			if (!tempDir.exists() && !tempDir.mkdirs()) {
				callback.onFailure("Could not create temp directory");
				return;
			}

			String safeName = projectName.replaceAll("[^a-zA-Z0-9_\\-]", "_");
			File projectZip = new File(tempDir, safeName + Constants.CATROBAT_EXTENSION);
			if (!zipProject(projectPath, projectZip)) {
				callback.onFailure("Could not package project");
				return;
			}

			File unsignedApk = new File(tempDir, safeName + "_unsigned.apk");
			if (!copyBaseApkWithEmbeddedProject(context, projectZip, unsignedApk)) {
				callback.onFailure("Could not embed project into APK");
				return;
			}

			File signedApk = new File(getExportDirectory(context), safeName + ".apk");
			ApkSigningKeyProvider.SigningIdentity identity = ApkSigningKeyProvider.getOrCreateIdentity(context);
			if (identity == null) {
				callback.onFailure("Could not create signing key");
				return;
			}

			if (!ApkV1Signer.signApk(unsignedApk, signedApk, identity)) {
				callback.onFailure("Could not sign APK");
				return;
			}

			unsignedApk.delete();
			projectZip.delete();

			callback.onSuccess(signedApk);
		} catch (Exception exception) {
			Log.e(TAG, "APK export failed", exception);
			callback.onFailure(exception.getMessage());
		}
	}

	private static boolean zipProject(String projectPath, File outputZip) {
		File projectDir = new File(projectPath);
		File[] children = projectDir.listFiles();
		if (children == null) {
			return false;
		}
		String[] paths = new String[children.length];
		for (int i = 0; i < children.length; i++) {
			paths[i] = children[i].getAbsolutePath();
		}
		return UtilZip.writeToZipFile(paths, outputZip.getAbsolutePath());
	}

	private static boolean copyBaseApkWithEmbeddedProject(Context context, File projectZip, File outputApk) {
		String baseApkPath = context.getApplicationInfo().sourceDir;
		try {
			java.util.zip.ZipInputStream zipInputStream =
					new java.util.zip.ZipInputStream(new java.io.FileInputStream(baseApkPath));
			java.util.zip.ZipOutputStream zipOutputStream =
					new java.util.zip.ZipOutputStream(new FileOutputStream(outputApk));
			zipOutputStream.setLevel(0);

			java.util.zip.ZipEntry entry;
			byte[] buffer = new byte[8192];
			while ((entry = zipInputStream.getNextEntry()) != null) {
				String name = entry.getName();
				// Skip the original signature files - we will re-sign from scratch.
				if (name.startsWith("META-INF/") && (name.endsWith(".SF") || name.endsWith(".RSA")
						|| name.endsWith(".DSA") || name.endsWith(".MF"))) {
					continue;
				}
				zipOutputStream.putNextEntry(new java.util.zip.ZipEntry(name));
				int read;
				while ((read = zipInputStream.read(buffer)) != -1) {
					zipOutputStream.write(buffer, 0, read);
				}
				zipOutputStream.closeEntry();
			}
			zipInputStream.close();

			// Add the embedded project as an asset.
			zipOutputStream.putNextEntry(new java.util.zip.ZipEntry(EMBEDDED_PROJECT_ZIP_ENTRY_PATH));
			java.io.FileInputStream projectInputStream = new java.io.FileInputStream(projectZip);
			int read;
			while ((read = projectInputStream.read(buffer)) != -1) {
				zipOutputStream.write(buffer, 0, read);
			}
			projectInputStream.close();
			zipOutputStream.closeEntry();

			zipOutputStream.close();
			return true;
		} catch (IOException ioException) {
			Log.e(TAG, "Could not copy base APK", ioException);
			return false;
		}
	}

	private static File getExportDirectory(Context context) {
		File dir = new File(Constants.DEFAULT_ROOT, "exported_apks");
		if (!dir.exists()) {
			dir.mkdirs();
		}
		return dir;
	}
}
