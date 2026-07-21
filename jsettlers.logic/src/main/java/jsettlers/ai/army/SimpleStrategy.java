package jsettlers.ai.army;

import jsettlers.ai.highlevel.AiStatistics;
import jsettlers.common.action.EMoveToType;
import jsettlers.common.buildings.EBuildingType;
import jsettlers.common.movable.EMovableType;
import jsettlers.common.player.IInGamePlayer;
import jsettlers.common.player.IPlayer;
import jsettlers.common.position.ShortPoint2D;
import jsettlers.logic.buildings.Building;
import jsettlers.logic.constants.MatchConstants;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.Vector;

public abstract class SimpleStrategy extends ArmyModule {

	private static final byte MIN_ATTACKER_COUNT = 20;
	private final float attackerCountFactor;

	// how readily each difficulty presses an attack: a HIGHER factor means it attacks with a smaller (or no) numeric advantage.
	// indexed by EPlayerType.ordinal(): AI_VERY_EASY, AI_EASY, AI_HARD, AI_VERY_HARD, HUMAN. Increasing with difficulty so that stronger
	// AIs are more aggressive rather than more passive (the previous values were inverted, which made hard AIs turtle).
	private static final float[] ATTACKER_COUNT_FACTOR_BY_PLAYER_TYPE = { 0.9F, 1.0F, 1.1F, 1.2F, 0F };

	public SimpleStrategy(ArmyFramework parent) {
		super(parent);

		// difficulty sets the base aggressiveness; the per-game play style then flavours it (a "turtle" attacks more cautiously,
		// an "aggressor" more readily) without changing the difficulty-defining economy.
		this.attackerCountFactor = ATTACKER_COUNT_FACTOR_BY_PLAYER_TYPE[parent.getPlayer().getPlayerType().ordinal()]
				* parent.getPlayStyle().aggressionFactor;
	}


	protected boolean wouldInfantryDie(SoldierPositions enemySoldierPositions) {
		return enemySoldierPositions.bowmenPositions.size() > SoldierProductionModule.BOWMEN_COUNT_OF_KILLING_INFANTRY;
	}


	protected boolean attackIsPossible(SoldierPositions soldierPositions, IPlayer enemy, SoldierPositions enemySoldierPositions, boolean infantryWouldDie) {
		// The attack is gated only by having a real standing army (MIN_ATTACKER_COUNT) and a favourable combat-power estimate below.
		// Previously it also required a full weapon-production chain (coal/iron mine, ironmelt, weaponsmith, barrack) to exist; that made
		// the AI refuse to attack with a large existing army the moment any one of those buildings was missing or destroyed, which is a
		// major cause of the AI passively sitting on troops it never uses.

		// Combat-power estimate: weight BOTH sides by their combat strength (which reflects troop upgrades / manna bonuses), not just our
		// own. The previous check compared our strength-weighted count against the enemy's raw head count, so it ignored enemy troop
		// quality and would happily attack a smaller but heavily upgraded enemy army. The attackerCountFactor (difficulty + play style)
		// is the margin by which we must out-power the enemy before committing.
		float ourStrength = parent.getPlayer().getCombatStrengthInformation().getCombatStrength(false);
		// enemies from getAliveEnemiesOf are concrete players; guard the cast and fall back to a neutral strength of 1 just in case
		float enemyStrength = enemy instanceof IInGamePlayer ? ((IInGamePlayer) enemy).getCombatStrengthInformation().getCombatStrength(false) : 1f;

		float ourAttackerCount = infantryWouldDie ? soldierPositions.bowmenPositions.size() : soldierPositions.getSoldiersCount();
		float ourPower = ourAttackerCount * ourStrength;
		float enemyPower = enemySoldierPositions.getSoldiersCount() * enemyStrength;

		return ourPower >= MIN_ATTACKER_COUNT && ourPower * attackerCountFactor > enemyPower;
	}

	protected void defend(SoldierPositions soldierPositions, Set<Integer> soldiersWithOrders) {
		List<ShortPoint2D> allMyTroops = new Vector<>();
		allMyTroops.addAll(soldierPositions.bowmenPositions);
		allMyTroops.addAll(soldierPositions.pikemenPositions);
		allMyTroops.addAll(soldierPositions.swordsmenPositions);
		parent.sendTroopsTo(allMyTroops, parent.getEnemiesInTown().iterator().next(), soldiersWithOrders, EMoveToType.DEFAULT);
	}

	protected void attack(SoldierPositions soldierPositions, boolean infantryWouldDie, Set<Integer> soldiersWithOrders) {
		IPlayer weakestEnemy = parent.getWeakestEnemy(true);
		if (weakestEnemy == null) return;
		ShortPoint2D targetDoor = getTargetEnemyDoorToAttack(weakestEnemy);
		if(targetDoor == null) return;

		if (infantryWouldDie) {
			parent.sendTroopsTo(soldierPositions.bowmenPositions, targetDoor, soldiersWithOrders, EMoveToType.DEFAULT);
		} else {
			List<ShortPoint2D> soldiers = new ArrayList<>(soldierPositions.bowmenPositions.size() + soldierPositions.pikemenPositions.size() + soldierPositions.swordsmenPositions.size());
			soldiers.addAll(soldierPositions.bowmenPositions);
			soldiers.addAll(soldierPositions.pikemenPositions);
			soldiers.addAll(soldierPositions.swordsmenPositions);
			parent.sendTroopsTo(soldiers, targetDoor, soldiersWithOrders, EMoveToType.DEFAULT);
		}
	}

