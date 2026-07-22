package jsettlers.ai.army;

import java.util.List;
import java.util.Set;

import jsettlers.common.buildings.EBuildingType;
import jsettlers.common.action.EMoveToType;
import jsettlers.common.movable.EMovableType;
import jsettlers.common.player.IPlayer;
import jsettlers.common.position.ShortPoint2D;
import jsettlers.logic.movable.MovableManager;
import jsettlers.logic.movable.interfaces.ILogicMovable;

/**
 * Imperfect-information / scouting behaviour for the <em>easy</em> AI tiers only.
 * <p>
 * The rest of the AI is omniscient: it reads the whole map through {@link jsettlers.ai.highlevel.AiStatistics} and always knows where every
 * enemy building and soldier is. That makes the harder AIs strong (which the difficulty-regression suite {@code AiDifficultiesIT} depends on),
 * but it also means beginners never feel like they are exploring an unknown map. This module is a first, deliberately additive step towards
 * genuine scouting: from time to time it detaches a single spare soldier from a comfortably-sized standing army and sends it on a short
 * excursion towards the frontier with the nearest reachable enemy, then releases it back into the army pool.
 * <p>
 * <b>Why this is balance-safe.</b> The module is <em>only</em> installed on {@link jsettlers.common.ai.EPlayerType#AI_EASY} and
 * {@link jsettlers.common.ai.EPlayerType#AI_VERY_EASY} players (see {@link ModularGeneral#createDefaultGeneral}). The harder tiers
 * ({@code AI_HARD}, {@code AI_VERY_HARD}) never receive this module, so their module list and therefore their behaviour is byte-for-byte
 * unchanged. In particular the tightest regression test {@code veryHardShouldConquerHard} pits only hard tiers against each other, neither of
 * which owns a {@code ScoutingModule}, so it is completely unaffected. For the easy tiers the effect is a small, temporary excursion of one
 * spare soldier; the army is only tapped once it exceeds {@link #MIN_SOLDIERS_BEFORE_SCOUTING}, so defence and attack strength are never
 * meaningfully reduced (if anything the tiny excursion very slightly weakens the easy AI, which only reinforces the difficulty ladder).
 * <p>
 * <b>Determinism.</b> The module makes <em>no</em> random draws (it does not touch {@code MatchConstants.aiRandom()}), so it neither perturbs
 * the shared AI RNG stream seen by other players in the same match nor endangers replay/multiplayer determinism. All selections are
 * distance-based with a stable coordinate tie-break.
 *
 * @author Claude
 */
public class ScoutingModule extends ArmyModule {

	/** Heavy rules tick roughly every 10 seconds; wait this many ticks between two scouting excursions. */
	private static final int SCOUT_COOLDOWN_HEAVY_TICKS = 6;
	/** Recall the scout after at most this many heavy ticks, even if it never reached the frontier (e.g. blocked terrain). */
	private static final int MAX_SCOUT_EXCURSION_HEAVY_TICKS = 6;
	/** Only ever detach a scout once the standing army is comfortably above this size, so defence/attack are never starved. */
	private static final int MIN_SOLDIERS_BEFORE_SCOUTING = 20;
	/** How far along the line from our base towards the enemy the scout probes (0 = home, 1 = the enemy building itself). */
	private static final float FRONTIER_FRACTION = 0.6f;
	/** The scout counts as "arrived" once it is within this grid distance of its frontier target. */
	private static final int ARRIVAL_DISTANCE = 5;

	private int ticksUntilNextScout = 0;
	private Integer scoutId = null;
	private ShortPoint2D scoutTarget = null;
	private int scoutTicksRemaining = 0;

	public ScoutingModule(ArmyFramework parent) {
		super(parent);
	}

	@Override
	public void applyHeavyRules(Set<Integer> soldiersWithOrders) {
		if (scoutId != null) {
			manageActiveScout(soldiersWithOrders);
			return;
		}
		if (ticksUntilNextScout > 0) {
			ticksUntilNextScout--;
			return;
		}
		tryStartScout(soldiersWithOrders);
	}

	@Override
	public void applyLightRules(Set<Integer> soldiersWithOrders) {
	}

	/**
	 * Keeps the currently dispatched scout reserved (so the other modules leave it alone) until it reaches the frontier, dies, or the
	 * excursion times out, at which point it is released back into the army pool and the cooldown starts.
	 */
	private void manageActiveScout(Set<Integer> soldiersWithOrders) {
		ILogicMovable scout = MovableManager.getMovableByID(scoutId);
		if (scout == null) { // the scout died on its excursion - release and cool down
			endExcursion();
			return;
		}

		boolean arrived = scout.getPosition().getOnGridDistTo(scoutTarget) <= ARRIVAL_DISTANCE;
		if (arrived || --scoutTicksRemaining <= 0) {
			endExcursion(); // stop reserving it; from the next tick the regular modules may use it again
			return;
		}

		soldiersWithOrders.add(scoutId); // still scouting: reserve it from attack/regroup/defence this tick
	}

