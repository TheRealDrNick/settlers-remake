package jsettlers.ai.army;

import jsettlers.ai.highlevel.AiStatistics;
import jsettlers.common.action.EMoveToType;
import jsettlers.common.buildings.EBuildingType;
import jsettlers.common.movable.EMovableType;
import jsettlers.common.CommonConstants;
import jsettlers.common.player.IInGamePlayer;
import jsettlers.common.player.IPlayer;
import jsettlers.common.position.ShortPoint2D;
import jsettlers.logic.buildings.Building;
import jsettlers.logic.constants.MatchConstants;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public abstract class SimpleStrategy extends ArmyModule {

	private static final byte MIN_ATTACKER_COUNT = 20;
	// how many defenders to commit per intruding enemy soldier; defenders get a home-ground combat bonus, so a modest margin suffices and
	// the rest of the army stays free to keep attacking instead of the whole army yo-yoing home for every small raid
	private static final int DEFENDERS_PER_INTRUDER = 2;
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

	// --- Adaptive aggression -----------------------------------------------------------------------------------------------------
	// Over a game the AI adjusts how hard it presses - the way a human would - based on how its campaign is actually going. If
	// repeated assaults keep bleeding troops without capturing anything, it becomes more cautious and masses (and, via the upgrade
	// module, upgrades) a bigger army before trying again, instead of feeding soldiers into the same meat-grinder; when it is clearly
	// making progress it presses the advantage and commits on a smaller margin. The multiplier is bounded so it can never overturn the
	// difficulty ordering (a very hard AI stays stronger than a hard one), and it is driven only by observed game state, so it stays
	// deterministic and replay/multiplayer safe. It is intentionally not persisted across save/load - on reload the AI simply
	// re-learns from the current situation.
	// The band is deliberately asymmetric and gentle. The aggressive side is the stronger lever - pressing a winning campaign helps a
	// stronger AI actually close out a game faster - while the cautious side is only a mild brake, because in this engine "mass more
	// before attacking" delays a conquest and must never be strong enough to stop a superior AI from winning in time (verified against
	// AiDifficultiesIT). The cautious step also only engages after a *sustained* stall, so the normal troop attrition of an ongoing
	// siege is not mistaken for a failed assault and does not ratchet the AI into permanent passivity.
	private static final float ADAPTIVE_AGGRESSION_MIN = 0.9f;  // most cautious: demand a modestly bigger army before committing
	private static final float ADAPTIVE_AGGRESSION_MAX = 1.2f;  // most aggressive: press a winning campaign, attack on a smaller edge
	private static final float ADAPTIVE_STEP_UP = 0.08f;        // per heavy tick while making progress (alive enemies losing buildings)
	private static final float ADAPTIVE_STEP_DOWN = 0.05f;      // per heavy tick once a stall is confirmed (smaller: caution is a light brake)
	private static final float ADAPTIVE_DRIFT = 0.03f;          // gentle return toward neutral when nothing decisive happened
	private static final float ARMY_POWER_LOSS_EPSILON = 2f;    // combat-power drop that counts as "we lost troops" (ignores tiny noise)
	// how many consecutive heavy ticks of "losing troops without capturing anything" before we treat the assault as genuinely stalled;
	// this stops routine combat attrition during a long siege from being read as failure
	private static final int ADAPTIVE_STALL_TICKS_BEFORE_CAUTION = 3;

	private float adaptiveAggression = 1.0f;
	private int lastEnemyMilitaryBuildingCount = -1;
	private float lastArmyPower = -1f;
	private int adaptiveStallTicks = 0;

	/**
	 * Updates {@link #adaptiveAggression} from how the campaign is going; call once per heavy tick before deciding whether to attack.
	 * Progress (alive enemies losing military buildings) makes the AI press harder; a <em>sustained</em> stall that keeps costing us
	 * troops makes it mass a modestly bigger army first; otherwise it drifts gently back toward neutral. Purely reads current statistics,
	 * so it is deterministic. The multiplier is bounded to a gentle band so it never overturns the difficulty ordering.
	 */
	protected void updateAdaptiveAggression(SoldierPositions ourSoldiers) {
		int enemyMilitaryBuildings = 0;
		for (IPlayer enemy : parent.aiStatistics.getAliveEnemiesOf(parent.getPlayer())) {
			enemyMilitaryBuildings += parent.aiStatistics.getBuildingPositionsOfTypesForPlayer(EBuildingType.MILITARY_BUILDINGS, enemy.getPlayerId()).size();
		}
		float ourStrength = parent.getPlayer().getCombatStrengthInformation().getCombatStrength(false);
		float ourPower = ourSoldiers.getSoldiersCount() * ourStrength;

		if (lastEnemyMilitaryBuildingCount >= 0) {
			boolean madeProgress = enemyMilitaryBuildings < lastEnemyMilitaryBuildingCount;
			boolean bledTroops = ourPower < lastArmyPower - ARMY_POWER_LOSS_EPSILON;
			if (madeProgress) {
				adaptiveStallTicks = 0;
				adaptiveAggression = Math.min(ADAPTIVE_AGGRESSION_MAX, adaptiveAggression + ADAPTIVE_STEP_UP); // capturing ground: press on
			} else if (bledTroops) {
				adaptiveStallTicks++;
				if (adaptiveStallTicks >= ADAPTIVE_STALL_TICKS_BEFORE_CAUTION) {
					adaptiveAggression = Math.max(ADAPTIVE_AGGRESSION_MIN, adaptiveAggression - ADAPTIVE_STEP_DOWN); // confirmed stall: mass more
				}
			} else {
				adaptiveStallTicks = 0; // quiet tick: nothing lost or gained, ease back toward neutral
				if (adaptiveAggression > 1.0f) {
					adaptiveAggression = Math.max(1.0f, adaptiveAggression - ADAPTIVE_DRIFT);
				} else if (adaptiveAggression < 1.0f) {
					adaptiveAggression = Math.min(1.0f, adaptiveAggression + ADAPTIVE_DRIFT);
				}
			}
		}
		lastEnemyMilitaryBuildingCount = enemyMilitaryBuildings;
		lastArmyPower = ourPower;
	}


	protected boolean wouldInfantryDie(SoldierPositions enemySoldierPositions) {
		return enemySoldierPositions.bowmenPositions.size() > SoldierProductionModule.BOWMEN_COUNT_OF_KILLING_INFANTRY;
	}

	/**
	 * @return true while the match is still within the opening grace period during which this AI must not launch offensive attacks. This
	 *         prevents an unfair opening rush when a scenario gives the AI a pre-placed army; defence is not affected. The base grace
	 *         ({@link CommonConstants#AI_ATTACK_GRACE_SECONDS}, configurable, 0 disables it) is scaled by the AI's play style.
	 */
	protected boolean isWithinAttackGracePeriod() {
		long graceMillis = (long) (CommonConstants.AI_ATTACK_GRACE_SECONDS.get() * 1000L * parent.getPlayStyle().attackGraceFactor);
		return graceMillis > 0 && MatchConstants.clock().getTime() < graceMillis;
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

		// adaptiveAggression scales the required margin over the game (see updateAdaptiveAggression): while stalled the AI demands a
		// bigger army, while winning it commits on a smaller edge. The MIN_ATTACKER_COUNT floor still guarantees a real standing army.
		return ourPower >= MIN_ATTACKER_COUNT && ourPower * attackerCountFactor * adaptiveAggression > enemyPower;
	}

	protected void defend(SoldierPositions soldierPositions, Set<Integer> soldiersWithOrders) {
		List<ShortPoint2D> threats = new ArrayList<>();
		for (ShortPoint2D threat : parent.getEnemiesInTown()) {
			threats.add(threat);
		}
		if (threats.isEmpty()) {
			return;
		}

		List<ShortPoint2D> allMyTroops = new ArrayList<>();
		allMyTroops.addAll(soldierPositions.bowmenPositions);
		allMyTroops.addAll(soldierPositions.pikemenPositions);
		allMyTroops.addAll(soldierPositions.swordsmenPositions);
		if (allMyTroops.isEmpty()) {
			return;
		}

		// Commit only a proportionate defensive force - enough to overwhelm the intrusion with a margin (defenders fight with a home-ground
		// bonus) - rather than the whole army. Otherwise a small raid pulls the entire army home and stalls our own offensive, which makes
		// the AI feel passive and drags games into mutual-defence stalemates. The closest troops to the breach respond; the rest stay free
		// for the attack strategy and regrouping. A large invasion naturally pulls in proportionally more (or all) of the army.
		int desiredDefenders = Math.min(allMyTroops.size(), threats.size() * DEFENDERS_PER_INTRUDER);
		allMyTroops.sort(Comparator.comparingInt(troop -> nearestThreatDistance(troop, threats)));
		List<ShortPoint2D> defenders = allMyTroops.subList(0, desiredDefenders);

		// send each chosen defender to the nearest intrusion, so the defence spreads across every breached area
		Map<ShortPoint2D, List<ShortPoint2D>> troopsByThreat = new HashMap<>();
		for (ShortPoint2D troop : defenders) {
			troopsByThreat.computeIfAbsent(nearestThreat(troop, threats), key -> new ArrayList<>()).add(troop);
		}
		for (Map.Entry<ShortPoint2D, List<ShortPoint2D>> entry : troopsByThreat.entrySet()) {
			parent.sendTroopsTo(entry.getValue(), entry.getKey(), soldiersWithOrders, EMoveToType.DEFAULT);
		}
	}

	private ShortPoint2D nearestThreat(ShortPoint2D troop, List<ShortPoint2D> threats) {
		ShortPoint2D nearest = null;
		int bestDistance = Integer.MAX_VALUE;
		for (ShortPoint2D threat : threats) {
			int distance = troop.getOnGridDistTo(threat);
			if (distance < bestDistance) {
				bestDistance = distance;
				nearest = threat;
			}
		}
		return nearest;
	}

	private int nearestThreatDistance(ShortPoint2D troop, List<ShortPoint2D> threats) {
		int bestDistance = Integer.MAX_VALUE;
		for (ShortPoint2D threat : threats) {
			bestDistance = Math.min(bestDistance, troop.getOnGridDistTo(threat));
		}
		return bestDistance;
	}

	protected void attack(SoldierPositions soldierPositions, boolean infantryWouldDie, Set<Integer> soldiersWithOrders) {
		// per-opponent adaptation may bias this toward the opponent harassing us most; falls back to the weakest enemy otherwise
		IPlayer targetEnemy = parent.getPreferredTargetEnemy(true);
		if (targetEnemy == null) return;
		ShortPoint2D targetDoor = getTargetEnemyDoorToAttack(targetEnemy);
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
