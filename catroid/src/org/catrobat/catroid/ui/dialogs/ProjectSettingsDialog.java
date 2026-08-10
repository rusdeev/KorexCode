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
package org.catrobat.catroid.ui.dialogs;

import android.app.AlertDialog;
import android.app.Dialog;
import android.app.DialogFragment;
import android.content.DialogInterface;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.RadioButton;

import org.catrobat.catroid.ProjectManager;
import org.catrobat.catroid.R;

/**
 * Settings screen for an already-created project. Currently lets the user
 * switch the project between portrait and landscape mode. Unlike
 * {@link OrientationDialog} (which only runs during project creation), this
 * dialog operates on the currently loaded project and re-saves it in place.
 */
public class ProjectSettingsDialog extends DialogFragment {

	public static final String DIALOG_FRAGMENT_TAG = "dialog_project_settings";

	private static final String TAG = ProjectSettingsDialog.class.getSimpleName();

	private Dialog projectSettingsDialog;
	private RadioButton landscapeMode;
	private RadioButton portraitMode;

	@Override
	public Dialog onCreateDialog(Bundle savedInstanceState) {
		View dialogView = LayoutInflater.from(getActivity()).inflate(R.layout.dialog_orientation_new_project, null);

		projectSettingsDialog = new AlertDialog.Builder(getActivity()).setView(dialogView)
				.setTitle(R.string.project_settings)
				.setPositiveButton(R.string.ok, new DialogInterface.OnClickListener() {
					@Override
					public void onClick(DialogInterface dialog, int which) {
					}
				}).setNegativeButton(R.string.cancel_button, new DialogInterface.OnClickListener() {
					@Override
					public void onClick(DialogInterface dialog, int which) {
					}
				}).create();

		projectSettingsDialog.setOnShowListener(new DialogInterface.OnShowListener() {
			@Override
			public void onShow(DialogInterface dialog) {
				if (getActivity() == null) {
					Log.e(TAG, "onShow() Activity was null!");
					return;
				}
				Button positiveButton = ((AlertDialog) dialog).getButton(AlertDialog.BUTTON_POSITIVE);
				positiveButton.setOnClickListener(new View.OnClickListener() {
					@Override
					public void onClick(View view) {
						handleOkButtonClick();
					}
				});
			}
		});

		portraitMode = (RadioButton) dialogView.findViewById(R.id.portrait);
		landscapeMode = (RadioButton) dialogView.findViewById(R.id.landscape_mode);

		if (getActivity() != null && ProjectManager.getInstance().getCurrentProject() != null) {
			boolean isLandscape = ProjectManager.getInstance().isCurrentProjectLandscapeMode();
			landscapeMode.setChecked(isLandscape);
			portraitMode.setChecked(!isLandscape);
		}

		return projectSettingsDialog;
	}

	protected void handleOkButtonClick() {
		if (getActivity() == null) {
			Log.e(TAG, "handleOkButtonClick() Activity was null!");
			return;
		}

		try {
			ProjectManager.getInstance().changeProjectOrientation(getActivity(), landscapeMode.isChecked());
		} catch (RuntimeException runtimeException) {
			// Defensive: a save failure (e.g. missing storage permission) should
			// not crash the app - just let the user know and keep the dialog usable.
			Log.e(TAG, Log.getStackTraceString(runtimeException));
			org.catrobat.catroid.utils.Utils.showErrorDialog(getActivity(), R.string.error_new_project);
			return;
		}

		dismiss();
	}
}
