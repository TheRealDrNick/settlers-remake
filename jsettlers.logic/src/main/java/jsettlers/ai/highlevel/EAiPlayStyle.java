/*******************************************************************************
 * Copyright (c) 2024
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation files (the "Software"),
 * to deal in the Software without restriction, including without limitation the rights to use, copy, modify, merge, publish, distribute, sublicense,
 * and/or sell copies of the Software, and to permit persons to whom the Software is furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER
 * DEALINGS IN THE SOFTWARE.
 *******************************************************************************/
package jsettlers.ai.highlevel;

import jsettlers.logic.constants.ExtendedRandom;

/**
 * A behavioural "personality" for an AI player, picked (seeded) once per game so that opponents do not all behave identically and are less
 * predictable from game to game. A play style only flavours <em>how</em> an AI fights and expands; it deliberately does NOT touch the
 * economy build-speed factors that define the difficulty level, so the difficulty ordering (harder beats easier) is preserved - a very
 * hard "turtle" is still economically stronger than a hard "aggressor", it just fights differently.
 * <p>
 * The multipliers are intentionally modest and bounded for the same reason. All values are applied on top of the difficulty-derived
 * defaults in {@link jsettlers.ai.army.SimpleStrategy} and {@link jsettlers.ai.army.NavalInvasionModule}.
 *
 * @author jsettlers naval/behaviour AI
 */
public enum EAiPlayStyle {
	/** Cautious: attacks only with a clear advantage, keeps a large home garrison, rarely ventures overseas, waits longest before attacking, and seldom harasses. */
	TURTLE(0.8f, 1.4f, 1.5f, 0.05f),
	/** The neutral baseline - behaves like the difficulty defaults. */
	BALANCED(1.0f, 1.0f, 1.0f, 0.15f),
	/** Presses attacks readily, keeps only a small garrison at home, starts attacking sooner, and harasses often. */
	AGGRESSOR(1.25f, 0.85f, 0.4f, 0.30f),
	/** Loves shipping troops around: eager to commit overseas invasions and flanks with a smaller surplus; attacks fairly early; harasses the most. */
	RAIDER(1.1f, 0.7f, 0.6f, 0.35f);

	public static final EAiPlayStyle[] VALUES = values();

	/** Multiplies the attack-aggressiveness factor: &gt;1 attacks with a smaller numeric advantage, &lt;1 is more cautious. */
	public final float aggressionFactor;
	/** Multiplies the naval invasion caution: &gt;1 needs a bigger surplus/garrison before shipping troops, &lt;1 commits more eagerly. */
	public final float navalCautionFactor;
	/** Multiplies the opening no-attack grace period: &gt;1 waits longer before the first offensive, &lt;1 attacks sooner. */
	public final float attackGraceFactor;
	/** Probability, evaluated each heavy tick (when a large troop surplus exists), of launching a small harassment raid. */
	public final float harassChance;

	EAiPlayStyle(float aggressionFactor, float navalCautionFactor, float attackGraceFactor, float harassChance) {
		this.aggressionFactor = aggressionFactor;
		this.navalCautionFactor = navalCautionFactor;
		this.attackGraceFactor = attackGraceFactor;
		this.harassChance = harassChance;
	}

	/**
	 * Picks a play style using the given seeded RNG so the choice is deterministic (replay/multiplayer safe). Falls back to
	 * {@link #BALANCED} if no RNG is available yet.
	 */
	public static EAiPlayStyle pickRandom(ExtendedRandom random) {
		if (random == null) {
			return BALANCED;
		}
		return VALUES[random.nextInt(VALUES.length)];
	}
}
