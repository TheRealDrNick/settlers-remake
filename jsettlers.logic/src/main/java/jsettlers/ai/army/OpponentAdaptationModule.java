/*******************************************************************************
 * Copyright (c) 2026
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
package jsettlers.ai.army;

import jsettlers.common.movable.EMovableType;
import jsettlers.common.player.IPlayer;
import jsettlers.common.position.ShortPoint2D;
import jsettlers.logic.movable.interfaces.ILogicMovable;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Per-opponent adaptation for the army AI.
 * <p>
 * A scripted AI feels mechanical when it treats every opponent identically. The default target selection ({@link
 * ArmyFramework#getWeakestEnemy(boolean)}) always marches on the globally weakest enemy regardless of who has actually been harassing
 * us. This module makes the AI behave more like a human who <em>holds a grudge</em>: it remembers, per opponent, how much that
 * opponent has recently been intruding into our town and biases our offensive target toward the enemy that has been pressuring us the
 * most - a retaliation instinct - while still respecting reachability and refusing hopeless fights.
 * <p>
 * <b>CRITICAL balance-safety guarantee - no-op with a single enemy.</b> {@link #selectTargetEnemy(boolean)} returns
 * {@link ArmyFramework#getWeakestEnemy(boolean)} <em>unchanged</em> whenever at most one alive enemy exists, and the accumulated grudge
 * data is never consulted on that path. The difficulty regression suite ({@code AiDifficultiesIT}) is always 1&nbsp;vs&nbsp;1, so the
 * feature is provably inert there and cannot regress the difficulty ladder.
 * <p>
 * The adaptation is additionally gated behind {@link ArmyFramework#usesAdvancedTactics()} so only the harder AIs adapt, consistent
 * with the existing difficulty-by-behaviour design; easier AIs keep the simpler, mechanical weakest-enemy behaviour.
 * <p>
 * <b>Determinism.</b> The grudge is accumulated purely from the deterministic {@link ArmyFramework#getEnemiesInTown()} snapshot and
 * intruder ownership read from {@link ArmyFramework#movableGrid}; all tie-breaks use a fixed ordering (lowest player id). No randomness
 * or wall-clock time is used, so replays and multiplayer stay in sync.
 * <p>
 * <b>Memory.</b> The per-opponent grudge map is small (one {@code double} per opponent) and is intentionally <em>not</em> serialised
 * with the savegame; it simply re-learns from observed intrusions after a reload.
 */
public class OpponentAdaptationModule extends ArmyModule {

	/** Grudge added per intruding enemy-soldier tile observed inside our town in one heavy tick. */
	private static final double GRUDGE_PER_INTRUDER = 1.0;
	/**
	 * Multiplicative decay applied to every grudge each heavy tick, so the grudge reflects <em>recent</em> pressure (an exponential
	 * moving window over heavy ticks) rather than the whole game's history. A steady raid of {@code n} intruder tiles converges to a
	 * grudge of {@code n * GRUDGE_PER_INTRUDER / (1 - GRUDGE_DECAY)}.
	 */
	private static final double GRUDGE_DECAY = 0.75;
	/** Grudges below this are forgotten (dropped from the map) to keep the memory small. */
	private static final double GRUDGE_FORGET_THRESHOLD = 0.5;
	/**
	 * Minimum grudge before we bother to retaliate against an opponent. Tuned so that a single lone scout wandering through
	 * (steady-state grudge {@code 1 / (1 - GRUDGE_DECAY) = 4}) does not trigger retaliation, but a sustained or multi-soldier raid does.
	 */
	private static final double RETALIATION_MIN_GRUDGE = 6.0;
	/**
	 * Hysteresis margin: once committed to retaliating against one opponent, a different opponent must out-grudge the committed target
	 * by at least this much before we switch. Prevents the whole army flip-flopping between two similarly-aggressive enemies every tick.
	 */
	private static final double RETALIATION_SWITCH_MARGIN = 4.0;
	/**
	 * We only redirect from the default (weakest) target to a retaliation target whose army is no larger than the weakest enemy's army
	 * times this factor (plus {@link #RETALIATION_STRENGTH_SLACK}). This keeps retaliation from steering us into a fight that is clearly
	 * harder than the soft target we would otherwise have picked; the real go/no-go decision remains {@code attackIsPossible}.
	 */
	private static final float RETALIATION_STRENGTH_TOLERANCE = 1.35f;
	/** Absolute soldier-count slack added to the tolerance test, so a lightly-defended aggressor can still be chosen over a near-empty weakest enemy. */
	private static final int RETALIATION_STRENGTH_SLACK = 6;

	private static final byte NO_TARGET = -1;

	/** Recent-intrusion pressure per opponent player id. Transient (not serialised); re-learned after reload. */
	private final Map<Byte, Double> grudgeByPlayerId = new HashMap<>();

	/** The opponent we are currently committed to retaliating against, or {@link #NO_TARGET}. */
	private byte committedTargetId = NO_TARGET;

	public OpponentAdaptationModule(ArmyFramework parent) {
		super(parent);
	}

	@Override
	public void applyHeavyRules(Set<Integer> soldiersWithOrders) {
		// Only the harder AIs adapt; for everyone else this module carries no state and does nothing (its selection also falls back).
		if (!parent.usesAdvancedTactics()) {
			return;
		}

		// Decay first so the grudge is a moving window over recent heavy ticks, then forget negligible entries to keep the map tiny.
		grudgeByPlayerId.replaceAll((id, grudge) -> grudge * GRUDGE_DECAY);
		grudgeByPlayerId.values().removeIf(grudge -> grudge < GRUDGE_FORGET_THRESHOLD);

		// Sample the current intruders in our town and attribute each to its owning opponent.
		for (ShortPoint2D intruderPosition : parent.getEnemiesInTown()) {
			ILogicMovable intruder = parent.movableGrid.getMovableAt(intruderPosition.x, intruderPosition.y);
			if (intruder == null) {
				continue; // the movable moved on between the statistics snapshot and now
			}
			byte ownerId = intruder.getPlayer().getPlayerId();
			grudgeByPlayerId.merge(ownerId, GRUDGE_PER_INTRUDER, Double::sum);
		}
	}

