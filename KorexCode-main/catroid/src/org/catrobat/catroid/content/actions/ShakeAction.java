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
package org.catrobat.catroid.content.actions;

import com.badlogic.gdx.scenes.scene2d.actions.TemporalAction;

import org.catrobat.catroid.content.Sprite;
import org.catrobat.catroid.formulaeditor.Formula;
import org.catrobat.catroid.formulaeditor.InterpretationException;

import java.util.Random;

public class ShakeAction extends TemporalAction {
	private Sprite sprite;
	private Formula intensity;
	private static final Random RANDOM = new Random();
	private static final float DEFAULT_INTENSITY = 10f;

	private float originalX;
	private float originalY;
	private boolean initialized = false;

	@Override
	protected void begin() {
		originalX = sprite.look.getXInUserInterfaceDimensionUnit();
		originalY = sprite.look.getYInUserInterfaceDimensionUnit();
		initialized = true;
	}

	@Override
	protected void update(float percent) {
		if (!initialized) {
			begin();
		}

		float shakeIntensity = DEFAULT_INTENSITY;
		if (intensity != null) {
			try {
				shakeIntensity = intensity.interpretFloat(sprite);
			} catch (InterpretationException interpretationException) {
				shakeIntensity = DEFAULT_INTENSITY;
			}
		}

		float offsetX = (RANDOM.nextFloat() * 2f - 1f) * shakeIntensity;
		float offsetY = (RANDOM.nextFloat() * 2f - 1f) * shakeIntensity;

		sprite.look.setPositionInUserInterfaceDimensionUnit(originalX + offsetX, originalY + offsetY);
	}

	@Override
	protected void end() {
		sprite.look.setPositionInUserInterfaceDimensionUnit(originalX, originalY);
	}

	public void setSprite(Sprite sprite) {
		this.sprite = sprite;
	}

	public void setIntensity(Formula intensity) {
		this.intensity = intensity;
	}
}
