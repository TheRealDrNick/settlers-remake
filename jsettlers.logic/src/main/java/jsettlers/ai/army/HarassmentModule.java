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
package jsettlers.ai.army;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

import jsettlers.common.CommonConstants;
import jsettlers.common.action.EMoveToType;
import jsettlers.common.buildings.EBuildingType;
import jsettlers.common.movable.EMovableType;
import jsettlers.common.player.IPlayer;
import jsettlers.common.position.ShortPoint2D;
import jsettlers.logic.buildings.Building;
import jsettlers.logic.constants.MatchConstants;
import jsettlers.logic.movable.interfaces.ILogicMovable;

/**
 * Sends small raiding parties to harass the enemy's soft spots - lightly defended or unmanned buildings - instead of only committing the
 * whole army to one frontal push. This makes the AI feel more like a human opponent that probes and pressures multiple points, and less
 * predictable game to game.
 * <p>
 * It is deliberately conservative so it does not weaken the main army: it only triggers when the AI has a large surplus of idle soldiers,
 * peels off just a small squad, respects the opening grace period, and fires only probabilistically (scaled by the play style's
 * {@link jsettlers.ai.highlevel.EAiPlayStyle#harassChance}). All randomness uses the synchronised game RNG, so multiplayer/replays stay
 * deterministic. It runs before {@link SimpleAttackStrategy} so its squad is reserved out of the main assault.
 *
 * @author jsettlers behaviour AI
 */
public class HarassmentModule extends ArmyModule {

	private static final int SQUAD_SIZE = 4;
	// only harass when at least this many idle, land-reachable soldiers are free, so the main army is never cannibalised for a raid
	private static final int MIN_SURPLUS_FOR_HARASS = 25;
	// enemy soldiers within this distance of a building count as defending it
	private static final int DEFENDER_RADIUS = 10;
	// a manned building is much less attractive to raid than an unmanned one
	private static final int OCCUPIED_PENALTY = 100;
	// buildings scoring within this margin of the softest are treated as equally raidable and chosen between at random (unpredictability)
	private static final int SOFTNESS_JITTER = 4;

	private final byte playerId;

	public HarassmentModule(ArmyFramework parent) {
		super(parent);
		this.playerId = parent.getPlayerId();
	}

	@Override
	public void applyLightRules(Set<Integer> soldiersWithOrders) {
	}

	@Override
	public void applyHeavyRules(Set<Integer> soldiersWithOrders) {
		if (!parent.usesAdvancedTactics()) {
			return; // only the higher difficulties probe with harassment raids; easier AIs play a plain, predictable game
		}
		if (isWithinAttackGracePeriod()) {
			return; // no offensive raids during the opening grace period
		}
		if (!parent.existsAliveEnemy()) {
			return;
		}

		// fire only occasionally, and more often for aggressive play styles, so harassment is unpredictable rather than every tick
		float harassChance = parent.getPlayStyle().harassChance;
		if (harassChance <= 0f || MatchConstants.aiRandom().nextFloat() > harassChance) {
			return;
		}

		IPlayer enemy = parent.getWeakestEnemy(true);
		if (enemy == null) {
			return;
		}

		// only raid when we have troops to spare, so the main assault/defence keeps its full strength
		List<ShortPoint2D> idleSoldiers = collectIdleReachableSoldiers(soldiersWithOrders);
		if (idleSoldiers.size() < MIN_SURPLUS_FOR_HARASS) {
			return;
		}

		ShortPoint2D targetDoor = pickSoftTargetDoor(enemy);
		if (targetDoor == null) {
			return;
		}

		// send the closest few idle soldiers as the raiding squad
		idleSoldiers.sort(Comparator.comparingInt(position -> position.getOnGridDistTo(targetDoor)));
		List<ShortPoint2D> squad = new ArrayList<>(idleSoldiers.subList(0, Math.min(SQUAD_SIZE, idleSoldiers.size())));
		parent.sendTroopsTo(squad, targetDoor, soldiersWithOrders, EMoveToType.DEFAULT);
	}

	private boolean isWithinAttackGracePeriod() {
		long graceMillis = (long) (CommonConstants.AI_ATTACK_GRACE_SECONDS.get() * 1000L * parent.getPlayStyle().attackGraceFactor);
		return graceMillis > 0 && MatchConstants.clock().getTime() < graceMillis;
	}

	private List<ShortPoint2D> collectIdleReachableSoldiers(Set<Integer> soldiersWithOrders) {
		List<ShortPoint2D> result = new ArrayList<>();
		for (ShortPoint2D position : parent.aiStatistics.getPositionsOfMovablesWithTypesForPlayer(playerId, EMovableType.SOLDIERS)) {
			ILogicMovable movable = parent.movableGrid.getMovableAt(position.x, position.y);
			if (movable == null || soldiersWithOrders.contains(movable.getID())) {
				continue;
			}
			if (parent.isReachableByLand(position)) {
				result.add(position);
			}
		}
		return result;
	}

	/**
	 * Picks the door of a lightly defended, reachable enemy military building to raid - preferring unmanned towers and buildings with few
	 * nearby defenders, chosen at random among the softest candidates for unpredictability.
	 */
	private ShortPoint2D pickSoftTargetDoor(IPlayer enemy) {
		List<ShortPoint2D> reachableBuildings = new ArrayList<>();
		for (ShortPoint2D position : parent.aiStatistics.getBuildingPositionsOfTypesForPlayer(EBuildingType.MILITARY_BUILDINGS, enemy.getPlayerId())) {
			Building building = parent.aiStatistics.getBuildingAt(position);
			if (building != null && building.isConstructionFinished() && parent.isReachableByLand(building.getDoor())) {
				reachableBuildings.add(position);
			}
		}
		if (reachableBuildings.isEmpty()) {
			return null;
		}

		List<ShortPoint2D> enemySoldiers = parent.aiStatistics.getPositionsOfMovablesWithTypesForPlayer(enemy.getPlayerId(), EMovableType.SOLDIERS);
		int softestScore = Integer.MAX_VALUE;
		for (ShortPoint2D position : reachableBuildings) {
			softestScore = Math.min(softestScore, softnessScore(position, enemySoldiers));
		}
		List<ShortPoint2D> softCandidates = new ArrayList<>();
		for (ShortPoint2D position : reachableBuildings) {
			if (softnessScore(position, enemySoldiers) <= softestScore + SOFTNESS_JITTER) {
				softCandidates.add(position);
			}
		}
		ShortPoint2D chosen = softCandidates.get(MatchConstants.aiRandom().nextInt(softCandidates.size()));
		return parent.aiStatistics.getBuildingAt(chosen).getDoor();
	}

	private int softnessScore(ShortPoint2D buildingPosition, List<ShortPoint2D> enemySoldiers) {
		Building building = parent.aiStatistics.getBuildingAt(buildingPosition);
		int score = (building != null && building.isOccupied()) ? OCCUPIED_PENALTY : 0;
		for (ShortPoint2D soldier : enemySoldiers) {
			if (soldier.getOnGridDistTo(buildingPosition) <= DEFENDER_RADIUS) {
				score++;
			}
		}
		return score;
	}
}