	private void endExcursion() {
		scoutId = null;
		scoutTarget = null;
		scoutTicksRemaining = 0;
		ticksUntilNextScout = SCOUT_COOLDOWN_HEAVY_TICKS;
	}

	private void tryStartScout(Set<Integer> soldiersWithOrders) {
		byte myId = parent.getPlayerId();
		List<ShortPoint2D> myMilitaryBuildings = parent.aiStatistics.getBuildingPositionsOfTypesForPlayer(EBuildingType.MILITARY_BUILDINGS, myId);
		if (myMilitaryBuildings.isEmpty()) {
			return;
		}
		if (parent.aiStatistics.getCountOfMovablesOfPlayer(parent.getPlayer(), EMovableType.SOLDIERS) < MIN_SOLDIERS_BEFORE_SCOUTING) {
			return; // keep the whole army at home until it is comfortably large
		}

		ShortPoint2D baseCenter = jsettlers.ai.highlevel.AiStatistics.calculateAveragePointFromList(myMilitaryBuildings);

		ShortPoint2D enemyBuilding = findNearestReachableEnemyMilitaryBuilding(baseCenter);
		if (enemyBuilding == null) {
			return; // no reachable enemy to scout towards (e.g. across water) - nothing to do
		}

		ShortPoint2D scoutStart = findNearestSpareSoldier(baseCenter, soldiersWithOrders);
		if (scoutStart == null) {
			return;
		}
		ILogicMovable soldier = parent.movableGrid.getMovableAt(scoutStart.x, scoutStart.y);
		if (soldier == null) {
			return;
		}

		ShortPoint2D frontier = interpolate(baseCenter, enemyBuilding, FRONTIER_FRACTION);
		parent.sendTroopsToById(List.of(soldier.getID()), frontier, soldiersWithOrders, EMoveToType.DEFAULT);

		scoutId = soldier.getID();
		scoutTarget = frontier;
		scoutTicksRemaining = MAX_SCOUT_EXCURSION_HEAVY_TICKS;
	}

	/**
	 * @return the land-reachable enemy military building closest to our base (with a stable x/y tie-break), or {@code null} if none exists.
	 */
	private ShortPoint2D findNearestReachableEnemyMilitaryBuilding(ShortPoint2D baseCenter) {
		ShortPoint2D best = null;
		int bestDistance = Integer.MAX_VALUE;
		for (IPlayer enemy : parent.aiStatistics.getAliveEnemiesOf(parent.getPlayer())) {
			for (ShortPoint2D building : parent.aiStatistics.getBuildingPositionsOfTypesForPlayer(EBuildingType.MILITARY_BUILDINGS, enemy.getPlayerId())) {
				if (!parent.isReachableByLand(building)) {
					continue;
				}
				int distance = baseCenter.getOnGridDistTo(building);
				if (isCloser(building, distance, best, bestDistance)) {
					best = building;
					bestDistance = distance;
				}
			}
		}
		return best;
	}

	/**
	 * @return the position of the spare soldier (not already carrying an order) closest to our base, or {@code null} if there is none.
	 */
	private ShortPoint2D findNearestSpareSoldier(ShortPoint2D baseCenter, Set<Integer> soldiersWithOrders) {
		ShortPoint2D best = null;
		int bestDistance = Integer.MAX_VALUE;
		for (ShortPoint2D position : parent.aiStatistics.getPositionsOfMovablesWithTypesForPlayer(parent.getPlayerId(), EMovableType.SOLDIERS)) {
			ILogicMovable movable = parent.movableGrid.getMovableAt(position.x, position.y);
			if (movable == null || soldiersWithOrders.contains(movable.getID())) {
				continue;
			}
			int distance = baseCenter.getOnGridDistTo(position);
			if (isCloser(position, distance, best, bestDistance)) {
				best = position;
				bestDistance = distance;
			}
		}
		return best;
	}

	/** Stable "is this candidate a better (closer) pick": nearer wins, ties broken deterministically by x then y. */
	private static boolean isCloser(ShortPoint2D candidate, int candidateDistance, ShortPoint2D current, int currentDistance) {
		if (current == null || candidateDistance < currentDistance) {
			return true;
		}
		if (candidateDistance > currentDistance) {
			return false;
		}
		if (candidate.x != current.x) {
			return candidate.x < current.x;
		}
		return candidate.y < current.y;
	}

	private static ShortPoint2D interpolate(ShortPoint2D from, ShortPoint2D to, float fraction) {
		int x = Math.round(from.x + (to.x - from.x) * fraction);
		int y = Math.round(from.y + (to.y - from.y) * fraction);
		return new ShortPoint2D(x, y);
	}
}
