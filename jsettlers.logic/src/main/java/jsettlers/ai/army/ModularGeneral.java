package jsettlers.ai.army;

import jsettlers.ai.highlevel.AiStatistics;
import jsettlers.ai.highlevel.EAiPlayStyle;
import jsettlers.common.ai.EPlayerType;
import jsettlers.logic.map.grid.movable.MovableGrid;
import jsettlers.logic.player.Player;
import jsettlers.network.client.interfaces.ITaskScheduler;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

public class ModularGeneral extends ArmyFramework implements ArmyGeneral {

	public ModularGeneral(AiStatistics aiStatistics, Player player, MovableGrid movableGrid, ITaskScheduler taskScheduler, EAiPlayStyle playStyle) {
		super(aiStatistics, player, movableGrid, taskScheduler, playStyle);
	}

	@Override
	public void applyHeavyRules(Set<Integer> soldiersWithOrders) {
		for (ArmyModule mod : modules) {
			mod.applyHeavyRules(soldiersWithOrders);
		}
	}

	@Override
	public void applyLightRules(Set<Integer> soldiersWithOrders) {
		for (ArmyModule mod : modules) {
			mod.applyLightRules(soldiersWithOrders);
		}
	}

	public static ArmyGeneral createDefaultGeneral(AiStatistics aiStatistics, Player player, MovableGrid movableGrid, ITaskScheduler taskScheduler, EAiPlayStyle playStyle) {
		List<Consumer<ArmyFramework>> modules = new ArrayList<>();
		modules.add(MountTowerModule::new);
		modules.add(SoldierProductionModule::new);
		modules.add(UpgradeSoldiersModule::new);
		modules.add(HealSoldiersModule::new);
		modules.add(OpponentAdaptationModule::new);
		// Imperfect-information scouting is only granted to the easy tiers so they explore the map like beginners. The hard tiers keep their
		// exact current module list (and thus byte-for-byte identical behaviour), which is what the difficulty-regression suite depends on.
		if (hasLimitedInformation(player.getPlayerType())) {
			modules.add(ScoutingModule::new);
		}
		modules.add(SimpleDefenseStrategy::new);
		modules.add(NavalInvasionModule::new);
		modules.add(ColonizationModule::new);
		modules.add(HarassmentModule::new);
		modules.add(SimpleAttackStrategy::new);
		modules.add(RegroupArmyModule::new);

		@SuppressWarnings("unchecked")
		Consumer<ArmyFramework>[] moduleArray = modules.toArray(new Consumer[0]);
		return createGeneral(aiStatistics, player, movableGrid, taskScheduler, playStyle, moduleArray);
	}

	/**
	 * @return {@code true} for the easy AI tiers, which act on limited/scouted information rather than the omniscient full-map view the
	 *         harder tiers use. Restricting the behaviour change to the easy tiers keeps the harder tiers as strong as today and therefore
	 *         preserves the harder-beats-easier difficulty ladder.
	 */
	private static boolean hasLimitedInformation(EPlayerType playerType) {
		return playerType == EPlayerType.AI_EASY || playerType == EPlayerType.AI_VERY_EASY;
	}

	@SafeVarargs
	public static ArmyGeneral createGeneral(AiStatistics aiStatistics, Player player, MovableGrid movableGrid, ITaskScheduler taskScheduler, EAiPlayStyle playStyle, Consumer<ArmyFramework>... modules) {
		ModularGeneral general = new ModularGeneral(aiStatistics, player, movableGrid, taskScheduler, playStyle);
		for (Consumer<ArmyFramework> module : modules) {
			module.accept(general);
		}
		return general;
	}
}
