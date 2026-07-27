package jsettlers.ai.army;

import jsettlers.common.CommonConstants;
import jsettlers.common.action.EMoveToType;
import jsettlers.common.movable.EMovableType;
import jsettlers.common.player.IPlayer;
import jsettlers.common.position.ShortPoint2D;
import jsettlers.logic.buildings.Building;
import jsettlers.logic.movable.MovableManager;
import jsettlers.logic.movable.interfaces.ILogicMovable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class SimpleAttackStrategy extends SimpleStrategy {

	private static final float MAX_WOUNDED_RATIO_FOR_ATTACK = 0.5f;

	// --- attack commitment ---
	// Once an attack is launched we keep pressing the SAME target with the SAME force across subsequent heavy ticks
	// (~10s each) instead of re-deciding from scratch every tick. Re-deciding every tick was the cause of the "attack
	// oscillation": near the power threshold the coarse estimate flip-flops (possible -> not possible -> possible) and,
	// because the marching attackers were not registered as committed, the Regroup module (which runs last) pulled them
	// back home mid-march -> the army marches out, turns around, comes back, repeatedly.

	// break-off hysteresis. We LAUNCH on a modest edge (attackIsPossible - deliberately imperfect, so the AI takes
	// calculated risks and sometimes loses) but only ABORT once the committed force is being CLEARLY beaten. The wide
	// dead-band between the launch line and this abort line is what removes the flip-flop; it is intentionally NOT a
	// "never lose" oracle - a losing assault retreats, but a close fight is seen through to the end.
	private static final float ABORT_POWER_RATIO = 0.5f;
	// abort once we have lost roughly two thirds of the force we set out with (the survivors retreat rather than die to the last man)
	private static final float COMMITTED_FORCE_FLOOR_RATIO = 0.34f;
	// a soldier this close to the target door is treated as "arrived / engaged" and is not issued a fresh move order (would interrupt combat)
	private static final float ENGAGED_DISTANCE = CommonConstants.TOWER_RADIUS;

	private boolean committed = false;
	private IPlayer committedEnemy;
	private ShortPoint2D committedTargetBuilding; // building position (not the door) so we can detect capture / destruction
	private ShortPoint2D committedTargetDoor;
	private final Set<Integer> committedSoldiers = new HashSet<>();
	private int committedInitialForce;

	public SimpleAttackStrategy(ArmyFramework parent) {
		super(parent);
	}

	@Override
	public void applyHeavyRules(Set<Integer> soldiersWithOrders) {
		if (committed) {
			updateCommittedAttack(soldiersWithOrders);
			return; // while an attack is in progress this module does nothing else; the commitment ends inside updateCommittedAttack
		}

		if (isWithinAttackGracePeriod()) {
			return; // do not launch offensive attacks during the opening grace period (defence still runs in SimpleDefenseStrategy)
		}
		if (parent.existsAliveEnemy()) {
			// only consider enemies reachable by land; across-water enemies are handled by the naval invasion logic.
			// per-opponent adaptation may bias this toward the opponent harassing us most; falls back to the weakest enemy otherwise.
			// Using the same selection here (feasibility check) and in attack() (target door) keeps the two consistent within a tick.
			IPlayer targetEnemy = parent.getPreferredTargetEnemy(true);
			if (targetEnemy == null) {
				return;
			}
			SoldierPositions soldierPositions = new SoldierPositions(parent.getPlayerId(), soldiersWithOrders);
			// learn from how the campaign is going (mass more when stalled, press when winning) before deciding whether to commit
			updateAdaptiveAggression(soldierPositions);
			SoldierPositions enemySoldierPositions = new SoldierPositions(targetEnemy.getPlayerId(), Set.of());
			boolean infantryWouldDie = wouldInfantryDie(enemySoldierPositions);
			int woundedSoldiersCount = parent.findModules(HealSoldiersModule.class).findAny().map(HealSoldiersModule::getWoundedSoldiersCount).orElse(0);
			if (woundedSoldiersCount/(float)soldierPositions.getSoldiersCount() <= MAX_WOUNDED_RATIO_FOR_ATTACK &&
					attackIsPossible(soldierPositions, targetEnemy, enemySoldierPositions, infantryWouldDie)) {
				launchAttack(targetEnemy, soldierPositions, infantryWouldDie, soldiersWithOrders);
			}
		}
	}

	/**
	 * Launches (and records) a committed attack: pick a stable target, capture exactly the soldiers we send, and mark
	 * them committed so subsequent ticks keep them on the offensive and the Regroup module leaves them alone.
	 */
	private void launchAttack(IPlayer enemy, SoldierPositions soldierPositions, boolean infantryWouldDie, Set<Integer> soldiersWithOrders) {
		ShortPoint2D targetBuilding = getTargetEnemyBuildingToAttack(enemy);
		if (targetBuilding == null) {
			return;
		}
		Building building = parent.aiStatistics.getBuildingAt(targetBuilding);
		if (building == null) {
			return;
		}

		List<ShortPoint2D> attackerPositions = new ArrayList<>();
		attackerPositions.addAll(soldierPositions.getBowmenPositions());
		if (!infantryWouldDie) {
			attackerPositions.addAll(soldierPositions.getPikemenPositions());
			attackerPositions.addAll(soldierPositions.getSwordsmenPositions());
		}
		if (attackerPositions.isEmpty()) {
			return;
		}

		committedSoldiers.clear();
		committedSoldiers.addAll(toIds(attackerPositions));
		if (committedSoldiers.isEmpty()) {
			return;
		}

		committed = true;
		committedEnemy = enemy;
		committedTargetBuilding = targetBuilding;
		committedTargetDoor = building.getDoor();
		committedInitialForce = committedSoldiers.size();

		parent.sendTroopsToById(new ArrayList<>(committedSoldiers), committedTargetDoor, soldiersWithOrders, EMoveToType.DEFAULT);
	}

	/**
	 * Advances an in-progress attack. Keeps the committed force pressing the committed target, releasing soldiers that
	 * died or were re-tasked (e.g. pulled home by the defence for a serious intrusion), and breaks the assault off when
	 * the target is gone, the force is spent, or the fight is clearly lost.
	 */
	private void updateCommittedAttack(Set<Integer> soldiersWithOrders) {
		// drop soldiers that died / vanished
		committedSoldiers.removeIf(id -> {
			ILogicMovable m = MovableManager.getMovableByID(id);
			return m == null || !m.isAlive();
		});
		// release soldiers that an earlier module this tick (defence / naval invasion) has already claimed - a genuine home
		// threat is allowed to reclaim part of the attacking force, which naturally shrinks the assault (and may trip the
		// force floor below), rather than the two modules fighting over the same soldiers.
		committedSoldiers.removeAll(soldiersWithOrders);

		if (!parent.existsAliveEnemy() || committedEnemy == null) {
			clearCommitment();
			return;
		}

		Building target = parent.aiStatistics.getBuildingAt(committedTargetBuilding);
		boolean targetLost = target == null || !target.isConstructionFinished()
				|| target.getPlayer() == null || target.getPlayer().getPlayerId() != committedEnemy.getPlayerId();
		boolean forceDepleted = committedSoldiers.size() < Math.max(1, committedInitialForce * COMMITTED_FORCE_FLOOR_RATIO);
		boolean losing = isCommittedAssaultLosing();

		if (targetLost || forceDepleted || losing) {
			// break off: forget the commitment. The survivors are no longer registered, so the Regroup module will pull
			// them back to defensive positions next tick - i.e. a retreat rather than a fight to the last man.
			clearCommitment();
			return;
		}

		// keep pressing: register the committed soldiers so the Regroup module (which runs after us) does NOT yank them
		// home, and nudge any that have fallen behind back onto the STABLE target (soldiers already at the target are left
		// to fight rather than being re-ordered).
		soldiersWithOrders.addAll(committedSoldiers);
		List<Integer> stragglers = new ArrayList<>();
		for (Integer id : committedSoldiers) {
			ILogicMovable m = MovableManager.getMovableByID(id);
			if (m != null && m.getPosition().getOnGridDistTo(committedTargetDoor) > ENGAGED_DISTANCE) {
				stragglers.add(id);
			}
		}
		if (!stragglers.isEmpty()) {
			parent.sendTroopsToById(stragglers, committedTargetDoor, soldiersWithOrders, EMoveToType.DEFAULT);
		}
	}

	/**
	 * @return true when the committed force is being clearly beaten - its combat-strength-weighted power has fallen below
	 *         {@link #ABORT_POWER_RATIO} of the defender's. Uses the same coarse power estimate as the launch decision and
	 *         routes through {@link #attackerCountFactor} so an aggressive personality presses on longer (riskier) than a
	 *         cautious one, mirroring how they launch.
	 */
	private boolean isCommittedAssaultLosing() {
		// the main assault is a global front, so it weighs itself against the enemy's whole army. Delegates to the shared
		// ArmyFramework helper (also used by the detached harassment raid) so the two bodies use exactly the same abort test.
		int enemyArmy = parent.aiStatistics.getCountOfMovablesOfPlayer(committedEnemy, EMovableType.SOLDIERS);
		return parent.isCommittedForceLosing(committedEnemy, committedSoldiers.size(), enemyArmy, attackerCountFactor, ABORT_POWER_RATIO);
	}

	private void clearCommitment() {
		committed = false;
		committedEnemy = null;
		committedTargetBuilding = null;
		committedTargetDoor = null;
		committedSoldiers.clear();
		committedInitialForce = 0;
	}

	private List<Integer> toIds(List<ShortPoint2D> positions) {
		List<Integer> ids = new ArrayList<>(positions.size());
		for (ShortPoint2D position : positions) {
			ILogicMovable movable = parent.movableGrid.getMovableAt(position.x, position.y);
			if (movable != null) {
				ids.add(movable.getID());
			}
		}
		return ids;
	}

	@Override
	public void applyLightRules(Set<Integer> soldiersWithOrders) {

	}
}
