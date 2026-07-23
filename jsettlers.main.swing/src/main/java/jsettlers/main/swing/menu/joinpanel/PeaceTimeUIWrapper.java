/*******************************************************************************
 * Copyright (c) 2015
 * <p>
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation files (the "Software"),
 * to deal in the Software without restriction, including without limitation the rights to use, copy, modify, merge, publish, distribute, sublicense,
 * and/or sell copies of the Software, and to permit persons to whom the Software is furnished to do so, subject to the following conditions:
 * <p>
 * The above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software.
 * <p>
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER
 * DEALINGS IN THE SOFTWARE.
 *******************************************************************************/
package jsettlers.main.swing.menu.joinpanel;

import jsettlers.graphics.localization.Labels;
import jsettlers.logic.player.EPeaceTime;

/**
 * Wraps an {@link EPeaceTime} for display in the peacetime combo box of the {@link JoinGamePanel}.
 */
public class PeaceTimeUIWrapper {

	private final EPeaceTime peaceTime;

	public PeaceTimeUIWrapper(EPeaceTime peaceTime) {
		this.peaceTime = peaceTime;
	}

	public EPeaceTime getPeaceTime() {
		return peaceTime;
	}

	@Override
	public String toString() {
		return Labels.getString("peace-time-" + peaceTime.name());
	}
}
