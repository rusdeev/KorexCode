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
import android.content.SharedPreferences;
import android.content.res.AssetManager;
import android.preference.PreferenceManager;
import android.util.Log;

import org.catrobat.catroid.common.Constants;
import org.catrobat.catroid.utils.UtilZip;
import org.catrobat.catroid.utils.Utils;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * When an APK created via {@link ApkExporter} is launched, it contains an
 * extra asset - "embedded_project.krx" - that is not present in a regular
 * KorexCode install. On first launch, this class extracts that project
 * into the normal project storage location so that the app can jump
 * straight into it, giving the exported app a "standalone app" feel
 * instead of showing the full KorexCode main menu.
 */
public final class EmbeddedProjectLauncher {

	private static final String TAG = EmbeddedProjectLauncher.class.getSimpleName();
	private static final String EMBEDDED_ASSET_NAME = ApkExporter.EMBEDDED_PROJECT_ASSET_NAME;
	private static final String PREF_EMBEDDED_PROJECT_NAME = "embedded_project_extracted_name";

	private EmbeddedProjectLauncher() {
	}

	public static boolean hasEmbeddedProject(Context context) {
		try {
			InputStream inputStream = context.getAssets().open(EMBEDDED_ASSET_NAME);
			inputStream.close();
			return true;
		} catch (IOException ioException) {
			return false;
		}
	}

	/**
	 * Extracts (on first call) and returns the name of the embedded project,
	 * or null if extraction failed or there is no embedded project.
	 * Subsequent calls are cheap - the extracted project name is cached in
	 * SharedPreferences and re-used without touching the assets again.
	 */
	public static String getOrExtractEmbeddedProjectName(Context context) {
		SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);
		String cachedName = preferences.getString(PREF_EMBEDDED_PROJECT_NAME, null);
		if (cachedName != null && new File(Utils.buildProjectPath(cachedName)).exists()) {
			return cachedName;
		}

		if (!hasEmbeddedProject(context)) {
			return null;
		}

		try {
			File tempDir = new File(Constants.TMP_PATH);
			if (!tempDir.exists()) {
				tempDir.mkdirs();
			}
			File tempZip = new File(tempDir, EMBEDDED_ASSET_NAME);

			AssetManager assetManager = context.getAssets();
			InputStream inputStream = assetManager.open(EMBEDDED_ASSET_NAME);
			FileOutputStream outputStream = new FileOutputStream(tempZip);
			byte[] buffer = new byte[8192];
			int read;
			while ((read = inputStream.read(buffer)) != -1) {
				outputStream.write(buffer, 0, read);
			}
			inputStream.close();
			outputStream.close();

			String projectName = readProjectNameFromZip(tempZip);
			if (projectName == null) {
				projectName = "Embedded Project";
			}

			String projectPath = Utils.buildProjectPath(projectName);
			if (!UtilZip.unZipFile(tempZip.getAbsolutePath(), projectPath)) {
				Log.e(TAG, "Could not extract embedded project");
				return null;
			}

			tempZip.delete();

			preferences.edit().putString(PREF_EMBEDDED_PROJECT_NAME, projectName).apply();
			return projectName;
		} catch (IOException ioException) {
			Log.e(TAG, "Could not extract embedded project", ioException);
			return null;
		}
	}

	private static String readProjectNameFromZip(File zipFile) {
		try {
			java.util.zip.ZipInputStream zipInputStream =
					new java.util.zip.ZipInputStream(new java.io.FileInputStream(zipFile));
			java.util.zip.ZipEntry entry;
			while ((entry = zipInputStream.getNextEntry()) != null) {
				String name = entry.getName();
				// project root folder inside the zip, e.g. "MyProject/code.xml"
				int slashIndex = name.indexOf('/');
				if (slashIndex > 0) {
					zipInputStream.close();
					return name.substring(0, slashIndex);
				}
			}
			zipInputStream.close();
		} catch (IOException ioException) {
			Log.e(TAG, "Could not read project name from embedded zip", ioException);
		}
		return null;
	}
}
