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
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import jsettlers.ai.highlevel.AiStatistics;
import jsettlers.common.CommonConstants;
import jsettlers.common.action.EMoveToType;
import jsettlers.common.buildings.EBuildingType;
import jsettlers.common.movable.EMovableType;
import jsettlers.common.player.IPlayer;
import jsettlers.common.position.ShortPoint2D;
import jsettlers.logic.buildings.Building;
import jsettlers.logic.constants.MatchConstants;
import jsettlers.logic.movable.MovableManager;
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
 * <p>
 * Once a raid is launched the squad becomes a <em>committed detached body</em>, mirroring {@link SimpleAttackStrategy}'s committed
 * assault: it keeps pressing the SAME soft target across heavy ticks and is only broken off once it is CLEARLY losing its local fight -
 * at which point it is unregistered and {@link RegroupArmyModule} (which runs after us) walks the survivors back to the nearest friendly
 * military building, i.e. a retreat rather than a fight to the last man. This stops a probing squad from feeding itself into a garrison it
 * blundered into, while the wide "launch on a modest edge, abort only when clearly beaten" dead-band means winnable skirmishes are still
 * pressed. All of this only ever runs for a squad that was launched through the gated path below, so where the module was inert before it
 * stays inert.
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

	// --- break-off hysteresis for the detached raid (mirrors SimpleAttackStrategy's committed-assault dead-band) ---
	// Break off once the squad's combat-strength-weighted power, after the personality margin, has fallen below this fraction of the LOCAL
	// defenders' power. NOTE: unlike the main assault (which weighs itself against the enemy's whole army), a raid weighs itself only against
	// the defenders physically around it - a 4-man squad compared to the enemy's entire army would read as doomed before it even set out.
	private static final float ABORT_POWER_RATIO = 0.5f;
	// also break off once the squad has been ground down past this fraction of the force it set out with (survivors retreat, not die to a man)
	private static final float FORCE_FLOOR_RATIO = 0.34f;
	// enemy soldiers within this distance of the squad's centre count as the local force fighting it (coarse, like the launch estimate)
	private static final int LOCAL_DEFENDER_RADIUS = 12;
	// a squad member this close to the target door is treated as engaged and is not re-nudged (mirrors SimpleAttackStrategy.ENGAGED_DISTANCE)
	private static final float ENGAGED_DISTANCE = CommonConstants.TOWER_RADIUS;

	private final byte playerId;

	// --- committed raid state (empty whenever no raid is in progress) ---
	private final Set<Integer> raidingSquad = new HashSet<>();
	private int raidInitialForce;
	private IPlayer raidEnemy;
	private ShortPoint2D raidTargetBuilding; // building position (not door) so we can detect capture / destruction
	private ShortPoint2D raidTargetDoor;

	public HarassmentModule(ArmyFramework parent) {
		super(parent);
		this.playerId = parent.getPlayerId();
	}

	@Override
	public void applyLightRules(Set<Integer> soldiersWithOrders) {
	}

	@Override
	public void applyHeavyRules(Set<Integer> soldiersWithOrders) {
		if (!raidingSquad.isEmpty()) {
			updateRaid(soldiersWithOrders);
			return; // while a raid is in progress this module manages that squad and does not peel off a new one
		}

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

		ShortPoint2D targetBuilding = pickSoftTarget(enemy);
		if (targetBuilding == null) {
			return;
		}
		Building building = parent.aiStatistics.getBuildingAt(targetBuilding);
		if (building == null) {
			return;
		}
		ShortPoint2D targetDoor = building.getDoor();

		// send the closest few idle soldiers as the raiding squad, and remember them as a committed detached body
		idleSoldiers.sort(Comparator.comparingInt(position -> position.getOnGridDistTo(targetDoor)));
		List<ShortPoint2D> squad = new ArrayList<>(idleSoldiers.subList(0, Math.min(SQUAD_SIZE, idleSoldiers.size())));
		launchRaid(enemy, targetBuilding, targetDoor, squad, soldiersWithOrders);
	}

	/** Launches (and records) a committed raid: capture exactly the soldiers we send so later ticks can keep them together or retreat them. */
	private void launchRaid(IPlayer enemy, ShortPoint2D targetBuilding, ShortPoint2D targetDoor, List<ShortPoint2D> squad, Set<Integer> soldiersWithOrders) {
		raidingSquad.clear();
		for (ShortPoint2D position : squad) {
			ILogicMovable movable = parent.movableGrid.getMovableAt(position.x, position.y);
			if (movable != null) {
				raidingSquad.add(movable.getID());
			}
		}
		if (raidingSquad.isEmpty()) {
			return;
		}
		raidEnemy = enemy;
		raidTargetBuilding = targetBuilding;
		raidTargetDoor = targetDoor;
		raidInitialForce = raidingSquad.size();
		parent.sendTroopsToById(new ArrayList<>(raidingSquad), targetDoor, soldiersWithOrders, EMoveToType.DEFAULT);
	}

	/**
	 * Advances an in-progress raid: drops squad members that died or were re-tasked, breaks the raid off when the target is gone, the squad
	 * is spent, or the local fight is clearly lost, and otherwise keeps the squad pressing the SAME target.
	 */
	private void updateRaid(Set<Integer> soldiersWithOrders) {
		// drop squad members that died / vanished
		raidingSquad.removeIf(id -> {
			ILogicMovable m = MovableManager.getMovableByID(id);
			return m == null || !m.isAlive();
		});
		// release members an earlier module this tick has already claimed (e.g. the defence pulling one home for a real intrusion); this
		// naturally shrinks the raid rather than two modules fighting over the same soldier, and may itself trip the force floor below.
		raidingSquad.removeAll(soldiersWithOrders);

		if (raidingSquad.isEmpty() || raidEnemy == null || !parent.existsAliveEnemy()) {
			clearRaid();
			return;
		}

		Building target = parent.aiStatistics.getBuildingAt(raidTargetBuilding);
		boolean targetLost = target == null || !target.isConstructionFinished()
				|| target.getPlayer() == null || target.getPlayer().getPlayerId() != raidEnemy.getPlayerId();
		boolean forceDepleted = raidingSquad.size() < Math.max(1, raidInitialForce * FORCE_FLOOR_RATIO);
		boolean losing = isRaidLosing();

		if (targetLost || forceDepleted || losing) {
			// break off: forget the raid and do NOT re-register the squad. The survivors are then free, so RegroupArmyModule (which runs
			// after us) walks them back to the nearest friendly military building - a retreat, exactly like SimpleAttackStrategy's abort.
			clearRaid();
			return;
		}

		// keep pressing the STABLE target: register the squad so RegroupArmyModule leaves it alone, and nudge any members that have fallen
		// behind back onto the target (members already engaged at the target are left to fight rather than re-ordered).
		soldiersWithOrders.addAll(raidingSquad);
		List<Integer> stragglers = new ArrayList<>();
		for (Integer id : raidingSquad) {
			ILogicMovable m = MovableManager.getMovableByID(id);
			if (m != null && m.getPosition().getOnGridDistTo(raidTargetDoor) > ENGAGED_DISTANCE) {
				stragglers.add(id);
			}
		}
		if (!stragglers.isEmpty()) {
			parent.sendTroopsToById(stragglers, raidTargetDoor, soldiersWithOrders, EMoveToType.DEFAULT);
		}
	}

	/**
	 * @return true when the raiding squad is clearly losing its LOCAL fight - its power has fallen below {@link #ABORT_POWER_RATIO} of the
	 *         enemy soldiers gathered around it. Routes through the shared {@link ArmyFramework#isCommittedForceLosing} test (the same one
	 *         the main assault uses) and is flavoured by the play style's aggression so a bolder personality presses on a little longer.
	 */
	private boolean isRaidLosing() {
		return parent.isCommittedForceLosing(raidEnemy, raidingSquad.size(), countLocalDefenders(),
				parent.getPlayStyle().aggressionFactor, ABORT_POWER_RATIO);
	}

	/** Counts the enemy soldiers gathered within {@link #LOCAL_DEFENDER_RADIUS} of the squad's centre - the force actually fighting it. */
	private int countLocalDefenders() {
		ShortPoint2D center = squadCenter();
		if (center == null) {
			return 0;
		}
		int count = 0;
		for (ShortPoint2D soldier : parent.aiStatistics.getPositionsOfMovablesWithTypesForPlayer(raidEnemy.getPlayerId(), EMovableType.SOLDIERS)) {
			if (soldier.getOnGridDistTo(center) <= LOCAL_DEFENDER_RADIUS) {
				count++;
			}
		}
		return count;
	}

	private ShortPoint2D squadCenter() {
		List<ShortPoint2D> positions = new ArrayList<>();
		for (Integer id : raidingSquad) {
			ILogicMovable m = MovableManager.getMovableByID(id);
			if (m != null) {
				positions.add(m.getPosition());
			}
		}
		return positions.isEmpty() ? null : AiStatistics.calculateAveragePointFromList(positions);
	}

	private void clearRaid() {
		raidingSquad.clear();
		raidEnemy = null;
		raidTargetBuilding = null;
		raidTargetDoor = null;
		raidInitialForce = 0;
	}

	private boolean isWithinAttackGracePeriod() {
		// like SimpleStrategy: if the match has a peacetime, the effective grace lasts at least until it ends (raiding earlier would
		// only send troops that cannot do any harm); the peacetime is a hard truce and not scaled by the play style.
		long graceEndMillis = (long) (CommonConstants.AI_ATTACK_GRACE_SECONDS.get() * 1000L * parent.getPlayStyle().attackGraceFactor);
		long effectiveEndMillis = Math.max(graceEndMillis, MatchConstants.getPeaceTimeEndMs());
		return effectiveEndMillis > 0 && MatchConstants.clock().getTime() < effectiveEndMillis;
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
	 * Picks a lightly defended, reachable enemy military building to raid - preferring unmanned towers and buildings with few nearby
	 * defenders, chosen at random among the softest candidates for unpredictability. Returns the building position (not the door) so the
	 * committed raid can detect once the building has been captured or destroyed.
	 */
	private ShortPoint2D pickSoftTarget(IPlayer enemy) {
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
		return softCandidates.get(MatchConstants.aiRandom().nextInt(softCandidates.size()));
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