	@Override
	public void applyLightRules(Set<Integer> soldiersWithOrders) {
		// nothing to do on the light tick
	}

	/**
	 * Chooses which enemy to attack, refining the default weakest-enemy selection with per-opponent retaliation.
	 *
	 * @param landReachableOnly
	 *            forwarded to {@link ArmyFramework#getWeakestEnemy(boolean)}; when true only land-reachable enemies are considered
	 *            (across-water enemies are handled by the naval invasion logic).
	 * @return the enemy to attack, or {@code null} if there is no (reachable) enemy - identical to {@code getWeakestEnemy} on the
	 *         no-op paths described in the class javadoc.
	 */
	IPlayer selectTargetEnemy(boolean landReachableOnly) {
		List<IPlayer> aliveEnemies = parent.aiStatistics.getAliveEnemiesOf(parent.getPlayer());

		// === CRITICAL no-op guarantee (see class javadoc) ===
		// With at most one alive enemy there is nothing to adapt between, so we defer byte-for-byte to the existing weakest-enemy
		// selection and never look at the grudge data. This makes the whole feature inert in the always-1v1 difficulty suite.
		if (aliveEnemies.size() <= 1) {
			committedTargetId = NO_TARGET;
			return parent.getWeakestEnemy(landReachableOnly);
		}

		IPlayer weakest = parent.getWeakestEnemy(landReachableOnly);
		// Only the harder AIs adapt; easier AIs keep the mechanical weakest-enemy behaviour even against several opponents.
		if (weakest == null || !parent.usesAdvancedTactics()) {
			committedTargetId = NO_TARGET;
			return weakest;
		}

		IPlayer topAggressor = highestGrudgeEnemy(aliveEnemies, landReachableOnly);

		// Commitment / hysteresis: keep pursuing the opponent we already committed to while it remains a valid, still-aggressing and
		// still-worthwhile target, unless another aggressor now clearly out-grudges it. Avoids flip-flopping the army every tick.
		if (committedTargetId != NO_TARGET) {
			IPlayer committed = findAliveEnemy(aliveEnemies, committedTargetId);
			if (committed != null && isRetaliationCandidate(committed, landReachableOnly)
					&& (topAggressor == null || grudgeOf(committedTargetId) + RETALIATION_SWITCH_MARGIN >= grudgeOf(topAggressor.getPlayerId()))
					&& isWorthRetaliating(committed, weakest)) {
				return committed;
			}
		}

		if (topAggressor != null && isWorthRetaliating(topAggressor, weakest)) {
			committedTargetId = topAggressor.getPlayerId();
			return topAggressor;
		}

		committedTargetId = NO_TARGET;
		return weakest;
	}

	/**
	 * @return the reachable alive enemy with the highest recent-intrusion grudge above {@link #RETALIATION_MIN_GRUDGE}, breaking ties by
	 *         the lowest player id for determinism, or {@code null} if no opponent has pressured us enough to warrant retaliation.
	 */
	private IPlayer highestGrudgeEnemy(List<IPlayer> aliveEnemies, boolean landReachableOnly) {
		IPlayer best = null;
		double bestGrudge = 0;
		for (IPlayer enemy : aliveEnemies) {
			if (!isRetaliationCandidate(enemy, landReachableOnly)) {
				continue;
			}
			double grudge = grudgeOf(enemy.getPlayerId());
			if (best == null || grudge > bestGrudge || (grudge == bestGrudge && enemy.getPlayerId() < best.getPlayerId())) {
				best = enemy;
				bestGrudge = grudge;
			}
		}
		return best;
	}

	private boolean isRetaliationCandidate(IPlayer enemy, boolean landReachableOnly) {
		if (grudgeOf(enemy.getPlayerId()) < RETALIATION_MIN_GRUDGE) {
			return false;
		}
		return !landReachableOnly || parent.hasLandReachableMilitaryBuilding(enemy);
	}

	/**
	 * @return true if redirecting from the default (weakest) target to the retaliation target does not steer us into a clearly harder
	 *         fight than the weakest target: the aggressor's army must be within {@link #RETALIATION_STRENGTH_TOLERANCE} (plus a small
	 *         slack) of the weakest enemy's army. The aggressor <em>being</em> the weakest enemy is always worthwhile.
	 */
	private boolean isWorthRetaliating(IPlayer aggressor, IPlayer weakest) {
		if (aggressor.getPlayerId() == weakest.getPlayerId()) {
			return true;
		}
		int aggressorSoldiers = parent.aiStatistics.getCountOfMovablesOfPlayer(aggressor, EMovableType.SOLDIERS);
		int weakestSoldiers = parent.aiStatistics.getCountOfMovablesOfPlayer(weakest, EMovableType.SOLDIERS);
		return aggressorSoldiers <= weakestSoldiers * RETALIATION_STRENGTH_TOLERANCE + RETALIATION_STRENGTH_SLACK;
	}

	private double grudgeOf(byte playerId) {
		return grudgeByPlayerId.getOrDefault(playerId, 0.0);
	}

	private static IPlayer findAliveEnemy(List<IPlayer> aliveEnemies, byte playerId) {
		for (IPlayer enemy : aliveEnemies) {
			if (enemy.getPlayerId() == playerId) {
				return enemy;
			}
		}
		return null;
	}
}
