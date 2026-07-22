package jsettlers.ai.army;

import jsettlers.ai.highlevel.AiPositions;
import jsettlers.ai.highlevel.AiStatistics;
import jsettlers.ai.highlevel.EAiPlayStyle;
import jsettlers.common.action.EMoveToType;
import jsettlers.common.action.SetMaterialProductionAction;
import jsettlers.common.ai.EPlayerType;
import jsettlers.common.material.EMaterialType;
import jsettlers.common.movable.EMovableType;
import jsettlers.common.movable.ESoldierType;
import jsettlers.common.player.IPlayer;
import jsettlers.common.position.ShortPoint2D;
import jsettlers.input.tasks.MoveToGuiTask;
import jsettlers.input.tasks.SetMaterialProductionGuiTask;
import jsettlers.input.tasks.UpgradeSoldiersGuiTask;
import jsettlers.logic.map.grid.movable.MovableGrid;
import jsettlers.logic.movable.interfaces.ILogicMovable;
import jsettlers.logic.player.Player;
import jsettlers.network.client.interfaces.ITaskScheduler;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.Vector;
import java.util.stream.Stream;

public class ArmyFramework {
	final AiStatistics aiStatistics;
	private final Player player;
	final MovableGrid movableGrid;
	final ITaskScheduler taskScheduler;
	private final EAiPlayStyle playStyle;

	protected final List<ArmyModule> modules = new ArrayList<>();

	ArmyFramework(AiStatistics aiStatistics, Player player, MovableGrid movableGrid, ITaskScheduler taskScheduler, EAiPlayStyle playStyle) {
		this.aiStatistics = aiStatistics;
		this.player = player;
		this.movableGrid = movableGrid;
		this.taskScheduler = taskScheduler;
		this.playStyle = playStyle;
	}

	public EAiPlayStyle getPlayStyle() {
		return playStyle;
	}

	void addModule(ArmyModule module) {
		modules.add(module);
	}

	public <T extends ArmyModule> Stream<T> findModules(Class<T> modClazz) {
		return modules.stream().filter(modClazz::isInstance).map(modClazz::cast);
	}

	boolean existsAliveEnemy() {
		return !aiStatistics.getAliveEnemiesOf(player).isEmpty();
	}


	void setNumberOfFutureProducedMaterial(byte playerId, EMaterialType materialType, int numberToProduce) {
		if (aiStatistics.getMaterialProduction(playerId).getAbsoluteProductionRequest(materialType) != numberToProduce) {
			taskScheduler.scheduleTask(new SetMaterialProductionGuiTask(playerId, aiStatistics.getPositionOfPartition(playerId), materialType,
					SetMaterialProductionAction.EMaterialProductionType.SET_PRODUCTION, numberToProduce));
		}
	}

	void setRatioOfMaterial(byte playerId, EMaterialType materialType, float ratio) {
		if (aiStatistics.getMaterialProduction(playerId).getUserConfiguredRelativeRequestValue(materialType) != ratio) {
			taskScheduler.scheduleTask(new SetMaterialProductionGuiTask(playerId, aiStatistics.getPositionOfPartition(playerId), materialType,
					SetMaterialProductionAction.EMaterialProductionType.SET_RATIO, ratio));
		}
	}


	void sendTroopsTo(List<ShortPoint2D> attackerPositions, ShortPoint2D target, Set<Integer> soldiersWithOrders, EMoveToType moveToType) {
		List<Integer> attackerIds = new Vector<>(attackerPositions.size());
		for (ShortPoint2D attackerPosition : attackerPositions) {
			ILogicMovable movable = movableGrid.getMovableAt(attackerPosition.x, attackerPosition.y);
			if(movable == null) {
				System.err.printf("AI ERROR: Attacker at %d:%d does not exist!\n", attackerPosition.x, attackerPosition.y);
				continue;
			}
			attackerIds.add(movable.getID());
		}

		sendTroopsToById(attackerIds, target, soldiersWithOrders, moveToType);
	}

	void sendTroopsToById(Collection<Integer> attackerIds, ShortPoint2D target, Set<Integer> soldiersWithOrders, EMoveToType moveToType) {
		List<Integer> ids = new ArrayList<>(attackerIds);
		if(soldiersWithOrders != null) {
			ids.removeAll(soldiersWithOrders);
			soldiersWithOrders.addAll(attackerIds);
		}

		taskScheduler.scheduleTask(new MoveToGuiTask(player.playerId, target, ids, moveToType));
	}


	IPlayer getWeakestEnemy() {
		return getWeakestEnemy(false);
	}

	/**
	 * @param landReachableOnly
	 *            if true, only enemies that own at least one finished military building reachable by land from our base are
	 *            considered. Enemies that can only be reached across water are ignored (they are handled by the naval invasion logic).
	 */
	IPlayer getWeakestEnemy(boolean landReachableOnly) {
		IPlayer weakestEnemyPlayer = null;
		int minAmountOfEnemyId = Integer.MAX_VALUE;

		for (IPlayer enemyPlayer : aiStatistics.getAliveEnemiesOf(player)) {
			if (landReachableOnly && !hasLandReachableMilitaryBuilding(enemyPlayer)) {
				continue;
			}
			int amountOfEnemyTroops = aiStatistics.getCountOfMovablesOfPlayer(enemyPlayer, EMovableType.SOLDIERS);
			if (amountOfEnemyTroops < minAmountOfEnemyId) {
				minAmountOfEnemyId = amountOfEnemyTroops;
				weakestEnemyPlayer = enemyPlayer;
			}
		}

		return weakestEnemyPlayer;
	}

	/**
	 * @return true if the target position is on the same connected landmass as our base, i.e. reachable by walking soldiers.
	 *         Returns false when the target is separated by water (or otherwise unreachable), in which case a land attack order
	 *         would be silently dropped by the pathfinder.
	 */
	boolean isReachableByLand(ShortPoint2D target) {
		return aiStatistics.hasPlayersBlockedPartition(getPlayerId(), target.x, target.y);
	}

	/**
	 * @return true if the given enemy owns at least one finished military building whose door is reachable by land from our base.
	 */
	boolean hasLandReachableMilitaryBuilding(IPlayer enemy) {
		return aiStatistics.isEnemyReachableByLand(getPlayerId(), enemy);
	}

	boolean canUpgradeSoldiers(ESoldierType type) {
		return player.getMannaInformation().isUpgradePossible(type);
	}

	void upgradeSoldiers(ESoldierType type) {
		assert canUpgradeSoldiers(type);

		taskScheduler.scheduleTask(new UpgradeSoldiersGuiTask(player.playerId, type));
	}

	public Player getPlayer() {
		return player;
	}

	public byte getPlayerId() {
		return player.getPlayerId();
	}

	/**
	 * @return true for the higher difficulties (hard and very hard), which use the AI's advanced tactics - clever target selection,
	 *         probing harassment raids and adaptive aggression. Easier AIs deliberately forgo these and fight in a cruder, more
	 *         predictable "beginner" style, so that the difficulty setting changes how the enemy <em>behaves</em>, not only how fast
	 *         its economy grows. The difficulty ordering is preserved because only the easier levels are held back, never the harder
	 *         ones (a hard AI keeps every tactic a very-hard AI has).
	 */
	public boolean usesAdvancedTactics() {
		EPlayerType type = player.getPlayerType();
		return type == EPlayerType.AI_HARD || type == EPlayerType.AI_VERY_HARD;
	}

	AiPositions getEnemiesInTown() {
		return aiStatistics.getEnemiesInTownOf(player.getPlayerId());
	}
}
