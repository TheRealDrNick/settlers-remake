package jsettlers.ai.army;

import jsettlers.common.player.IPlayer;

import java.util.Set;

public class SimpleAttackStrategy extends SimpleStrategy {

	private static final float MAX_WOUNDED_RATIO_FOR_ATTACK = 0.5f;

	public SimpleAttackStrategy(ArmyFramework parent) {
		super(parent);
	}

	@Override
	public void applyHeavyRules(Set<Integer> soldiersWithOrders) {
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
				attack(soldierPositions, infantryWouldDie, soldiersWithOrders);
			}
		}
	}

	@Override
	public void applyLightRules(Set<Integer> soldiersWithOrders) {

	}
}