	// how strongly a defended target is avoided when picking what to attack, expressed in "tiles of extra detour" per defender / per manned tower
	private static final int TARGET_OCCUPIED_TOWER_WEIGHT = 30;
	private static final int TARGET_DEFENDER_WEIGHT = 15;
	private static final int TARGET_DEFENDER_RADIUS = 10;
	// targets scoring within this many points of the best are treated as equally attractive and chosen between at random (unpredictability)
	private static final int TARGET_SCORE_JITTER = 12;

	protected ShortPoint2D getTargetEnemyDoorToAttack(IPlayer enemyToAttack) {
		List<ShortPoint2D> myMilitaryBuildings = parent.aiStatistics.getBuildingPositionsOfTypesForPlayer(EBuildingType.MILITARY_BUILDINGS, parent.getPlayerId());
		if (myMilitaryBuildings.isEmpty()) {
			return null;
		}
		ShortPoint2D myBaseAveragePoint = AiStatistics.calculateAveragePointFromList(myMilitaryBuildings);
		List<ShortPoint2D> enemyMilitaryBuildings = parent.aiStatistics.getBuildingPositionsOfTypesForPlayer(EBuildingType.MILITARY_BUILDINGS, enemyToAttack.getPlayerId());
		// ignore unfinished buildings and buildings we cannot reach by land (e.g. on another landmass across water):
		// sending soldiers there would be silently dropped by the pathfinder. Across-water enemies are handled by the naval invasion logic.
		enemyMilitaryBuildings.removeIf(shortPoint2D -> {
			Building building = parent.aiStatistics.getBuildingAt(shortPoint2D);
			return building == null || !building.isConstructionFinished() || !parent.isReachableByLand(building.getDoor());
		});
		if (enemyMilitaryBuildings.isEmpty()) {
			return null;
		}

		// prefer the least defended reachable building - an unmanned tower is a free conquest (upstream #77) - while still favouring
		// closer targets so the army does not march across the whole map for a marginally softer tower.
		List<ShortPoint2D> enemySoldiers = parent.aiStatistics.getPositionsOfMovablesWithTypesForPlayer(enemyToAttack.getPlayerId(), EMovableType.SOLDIERS);
		int bestScore = Integer.MAX_VALUE;
		for (ShortPoint2D position : enemyMilitaryBuildings) {
			bestScore = Math.min(bestScore, targetScore(position, myBaseAveragePoint, enemySoldiers));
		}
		// pick at random among all targets that are roughly as attractive as the best one. This keeps the AI going after soft targets
		// but makes it less predictable than always hitting the single best-scoring building. Uses the synchronised game RNG so that
		// multiplayer and replays stay deterministic.
		List<ShortPoint2D> topCandidates = new ArrayList<>();
		for (ShortPoint2D position : enemyMilitaryBuildings) {
			if (targetScore(position, myBaseAveragePoint, enemySoldiers) <= bestScore + TARGET_SCORE_JITTER) {
				topCandidates.add(position);
			}
		}
		ShortPoint2D chosen = topCandidates.get(MatchConstants.aiRandom().nextInt(topCandidates.size()));
		return parent.aiStatistics.getBuildingAt(chosen).getDoor();
	}

	private int targetScore(ShortPoint2D buildingPosition, ShortPoint2D myBase, List<ShortPoint2D> enemySoldiers) {
		Building building = parent.aiStatistics.getBuildingAt(buildingPosition);
		int score = buildingPosition.getOnGridDistTo(myBase);
		if (building != null && building.isOccupied()) {
			score += TARGET_OCCUPIED_TOWER_WEIGHT;
		}
		for (ShortPoint2D soldier : enemySoldiers) {
			if (soldier.getOnGridDistTo(buildingPosition) <= TARGET_DEFENDER_RADIUS) {
				score += TARGET_DEFENDER_WEIGHT;
			}
		}
		return score;
	}

	protected class SoldierPositions {
		protected SoldierPositions(byte playerId, Set<Integer> soldiersWithOrders) {
			swordsmenPositions = calculateSituation(playerId, EMovableType.SWORDSMEN, soldiersWithOrders);
			bowmenPositions = calculateSituation(playerId, EMovableType.BOWMEN, soldiersWithOrders);
			pikemenPositions = calculateSituation(playerId, EMovableType.PIKEMEN, soldiersWithOrders);
		}

		private final List<ShortPoint2D> swordsmenPositions;
		private final List<ShortPoint2D> bowmenPositions;
		private final List<ShortPoint2D> pikemenPositions;

		int getSoldiersCount() {
			return swordsmenPositions.size() + bowmenPositions.size() + pikemenPositions.size();
		}


		protected List<ShortPoint2D> calculateSituation(byte playerId, Set<EMovableType> soldierTypes, Set<Integer> soldiersWithOrders) {
			List<ShortPoint2D> soldierPositions = new ArrayList<>();
			for(EMovableType soldierType : soldierTypes) {
				parent.aiStatistics.getPositionsOfMovablesWithTypeForPlayer(playerId, soldierType)
						.stream().filter(pos -> !soldiersWithOrders.contains(
								parent.movableGrid.getMovableAt(pos.x, pos.y).getID()))
								.forEach(soldierPositions::add);
			}
			return soldierPositions;
		}
	}
}
